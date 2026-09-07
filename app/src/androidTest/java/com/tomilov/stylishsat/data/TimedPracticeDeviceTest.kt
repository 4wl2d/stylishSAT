package com.tomilov.stylishsat.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.StudyDraft
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real VM/Room persistence with a short fixture limit; no production records or models. */
@RunWith(AndroidJUnit4::class)
class TimedPracticeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private class MemorySettings : SettingsStore {
        override val flow = MutableStateFlow(Settings(Exam.SAT, Language.EN))
        override suspend fun exam(value: Exam) { flow.value = flow.value.copy(exam = value) }
        override suspend fun language(value: Language) { flow.value = flow.value.copy(language = value) }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun ready(vm: StudyViewModel) {
        withTimeout(15_000) { vm.state.first { !it.loading && it.pendingWrites == 0 } }
        assertNull(vm.state.value.error)
    }
    private suspend fun close(vm: StudyViewModel?) = withContext(NonCancellable + Dispatchers.Default) {
        vm?.viewModelScope?.coroutineContext?.get(Job)?.let { withTimeout(10_000) { it.cancelAndJoin() } }
    }
    private suspend fun fixture(db: StudyDatabase, confident: Boolean = true): ContentPack {
        val info = bundledPackInfo(application)
        val skill = Skill("timed-algebra", Exam.SAT, LocalizedText("Algebra", "Алгебра"), "Math")
        val pack = ContentPack(id = info.contentId, version = info.contentVersion + 1, title = LocalizedText("Timer fixture", "Тест таймера"),
            skills = listOf(skill), lessons = listOf(Lesson("timed-rule", skill.id, LocalizedText("Rule", "Правило"),
                LocalizedText("Multiply both sides equally.", "Умножайте обе части одинаково."), LocalizedText("x/2=2 gives x=4.", "Из x/2=2 следует x=4."), 1)),
            exercises = ContentSplit.entries.flatMap { split -> (1..4).map { index ->
                val id = "timed-${split.name}-$index"
                Exercise(id, exam = Exam.SAT, skillId = skill.id, split = split, familyId = id,
                    type = ExerciseType.NUMERIC, difficulty = 2, prompt = "If x / $index = 2, find x.",
                    acceptedAnswers = listOf((index * 2).toString()), expectedSeconds = 3, author = "isolated timer fixture",
                    typicalErrors = listOf(LocalizedText("Use x = 2 × the divisor.", "Используйте x = 2 × делитель.")),
                    hints = listOf(LocalizedText("Multiply both sides.", "Умножьте обе части.")))
            } })
        ContentPackCodec.validate(pack)
        db.dao().insertPack(StoredPack(pack.id, pack.version, storageJson.encodeToString(pack)))
        if (confident) (1..6).forEach { index ->
            val prior = Attempt("prior-$index", "prior-exercise-$index", 1, Exam.SAT, skill.id, "2", true,
                1_700_000_000_000L + index, elapsedSeconds = 1, difficulty = if (index <= 4) 1 else 2,
                familyId = "prior-family-$index", sourceId = "prior-source-$index", expectedSeconds = 3)
            db.dao().put(StoredRecord("attempt:${prior.id}", "attempt", Exam.SAT.name, storageJson.encodeToString(prior)))
        }
        return pack
    }

    @Test fun expiryFreezesLatestTextAndSurvivesPauseAndVmRestoration(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(db)
            main { vm = StudyViewModel(application, db, settings) }
            var current = vm!!; ready(current)
            main { current.startSession(ContentSplit.PRACTICE) }; ready(current)
            val initial = current.state.value.session!!
            assertTrue(initial.lessonSeen)
            assertEquals(3, initial.timeLimitSeconds)
            val key = initial.stepKey
            main {
                current.draft("1", key)
                current.tick(key); current.tick(key)
                // Simulates local editor text not yet delivered by snapshotFlow.
                current.tick(key, "24", initial.draftRevision)
                current.draft("999", key); current.applyTranscript("stale", key); current.hint(); current.tick(key)
            }
            ready(current)
            val locked = current.state.value.session!!
            assertTrue(locked.answerLockedByTimeLimit)
            assertEquals("24", locked.draft)
            assertEquals(3, locked.activeSeconds)
            assertEquals(0, locked.hintsUsed)
            assertNull(locked.result)
            assertEquals(6, current.state.value.attempts.size)
            main { assertTrue(current.finishForNow()) }; ready(current)
            val draft = current.state.value.drafts.values.single { it.workId == initial.workId }
            assertEquals(3, draft.timeLimitSeconds)
            assertEquals(3, draft.elapsedSeconds)
            close(current)
            main { vm = StudyViewModel(application, db, settings) }
            current = vm!!; ready(current)
            main { assertTrue(current.resumeDraft(draft.key)) }; ready(current)
            assertTrue(current.state.value.session!!.answerLockedByTimeLimit)
            assertEquals("24", current.state.value.session!!.draft)
            main { current.submit() }; ready(current)
            val submitted = current.state.value.attempts.last()
            assertEquals("24", submitted.answer)
            assertEquals(3, submitted.elapsedSeconds)
            assertEquals(3, submitted.timeLimitSeconds)
            assertFalse(submitted.continuedWithoutTimeLimit)
        } finally { close(vm); db.close() }
    }

    @Test fun explicitUntimedContinuationIsRecordedAndStaleActionsCannotUnlockNextItem(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(db)
            main { vm = StudyViewModel(application, db, settings) }
            var current = vm!!; ready(current)
            main { current.startSession(ContentSplit.PRACTICE) }; ready(current)
            val first = current.state.value.session!!
            main {
                repeat(2) { current.tick(first.stepKey) }
                current.applyTranscript("new revision", first.stepKey)
                current.tick(first.stepKey, "stale editor text", first.draftRevision)
                current.continueWithoutTimeLimit("stale-step")
            }
            assertTrue(current.state.value.session!!.answerLockedByTimeLimit)
            assertEquals("new revision", current.state.value.session!!.draft)
            main {
                current.continueWithoutTimeLimit(first.stepKey)
                current.draft(first.exercise!!.acceptedAnswers.first(), first.stepKey)
                current.tick(first.stepKey)
            }
            ready(current)
            close(current)
            main { vm = StudyViewModel(application, db, settings) }
            current = vm!!; ready(current)
            assertTrue(current.state.value.session!!.continuedWithoutTimeLimit)
            assertFalse(current.state.value.session!!.answerLockedByTimeLimit)
            main { current.submit(); current.next() }; ready(current)
            val result = current.state.value.attempts.last()
            assertEquals(true, result.correct)
            assertEquals(4, result.elapsedSeconds)
            assertEquals(3, result.timeLimitSeconds)
            assertTrue(result.continuedWithoutTimeLimit)
            val next = current.state.value.session!!
            assertEquals(3, next.timeLimitSeconds)
            assertFalse(next.continuedWithoutTimeLimit)
            main { repeat(3) { current.tick(next.stepKey) }; current.continueWithoutTimeLimit(first.stepKey); current.draft("stale", first.stepKey) }
            ready(current)
            assertTrue(current.state.value.session!!.answerLockedByTimeLimit)
            assertEquals("", current.state.value.session!!.draft)
        } finally { close(vm); db.close() }
    }

    @Test fun weakPracticeKeepsLessonAndOldDraftDoesNotGainARetroactiveDeadline(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(db, confident = false)
            main { vm = StudyViewModel(application, db, MemorySettings()) }
            var current = vm!!; ready(current)
            main { current.startSession(ContentSplit.PRACTICE) }; ready(current)
            assertFalse(current.state.value.session!!.lessonSeen)
            assertNull(current.state.value.session!!.timeLimitSeconds)
            val initial = current.state.value.session!!
            main {
                current.lessonSeen(initial.stepKey)
                current.applyTranscript("new untimed revision", initial.stepKey)
                current.tick(initial.stepKey, "stale editor text", initial.draftRevision)
            }
            assertEquals("new untimed revision", current.state.value.session!!.draft)
            main { current.finishForNow() }; ready(current)
            close(current)
            val exercise = pack.exercises.first { it.split == ContentSplit.ASSESSMENT }
            val draft = StudyDraft(Exam.SAT, exercise.id, exercise.version, "2", elapsedSeconds = 10)
            db.dao().put(StoredRecord("draft:${draft.key}", "draft", Exam.SAT.name, storageJson.encodeToString(draft)))
            main { vm = StudyViewModel(application, db, MemorySettings()) }
            current = vm!!; ready(current)
            main { assertTrue(current.resumeDraft(draft.key)) }; ready(current)
            assertNull(current.state.value.session!!.timeLimitSeconds)
            assertFalse(current.state.value.session!!.answerLockedByTimeLimit)
            assertEquals(10, current.state.value.session!!.previousWorkSeconds)
        } finally { close(vm); db.close() }
    }

    @Test fun reviewGuidanceCountsOnceAndSurvivesPauseAndRestoration(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(db)
            val failedItem = pack.exercises.first { it.split == ContentSplit.PRACTICE }
            val failed = Attempt("failure", failedItem.id, failedItem.version, Exam.SAT, failedItem.skillId, "999", false,
                1_700_000_000_007L, elapsedSeconds = 1, difficulty = 2, errorType = "KEY_MISMATCH",
                familyId = failedItem.familyId, sourceId = failedItem.sourceId, expectedSeconds = 3)
            db.dao().put(StoredRecord("attempt:${failed.id}", "attempt", Exam.SAT.name, storageJson.encodeToString(failed)))
            main { vm = StudyViewModel(application, db, settings) }
            var current = vm!!; ready(current)
            val independentBefore = current.state.value.skillStates.single().independentCount
            main { current.startSession(ContentSplit.PRACTICE); current.lessonSeen() }; ready(current)
            val initial = current.state.value.session!!
            assertFalse(initial.reviewGuidanceViewed)
            assertFalse(StudyPlanner.isFamiliar(initial.exercise!!, pack, current.state.value.attempts))
            assertFalse(StudyPlanner.practiceChecks(pack, initial.exercise!!, current.state.value.attempts).isEmpty())
            main { current.reviewGuidance("stale-step") }
            assertFalse(current.state.value.session!!.reviewGuidanceViewed)
            main {
                current.reviewGuidance(initial.stepKey); current.reviewGuidance(initial.stepKey)
                current.hint(); current.draft(initial.exercise!!.acceptedAnswers.first(), initial.stepKey)
                assertTrue(current.finishForNow())
            }
            ready(current)
            val saved = current.state.value.drafts.values.single { it.workId == initial.workId }
            assertTrue(saved.reviewGuidanceViewed)
            assertEquals(1, saved.hintsUsed)
            assertFalse(saved.submitted)
            assertEquals(7, current.state.value.attempts.size)
            close(current)
            main { vm = StudyViewModel(application, db, settings) }
            current = vm!!; ready(current)
            main { assertTrue(current.resumeDraft(saved.key)) }; ready(current)
            val restored = current.state.value.session!!
            assertTrue(restored.reviewGuidanceViewed)
            main { current.reviewGuidance(restored.stepKey); current.submit() }; ready(current)
            val attempt = current.state.value.attempts.last()
            assertEquals(true, attempt.correct)
            assertEquals(2, attempt.hintsUsed)
            assertFalse(attempt.independent)
            assertEquals(independentBefore, current.state.value.skillStates.single().independentCount)
            main { current.next(); current.startSession(ContentSplit.PRACTICE); current.reviewGuidance(restored.stepKey) }; ready(current)
            assertFalse(current.state.value.session!!.reviewGuidanceViewed)
            assertFalse(StudyPlanner.practiceChecks(pack, current.state.value.session!!.exercise!!, current.state.value.attempts).isEmpty())
        } finally { close(vm); db.close() }
    }
}
