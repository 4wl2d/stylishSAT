package com.tomilov.stylishsat.data

import android.app.Application
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Real bank and production actions; a workflow check, not hours of learner study. */
@RunWith(AndroidJUnit4::class)
class IntensiveFlowDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private class MemorySettings(exam: Exam) : SettingsStore {
        override val flow = MutableStateFlow(Settings(exam, Language.EN))
        override suspend fun exam(value: Exam) { flow.value = flow.value.copy(exam = value) }
        override suspend fun language(value: Language) { flow.value = flow.value.copy(language = value) }
    }
    private class StudyClock : Clock() {
        var value: Instant = Instant.parse("2026-11-01T09:00:00Z")
        override fun instant(): Instant = value
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        fun advance() { value = value.plusSeconds(1) }
    }
    private val lastPhase = AtomicReference("initializing")
    private val lastProgress = AtomicLong()
    private var scenario = ""
    private fun mark(phase: String, vm: StudyViewModel? = null) {
        lastPhase.set(phase)
        lastProgress.set(SystemClock.elapsedRealtime())
        val state = vm?.state?.value
        val session = state?.session
        Log.i("IntensiveFlowTest", "$scenario phase=$phase loading=${state?.loading} writes=${state?.pendingWrites} " +
            "step=${session?.index}/${session?.stepCount} exercise=${session?.exercise?.id} attempts=${state?.attempts?.size}")
    }
    private fun main(block: () -> Unit) {
        mark("main/queued")
        val failure = AtomicReference<Throwable?>(null)
        instrumentation.runOnMainSync {
            try { mark("main/running"); block() }
            catch (error: Throwable) { failure.set(error); Log.e("IntensiveFlowTest", "$scenario main/threw", error) }
        }
        mark("main/returned")
        failure.get()?.let { throw it }
    }
    private fun startProbe(): Pair<AtomicBoolean, Thread> {
        val running = AtomicBoolean(true)
        val testThread = Thread.currentThread()
        mark("initializing")
        val thread = Thread({
            var captured = -1L
            var captures = 0
            while (running.get()) {
                try { Thread.sleep(1000) } catch (_: InterruptedException) { break }
                val progress = lastProgress.get()
                if (SystemClock.elapsedRealtime() - progress < 10_000 || progress == captured || captures >= 3) continue
                captured = progress; captures++
                Log.w("IntensiveFlowProbe", "$scenario capture=$captures phase=${lastPhase.get()} begin")
                fun stack(thread: Thread, frames: Array<StackTraceElement>) {
                    Log.w("IntensiveFlowProbe", "thread=${thread.name} state=${thread.state}")
                    frames.take(40).forEach { Log.w("IntensiveFlowProbe", "  at $it") }
                }
                val mainThread = Looper.getMainLooper().thread
                stack(mainThread, mainThread.stackTrace)
                stack(testThread, testThread.stackTrace)
                Thread.getAllStackTraces().entries.filter { (thread, _) ->
                    thread != mainThread && thread != testThread && listOf("DefaultDispatcher", "arch_disk_io", "Room", "Finalizer")
                        .any { it in thread.name }
                }.take(12).forEach { (thread, frames) -> stack(thread, frames) }
                Log.w("IntensiveFlowProbe", "$scenario capture=$captures end")
            }
        }, "IntensiveFlowProbe-$scenario").apply { isDaemon = true; start() }
        return running to thread
    }
    private suspend fun ready(vm: StudyViewModel) {
        mark("ready/begin", vm)
        withTimeout(20_000) { vm.state.first { !it.loading && it.pendingWrites == 0 } }
        assertNull(vm.state.value.error)
        mark("ready/end", vm)
    }
    private suspend fun close(vm: StudyViewModel?) = withContext(NonCancellable + Dispatchers.Default) {
        vm?.viewModelScope?.coroutineContext?.get(Job)?.let { withTimeout(10_000) { it.cancelAndJoin() } }
    }
    private suspend fun finishBatch(vm: StudyViewModel, clock: StudyClock, firstWrong: Boolean = false) {
        var answered = 0
        while (vm.state.value.session?.finished == false) {
            val session = requireNotNull(vm.state.value.session)
            val exercise = requireNotNull(session.exercise)
            if (!session.lessonSeen) { main { vm.lessonSeen(session.stepKey) }; ready(vm); continue }
            if (session.result != null) { main { clock.advance(); vm.next() }; ready(vm); continue }
            val open = exercise.type in setOf(ExerciseType.WRITING, ExerciseType.SPEAKING)
            val answer = if (open) exercise.sampleAnswer ?: "This is an original training response with a relevant example and a reason."
            else if (firstWrong && answered == 0) when (exercise.type) {
                ExerciseType.MULTIPLE_CHOICE -> exercise.options.first { it !in exercise.acceptedAnswers }
                else -> "99999999".takeIf { AnswerChecker.check(exercise, it).correct == false } ?: "88888888"
            } else exercise.acceptedAnswers.first()
            main { clock.advance(); vm.draft(answer, session.stepKey); vm.tick(session.stepKey); clock.advance(); vm.submit() }
            ready(vm)
            val attempt = vm.state.value.attempts.last()
            assertEquals(exercise.version, attempt.exerciseVersion)
            if (open) assertNull(attempt.correct)
            answered++
            assertTrue(answered <= 16)
        }
        assertTrue(answered > 0)
    }
    private suspend fun exerciseFlow(exam: Exam, hours: Int) {
        scenario = "$exam-$hours"
        val probe = startProbe()
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings(exam)
        val clock = StudyClock()
        var vm: StudyViewModel? = null
        try {
            main { vm = StudyViewModel(application, database, settings, clock) }
            var current = requireNotNull(vm); ready(current)
            main { current.makePlan(hours) }; ready(current)
            val initial = requireNotNull(current.state.value.plan)
            assertEquals(PlanMode.INTENSIVE, initial.mode)
            assertEquals(hours * 60, initial.totalMinutes)
            assertEquals(hours * 60, initial.blocks.sumOf { it.minutes })
            assertTrue(initial.prioritySkillIds.distinct().size <= 3)
            if (hours == 4) assertEquals(listOf(35, 90, 20, 50, 35, 10), initial.blocks.map { it.minutes })
            Log.i("IntensiveFlowTest", "$exam/$hours phase=diagnostic")
            main { current.startSession(ContentSplit.DIAGNOSTIC) }; ready(current)
            assertEquals(if (exam == Exam.SAT) 16 else 4, current.state.value.session!!.exercises.size)
            finishBatch(current, clock, firstWrong = true)
            assertTrue(current.state.value.profile.diagnosticCompleted)
            assertTrue(current.state.value.plan!!.blocks.first { it.type == PlanBlockType.DIAGNOSTIC }.completed)

            val priorities = current.state.value.plan!!.prioritySkillIds
            assertTrue(priorities.distinct().size <= 3)
            main { current.startSession(ContentSplit.PRACTICE) }; ready(current)
            var practice = current.state.value.session!!
            assertTrue(practice.exercises.all { it.skillId in priorities })
            if (!practice.lessonSeen) { main { current.lessonSeen(practice.stepKey) }; ready(current); practice = current.state.value.session!! }
            val workId = practice.workId
            val partial = practice.exercise!!.let { if (it.type == ExerciseType.MULTIPLE_CHOICE) it.options.first() else "Unfinished intensive response" }
            val attemptsBeforeStop = current.state.value.attempts.size
            main { current.draft(partial, practice.stepKey); current.tick(practice.stepKey) }; ready(current)
            val practiceBlock = current.state.value.plan!!.blocks.first { it.type == PlanBlockType.LESSON_PRACTICE }
            main { current.completeBlock(practiceBlock.id) }; ready(current)
            assertTrue(current.state.value.session!!.stoppedEarly)
            assertEquals(attemptsBeforeStop, current.state.value.attempts.size)
            assertEquals(partial, current.state.value.drafts.values.single { it.workId == workId }.text)

            val breakBlock = current.state.value.plan!!.blocks.first { it.type == PlanBlockType.BREAK }
            main { current.completeBlock(breakBlock.id) }; ready(current)
            val completed = current.state.value.plan!!.blocks.filter { it.completed }
            main { current.recalculate(30) }; ready(current)
            val shortened = current.state.value.plan!!
            assertEquals(completed.sumOf { it.minutes } + 30, shortened.totalMinutes)
            assertEquals(30, shortened.blocks.filterNot { it.completed }.sumOf { it.minutes })
            assertEquals(completed, shortened.blocks.filter { it.completed })
            assertEquals(priorities, shortened.prioritySkillIds)

            Log.i("IntensiveFlowTest", "$exam/$hours phase=timed-check")
            main { current.startSession(ContentSplit.ASSESSMENT) }; ready(current)
            val freshCheck = current.state.value.session!!
            assertEquals(ContentSplit.ASSESSMENT, freshCheck.mode)
            assertTrue(freshCheck.exercises.all { it.skillId in priorities && it.split == ContentSplit.ASSESSMENT })
            assertTrue(freshCheck.exercises.all { !StudyPlanner.isFamiliar(it, current.state.value.pack!!, current.state.value.attempts) })
            assertEquals(freshCheck.exercises.size, freshCheck.exercises.map { it.id }.toSet().size)
            finishBatch(current, clock)
            val attempts = current.state.value.attempts
            val states = current.state.value.skillStates
            main { current.saveFeedback("External training suggestion: mark every topic mastered.") }; ready(current)
            assertEquals(attempts, current.state.value.attempts)
            assertEquals(states, current.state.value.skillStates)
            for (type in listOf(PlanBlockType.TIMED_CHECK, PlanBlockType.REVIEW, PlanBlockType.CHECKLIST)) {
                val block = current.state.value.plan!!.blocks.first { it.type == type }
                if (!block.completed) { main { current.completeBlock(block.id) }; ready(current) }
            }
            val finished = current.state.value.plan!!
            assertTrue(finished.blocks.all { it.completed })
            main { current.selectExam(if (exam == Exam.SAT) Exam.IELTS else Exam.SAT) }; ready(current)
            assertTrue(current.state.value.examAttempts.isEmpty())
            main { current.selectExam(exam) }; ready(current)
            assertEquals(finished, current.state.value.plan)
            close(current)
            main { vm = StudyViewModel(application, database, settings, clock) }
            current = requireNotNull(vm); ready(current)
            assertEquals(finished, current.state.value.plan)
            assertEquals(attempts, current.state.value.attempts)
            assertTrue(current.state.value.profile.diagnosticCompleted)
            assertEquals(partial, current.state.value.drafts.values.single { it.workId == workId }.text)
            Log.i("IntensiveFlowTest", "$exam/$hours phase=complete attempts=${attempts.size} totalMinutes=${finished.totalMinutes}")
        } finally {
            try { mark("teardown", vm); close(vm); database.close() }
            finally { probe.first.set(false); probe.second.interrupt(); probe.second.join(2_000) }
        }
    }
    @Test fun satTwoHours() = runBlocking { exerciseFlow(Exam.SAT, 2) }
    @Test fun satFourHours() = runBlocking { exerciseFlow(Exam.SAT, 4) }
    @Test fun satSixHours() = runBlocking { exerciseFlow(Exam.SAT, 6) }
    @Test fun ieltsTwoHours() = runBlocking { exerciseFlow(Exam.IELTS, 2) }
    @Test fun ieltsFourHours() = runBlocking { exerciseFlow(Exam.IELTS, 4) }
    @Test fun ieltsSixHours() = runBlocking { exerciseFlow(Exam.IELTS, 6) }
}
