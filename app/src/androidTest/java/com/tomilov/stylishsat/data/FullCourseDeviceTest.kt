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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Exercises production scheduling/persistence with the actual bank; it is not a learner pilot. */
@RunWith(AndroidJUnit4::class)
class FullCourseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private val lastTraceAt = AtomicLong(SystemClock.elapsedRealtime())
    private val lastTracePhase = AtomicReference("not-started")

    private class MemorySettings(exam: Exam) : SettingsStore {
        override val flow = MutableStateFlow(Settings(exam, Language.EN))
        override suspend fun exam(value: Exam) { flow.value = flow.value.copy(exam = value) }
        override suspend fun language(value: Language) { flow.value = flow.value.copy(language = value) }
    }

    private class StudyClock private constructor(
        private val value: AtomicReference<Instant>, private val studyZone: ZoneId,
    ) : Clock() {
        constructor(date: LocalDate) : this(AtomicReference(date.atTime(9, 0).toInstant(ZoneOffset.UTC)), ZoneOffset.UTC)
        override fun instant(): Instant = value.get()
        override fun getZone(): ZoneId = studyZone
        override fun withZone(zone: ZoneId): Clock = StudyClock(value, zone)
        fun day(date: LocalDate) { value.set(date.atTime(9, 0).toInstant(ZoneOffset.UTC)) }
        fun advance() { value.updateAndGet { it.plusSeconds(1) } }
    }

    private data class PendingDraft(val text: String, val seconds: Int)

    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun trace(vm: StudyViewModel, phase: String, detail: String = "") {
        lastTracePhase.set(phase)
        lastTraceAt.set(SystemClock.elapsedRealtime())
        val state = vm.state.value
        val session = state.session
        Log.i("FullCourseTest", "exam=${state.exam} phase=$phase elapsedMs=${SystemClock.elapsedRealtime()} " +
            "day=${session?.courseDay} step=${session?.index}/${session?.stepCount} kind=${session?.activity?.kind} " +
            "work=${session?.workId} loading=${state.loading} pendingWrites=${state.pendingWrites} " +
            "attempts=${state.attempts.size} finished=${session?.finished} draftChars=${session?.draft?.length} $detail")
    }
    /** A blocked runOnMainSync prevents coroutine readiness timeouts from running.
     * Capture that boundary from an independent diagnostic thread, at most three times. */
    private fun watchdog(exam: Exam): Pair<AtomicBoolean, Thread> {
        val running = AtomicBoolean(true)
        val testThread = Thread.currentThread()
        lastTraceAt.set(SystemClock.elapsedRealtime())
        lastTracePhase.set("$exam/initializing")
        val thread = Thread({
            var capturedProgress = -1L
            var captures = 0
            while (running.get()) {
                try { Thread.sleep(1_000) } catch (_: InterruptedException) { break }
                val progress = lastTraceAt.get()
                val quiet = SystemClock.elapsedRealtime() - progress
                if (quiet < 10_000 || capturedProgress == progress || captures >= 3) continue
                capturedProgress = progress
                captures++
                Log.w("FullCourseWatchdog", "exam=$exam capture=$captures quietMs=$quiet phase=${lastTracePhase.get()} begin")
                fun stack(thread: Thread, frames: Array<StackTraceElement>) {
                    Log.w("FullCourseWatchdog", "thread=${thread.name} id=${thread.id} state=${thread.state}")
                    frames.take(40).forEach { Log.w("FullCourseWatchdog", "  at $it") }
                }
                val mainThread = Looper.getMainLooper().thread
                stack(mainThread, mainThread.stackTrace)
                stack(testThread, testThread.stackTrace)
                Thread.getAllStackTraces().entries.filter { (thread, _) ->
                    thread != mainThread && thread != testThread && listOf("DefaultDispatcher", "arch_disk_io", "Room",
                        "HeapTask", "Finalizer", "ReferenceQueue").any { it in thread.name }
                }.take(10).forEach { (thread, frames) -> stack(thread, frames) }
                Log.w("FullCourseWatchdog", "exam=$exam capture=$captures end")
            }
        }, "FullCourseWatchdog-$exam").apply { isDaemon = true; start() }
        return running to thread
    }
    private suspend fun stopWatchdog(watchdog: Pair<AtomicBoolean, Thread>) {
        watchdog.first.set(false)
        watchdog.second.interrupt()
        withContext(NonCancellable + Dispatchers.Default) {
            watchdog.second.join(2_000)
            Log.i("FullCourseWatchdog", "stopped=${!watchdog.second.isAlive} thread=${watchdog.second.name}")
        }
    }
    private suspend fun ready(vm: StudyViewModel, phase: String = "await-state") {
        val started = SystemClock.elapsedRealtime()
        trace(vm, "$phase/begin")
        try {
            withTimeout(20_000) { vm.state.first { !it.loading && it.pendingWrites == 0 } }
            assertNull(vm.state.value.error)
            trace(vm, "$phase/ready", "waitMs=${SystemClock.elapsedRealtime() - started}")
        } catch (error: Throwable) {
            trace(vm, "$phase/failed", "waitMs=${SystemClock.elapsedRealtime() - started} error=${error.javaClass.simpleName}")
            throw error
        }
    }
    private suspend fun shutdown(vm: StudyViewModel?, phase: String, database: StudyDatabase? = null) {
        withContext(NonCancellable + Dispatchers.Default) {
            if (vm != null) {
                val job = requireNotNull(vm.viewModelScope.coroutineContext[Job])
                val started = SystemClock.elapsedRealtime()
                trace(vm, "$phase/cancel-begin", "children=${job.children.count()}")
                try {
                    withTimeout(10_000) { job.cancelAndJoin() }
                    trace(vm, "$phase/cancel-joined", "joinMs=${SystemClock.elapsedRealtime() - started} completed=${job.isCompleted}")
                } catch (error: Throwable) {
                    trace(vm, "$phase/cancel-failed", "joinMs=${SystemClock.elapsedRealtime() - started} error=${error.javaClass.simpleName}")
                    throw error
                }
            }
            if (database != null) {
                Log.i("FullCourseTest", "phase=$phase/database-close-begin elapsedMs=${SystemClock.elapsedRealtime()}")
                database.close()
                Log.i("FullCourseTest", "phase=$phase/database-closed elapsedMs=${SystemClock.elapsedRealtime()}")
            }
        }
    }

    @Test fun satCourseCompletesAllTwentyEightDaysWithFreshChecks(): Unit = runBlocking {
        withTimeout(240_000) { completeCourse(Exam.SAT) }
    }

    @Test fun ieltsCourseCompletesAllTwentyEightDaysWithUnscoredPartialWriting(): Unit = runBlocking {
        withTimeout(240_000) { completeCourse(Exam.IELTS) }
    }

    private suspend fun completeCourse(exam: Exam) {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings(exam)
        val start = LocalDate.of(2026, 10, 1)
        val clock = StudyClock(start)
        val pending = mutableMapOf<String, PendingDraft>()
        val submittedWork = mutableSetOf<String>()
        val completedDays = mutableListOf<PlanDay>()
        var writingCheckpoints = 0
        var vm: StudyViewModel? = null
        val watchdog = watchdog(exam)
        try {
            main { vm = StudyViewModel(application, database, settings, clock) }
            var current = vm!!; ready(current, "initial-load")
            val pack = current.state.value.pack!!
            assertEquals(810, pack.exercises.size)
            assertEquals(48, pack.lessons.size)
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 15)); current.makePlan() }
            ready(current, "initial-plan")
            val planId = current.state.value.plan!!.id
            assertEquals(pack.version, current.state.value.plan!!.contentVersion)
            assertEquals(StudyPlanner.CURRENT_PLANNER_VERSION, current.state.value.plan!!.plannerVersion)

            for (dayNumber in 1..28) {
                trace(current, "day-$dayNumber/begin", "pendingWork=${pending.size} logicalDate=${start.plusDays(dayNumber - 1L)}")
                main { clock.day(start.plusDays(dayNumber - 1L)) }
                val before = current.state.value.plan!!
                assertEquals("$exam day $dayNumber must not silently extend an unchanged 15-minute route", 28, before.days.size)
                assertEquals(completedDays, before.days.take(dayNumber - 1))
                val plannedDay = before.days[dayNumber - 1]
                assertFalse("$exam day $dayNumber must be reachable", plannedDay.contentExhausted)
                assertTrue(plannedDay.activities.sumOf { it.minutes } <= 15)
                main { current.startPlannedDay(dayNumber) }; ready(current, "day-$dayNumber/start-session")
                assertEquals(dayNumber, current.state.value.session!!.courseDay)
                assertFalse(current.state.value.session!!.finished)

                var steps = 0
                while (!current.state.value.session!!.finished) {
                    assertTrue("$exam day $dayNumber failed to advance its session", ++steps <= 64)
                    val session = current.state.value.session!!
                    val activity = requireNotNull(session.activity)
                    val exercise = requireNotNull(session.exercise)
                    val beforeAttempts = current.state.value.attempts.size
                    val stepPhase = "day-$dayNumber/step-${session.index}"
                    trace(current, "$stepPhase/action", "remainingMinutes=${activity.remainingMinutes} result=${session.result?.status}")
                    if (activity.isLesson) {
                        main {
                            clock.advance()
                            if (activity.remainingMinutes > 0) current.checkpoint(session.stepKey)
                            else current.lessonSeen(session.stepKey)
                        }
                        ready(current, "$stepPhase/lesson")
                        assertEquals("a lesson/review cannot create an attempt", beforeAttempts, current.state.value.attempts.size)
                    } else if (session.result != null) {
                        main { clock.advance(); current.next() }; ready(current, "$stepPhase/next")
                    } else {
                        pending[session.workId]?.let { saved ->
                            assertEquals("$exam restored text for ${session.workId}", saved.text, session.draft)
                            assertEquals(saved.seconds, session.previousWorkSeconds)
                            assertEquals(0, session.activeSeconds)
                        }
                        val open = exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING
                        val partial = activity.remainingMinutes > 0
                        val answer = when {
                            partial -> session.draft.ifBlank { "Unfinished original response for ${exercise.id}." } + " Continued on study day $dayNumber."
                            open -> exercise.sampleAnswer ?: "A complete original response with a relevant explanation and a concrete example."
                            else -> exercise.acceptedAnswers.first()
                        }
                        var checkpointDraft: PendingDraft? = null
                        main {
                            clock.advance(); current.draft(answer, session.stepKey)
                            repeat(2) { clock.advance(); current.tick() }
                            val updated = current.state.value.session!!
                            checkpointDraft = PendingDraft(updated.draft, updated.previousWorkSeconds + updated.activeSeconds)
                            clock.advance()
                            if (partial) current.checkpoint(session.stepKey) else current.submit()
                        }
                        ready(current, "$stepPhase/${if (partial) "checkpoint" else "submit"}")
                        if (partial) {
                            assertEquals("a partial work slice cannot be graded", beforeAttempts, current.state.value.attempts.size)
                            pending[session.workId] = requireNotNull(checkpointDraft)
                            if (exercise.type == ExerciseType.WRITING) writingCheckpoints++
                        } else {
                            assertEquals(beforeAttempts + 1, current.state.value.attempts.size)
                            val attempt = current.state.value.attempts.last()
                            assertTrue("one attempt per work identity", submittedWork.add(session.workId))
                            assertEquals(session.workId, attempt.workId)
                            assertEquals(exercise.version, attempt.exerciseVersion)
                            assertEquals(checkpointDraft!!.seconds, attempt.elapsedSeconds)
                            assertEquals(start.plusDays(dayNumber - 1L).toEpochDay(), attempt.localDateEpochDay)
                            if (open) assertNull(attempt.correct) else assertEquals(true, attempt.correct)
                            if (activity.kind == ActivityKind.ASSESSMENT) {
                                assertEquals(ContentSplit.ASSESSMENT, attempt.split)
                                assertFalse("final-check source must be unseen before submission", StudyPlanner.isFamiliar(exercise, pack, current.state.value.attempts.dropLast(1)))
                                assertFalse(attempt.isRepeat)
                            }
                            pending.remove(session.workId)
                        }
                    }
                }

                val finished = current.state.value.session!!
                completedDays += requireNotNull(finished.daySnapshot)
                assertEquals((1..dayNumber).toList(), current.state.value.courseProgress[exam]!!.completedDays)
                assertEquals(completedDays, current.state.value.plan!!.days.take(dayNumber))
                assertEquals(28, current.state.value.plan!!.days.size)
                trace(current, "day-$dayNumber/completed", "completedDays=${completedDays.size} pendingWork=${pending.size}")
                if (dayNumber == 14) {
                    shutdown(current, "mid-course-restart")
                    main { vm = StudyViewModel(application, database, settings, clock) }
                    current = vm!!; ready(current, "mid-course-restart/load")
                    assertEquals((1..14).toList(), current.state.value.courseProgress[exam]!!.completedDays)
                    assertEquals(completedDays, current.state.value.plan!!.days.take(14))
                }
            }

            assertTrue("all partial work is submitted before the course ends", pending.isEmpty())
            if (exam == Exam.IELTS) assertTrue("the real Writing bank must exercise continuation", writingCheckpoints > 0)
            val attempts = current.state.value.examAttempts
            if (exam == Exam.IELTS) {
                val writing = completedDays.flatMap { it.activities }
                    .filter { it.kind == ActivityKind.PRACTICE && it.remainingMinutes == 0 }
                    .mapNotNull { it.exerciseSnapshot }.filter { it.type == ExerciseType.WRITING }
                assertEquals("the real course must finish both Writing formats", setOf(false, true),
                    writing.map { it.chart != null }.toSet())
                val openSkillIds = pack.exercises.filter { it.type == ExerciseType.WRITING || it.type == ExerciseType.SPEAKING }
                    .map { it.skillId }.toSet()
                StudyPlanner.statesFromAttempts(pack, attempts).filter { it.skillId in openSkillIds }.forEach {
                    assertEquals("open responses never become automatic level evidence", SkillState(it.skillId), it)
                }
            }
            val checks = attempts.filter { it.split == ContentSplit.ASSESSMENT }
            val expectedSkills = pack.skills.filter { it.exam == exam }.map { it.id }.toSet()
            assertEquals(expectedSkills, checks.map { it.skillId }.toSet())
            assertEquals(expectedSkills.size, checks.size)
            assertTrue(checks.groupBy { it.skillId }.values.all { it.size == 1 })
            assertTrue("clock advances between submissions as well as study days",
                attempts.sortedBy { it.timestampEpochMillis }.zipWithNext().all { (a, b) -> a.timestampEpochMillis < b.timestampEpochMillis })
            val finalPlan = current.state.value.plan!!
            shutdown(current, "completed-course-restart")
            main { vm = StudyViewModel(application, database, settings, clock) }
            current = vm!!; ready(current, "completed-course-restart/load")
            assertEquals(planId, current.state.value.plan!!.id)
            assertEquals(finalPlan, current.state.value.plan)
            assertEquals((1..28).toList(), current.state.value.courseProgress[exam]!!.completedDays)
            assertEquals(attempts.associateBy { it.id }, current.state.value.examAttempts.associateBy { it.id })
            assertTrue(current.state.value.session!!.finished)
        } finally {
            try { shutdown(vm, "teardown", database) } finally { stopWatchdog(watchdog) }
        }
    }
}
