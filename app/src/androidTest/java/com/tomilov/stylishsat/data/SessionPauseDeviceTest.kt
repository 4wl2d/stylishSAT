package com.tomilov.stylishsat.data

import android.app.Application
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.StudyDraft
import com.tomilov.stylishsat.StudySession
import com.tomilov.stylishsat.StudyViewModel
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Isolated Room fixtures; no production learner records, models or microphone use. */
@RunWith(AndroidJUnit4::class)
class SessionPauseDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    private class MemorySettings : SettingsStore {
        override val flow = MutableStateFlow(Settings(Exam.IELTS, Language.EN))
        override suspend fun exam(value: Exam) { flow.value = flow.value.copy(exam = value) }
        override suspend fun language(value: Language) { flow.value = flow.value.copy(language = value) }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private suspend fun ready(vm: StudyViewModel) {
        withTimeout(15_000) { vm.state.first { !it.loading && it.pendingWrites == 0 } }
        assertNull(vm.state.value.error)
    }
    private suspend fun shutdown(vm: StudyViewModel?, database: StudyDatabase? = null) {
        withContext(NonCancellable + Dispatchers.Default) {
            vm?.viewModelScope?.coroutineContext?.get(Job)?.let { withTimeout(15_000) { it.cancelAndJoin() } }
            database?.close()
        }
    }
    private suspend fun fixture(database: StudyDatabase): ContentPack {
        val info = bundledPackInfo(application)
        val skill = Skill("writing", Exam.IELTS, LocalizedText("Writing", "Письмо"), "Writing")
        val otherSkill = Skill("z-reading", Exam.IELTS, LocalizedText("Reading", "Чтение"), "Reading")
        fun item(id: String, split: ContentSplit) = Exercise(id, exam = Exam.IELTS, skillId = skill.id,
            split = split, familyId = id, type = ExerciseType.WRITING, prompt = "Explain an original proposal with supporting reasons.",
            expectedSeconds = 2400, author = "device-test fixture", criteria = listOf(LocalizedText("Support the proposal", "Обоснуйте предложение")),
            hints = listOf(LocalizedText("Outline", "План"), LocalizedText("Add evidence", "Добавьте обоснование")))
        val pack = ContentPack(id = info.contentId, version = info.contentVersion + 1,
            title = LocalizedText("Pause fixture", "Тест паузы"), skills = listOf(skill, otherSkill),
            lessons = listOf(Lesson("writing-rule", skill.id, LocalizedText("Rule", "Правило"),
                LocalizedText("Plan and support a response.", "Спланируйте и обоснуйте ответ."), LocalizedText("A worked example.", "Пример."), 8),
                Lesson("reading-rule", otherSkill.id, LocalizedText("Read", "Читайте"), LocalizedText("Find evidence.", "Найдите обоснование."),
                    LocalizedText("An example.", "Пример."), 2)),
            exercises = (1..3).map { item("writing-p$it", ContentSplit.PRACTICE) } +
                (1..2).map { item("writing-a$it", ContentSplit.ASSESSMENT) } +
                item("writing-d", ContentSplit.DIAGNOSTIC) + ContentSplit.entries.map { split ->
                    Exercise("reading-${split.name}", exam = Exam.IELTS, skillId = otherSkill.id, split = split,
                        familyId = "reading-${split.name}", type = ExerciseType.SHORT_ANSWER, prompt = "Name the stated colour.",
                        passage = "The ${split.name.lowercase()} folder is blue.", acceptedAnswers = listOf("blue"),
                        expectedSeconds = 60, author = "device-test fixture")
                })
        ContentPackCodec.validate(pack)
        database.dao().insertPack(StoredPack(pack.id, pack.version, storageJson.encodeToString(pack)))
        return pack
    }

    @Test fun partialManualWritingCanMoveToTimedCheckAndResumeArchivedVersion(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val originalPack = fixture(db)
            main { vm = StudyViewModel(application, db, settings) }
            var current = vm!!; ready(current)
            main { current.makePlan(2); current.startSession(ContentSplit.PRACTICE, "writing"); current.lessonSeen() }
            ready(current)
            main { current.draft("A finished first response."); current.submit(); current.next(); current.lessonSeen() }
            ready(current)
            val original = current.state.value.session!!.exercise!!
            val workId = current.state.value.session!!.workId
            val text = "The second response is unfinished; these exact words and all recordings must survive."
            main {
                current.draft(text); current.hint(); current.hint()
                current.attachRecording("/fixture/first.wav"); current.attachRecording("/fixture/second.wav")
                repeat(5) { current.tick() }
                current.completeBlock(current.state.value.plan!!.blocks.single { it.type == PlanBlockType.LESSON_PRACTICE }.id)
            }
            ready(current)
            val paused = current.state.value.session!!
            assertTrue(paused.finished && paused.stoppedEarly)
            assertEquals(1, current.state.value.attempts.size)
            val saved = current.state.value.drafts.values.single { it.workId == workId }
            assertFalse(saved.submitted)
            assertEquals(text, saved.text)
            assertTrue(current.state.value.plan!!.blocks.single { it.type == PlanBlockType.LESSON_PRACTICE }.completed)
            main {
                current.startSession(ContentSplit.ASSESSMENT)
                current.draft("A separate timed-check response."); current.submit(); current.next()
                assertTrue(current.finishForNow())
            }
            ready(current)
            assertEquals(2, current.state.value.attempts.size)
            assertEquals(ContentSplit.ASSESSMENT, current.state.value.session!!.mode)
            val revised = original.copy(version = original.version + 1, prompt = "A different prompt introduced by a content update.")
            current.contentRepository.importPack(ContentPackCodec.encode(originalPack.copy(version = originalPack.version + 1,
                exercises = originalPack.exercises.map { if (it.id == original.id) revised else it })))
            shutdown(current)
            main { vm = StudyViewModel(application, db, settings) }
            current = vm!!; ready(current)
            assertEquals(revised, current.state.value.pack!!.exercises.single { it.id == original.id })
            main {
                assertTrue(current.resumeDraft(saved.key)); assertTrue(current.state.value.loading)
                current.startSession(ContentSplit.ASSESSMENT); current.selectExam(Exam.SAT)
            }
            ready(current)
            val restored = current.state.value.session!!
            assertEquals(Exam.IELTS, current.state.value.exam)
            assertEquals(original, restored.exercise)
            assertEquals(workId, restored.workId)
            assertEquals(text, restored.draft)
            assertEquals(5, restored.previousWorkSeconds)
            assertEquals(2, restored.hintsUsed)
            assertEquals(listOf("/fixture/first.wav", "/fixture/second.wav"), restored.recordingPaths)
            assertEquals("/fixture/second.wav", restored.recordingPath)
            assertTrue(restored.resumingDraft && restored.lessonSeen)
            assertFalse(restored.stoppedEarly)
            assertEquals(2, current.state.value.attempts.size)
            main { repeat(2) { current.tick() }; current.submit(); current.next() }
            ready(current)
            val attempt = current.state.value.attempts.single { it.workId == workId }
            assertEquals(original.version, attempt.exerciseVersion)
            assertEquals(7, attempt.elapsedSeconds)
            assertEquals(text, attempt.answer)
            assertEquals(restored.recordingPaths, attempt.recordingPaths)
            assertEquals(3, current.state.value.attempts.size)
        } finally { shutdown(vm, db) }
    }

    @Test fun courseStopCarriesOriginalUngradedWorkAndDropsOnlyUnseenActivities(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(db)
            main { vm = StudyViewModel(application, db, settings) }
            var current = vm!!; ready(current)
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 60)); current.makePlan(); current.startPlannedDay(1); current.lessonSeen() }
            ready(current)
            val before = current.state.value.session!!
            val original = before.exercise!!
            val work = before.activity!!
            assertEquals(ActivityKind.PRACTICE, work.kind)
            assertEquals(0, work.remainingMinutes)
            assertTrue(before.index + 1 < before.activities.size)
            main {
                current.draft("A partial course response."); current.hint(); current.attachRecording("/fixture/course.wav")
                repeat(4) { current.tick() }; assertTrue(current.finishForNow())
            }
            ready(current)
            assertTrue(current.state.value.attempts.isEmpty())
            val frozen = current.state.value.plan!!.days.first()
            assertEquals(before.index + 1, frozen.activities.size)
            assertEquals(work.workId, frozen.activities.last().workId)
            assertEquals(work.minutes + work.remainingMinutes, frozen.activities.last().remainingMinutes)
            assertEquals(before.activities.first(), frozen.activities.first())
            assertEquals(listOf(1), current.state.value.courseProgress[Exam.IELTS]!!.completedDays)
            val changed = original.copy(version = original.version + 1, prompt = "An updated task.", expectedSeconds = 60)
            current.contentRepository.importPack(ContentPackCodec.encode(pack.copy(version = pack.version + 1,
                exercises = pack.exercises.map { if (it.id == original.id) changed else it })))
            shutdown(current)
            main { vm = StudyViewModel(application, db, settings) }
            current = vm!!; ready(current)
            main { current.startPlannedDay(2) }; ready(current)
            val continued = current.state.value.session!!
            assertEquals(work.workId, continued.workId)
            assertEquals(original, continued.exercise)
            assertTrue(continued.activity!!.continuation)
            assertEquals("A partial course response.", continued.draft)
            assertEquals(4, continued.previousWorkSeconds)
            assertEquals(1, continued.hintsUsed)
            assertEquals(listOf("/fixture/course.wav"), continued.recordingPaths)
            assertTrue(current.state.value.attempts.isEmpty())
        } finally { shutdown(vm, db) }
    }

    @Test fun switchingToIntensiveCarriesAnUnfinishedCourseLesson(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        var vm: StudyViewModel? = null
        try {
            fixture(db)
            main { vm = StudyViewModel(application, db, MemorySettings()) }
            val current = vm!!; ready(current)
            main { current.makePlan(); current.startPlannedDay(1) }; ready(current)
            val original = current.state.value.session!!.activity!!
            assertEquals(ActivityKind.LESSON, original.kind)
            main { current.makePlan(2) }; ready(current)
            assertTrue(current.state.value.session!!.stoppedEarly)
            assertEquals(PlanMode.INTENSIVE, current.state.value.plan!!.mode)
            assertTrue(current.state.value.plan!!.prioritySkillIds.size <= 3)
            assertEquals(original.minutes + original.remainingMinutes,
                current.state.value.dailyPlans[Exam.IELTS]!!.days.first().activities.last().remainingMinutes)
            main { current.makePlan(); current.startPlannedDay(2) }; ready(current)
            val continued = current.state.value.session!!.activity!!
            assertEquals(ActivityKind.LESSON, continued.kind)
            assertEquals(original.workId, continued.workId)
            assertEquals(original.exerciseSnapshot, continued.exerciseSnapshot)
            assertTrue(current.state.value.attempts.isEmpty())
        } finally { shutdown(vm, db) }
    }

    @Test fun legacyDraftAliasRetainsItsPayloadAndNeverOverwritesAnotherWork(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(db)
            val exercise = pack.exercises.first()
            val legacy = StudyDraft(Exam.IELTS, exercise.id, exercise.version, "The preserved legacy text.", elapsedSeconds = 9,
                hintsUsed = 1, recordingPath = "/fixture/legacy.wav", updatedAt = 10)
            val newer = legacy.copy(workId = "separate-newer-work", text = "A different newer draft.", elapsedSeconds = 15, updatedAt = 20)
            val oldPayload = JsonObject(storageJson.parseToJsonElement(storageJson.encodeToString(legacy)).jsonObject
                .filterKeys { it !in setOf("workId", "supersededByWorkId", "recordingPaths") }).toString()
            db.dao().put(StoredRecord("draft:${legacy.key}", "draft", Exam.IELTS.name, oldPayload))
            db.dao().put(StoredRecord("draft:${newer.key}", "draft", Exam.IELTS.name, storageJson.encodeToString(newer)))
            main { vm = StudyViewModel(application, db, settings) }
            var current = vm!!; ready(current)
            main { assertTrue(current.resumeDraft(legacy.key)) }; ready(current)
            val workId = current.state.value.session!!.workId
            assertNotEquals(newer.workId, workId)
            assertEquals(legacy.text, current.state.value.session!!.draft)
            assertEquals(listOf("/fixture/legacy.wav"), current.state.value.session!!.recordingPaths)
            assertEquals(workId, current.state.value.drafts.getValue(legacy.key).supersededByWorkId)
            assertEquals(legacy.text, current.state.value.drafts.getValue(legacy.key).text)
            assertEquals(newer, current.state.value.drafts.getValue(newer.key))
            main { current.draft("The migrated work has new edits."); assertTrue(current.finishForNow()) }; ready(current)
            shutdown(current)
            main { vm = StudyViewModel(application, db, settings) }
            current = vm!!; ready(current)
            main { assertTrue(current.resumeDraft(legacy.key)) }; ready(current)
            assertEquals(workId, current.state.value.session!!.workId)
            assertEquals("The migrated work has new edits.", current.state.value.session!!.draft)
            assertEquals(newer, current.state.value.drafts.getValue(newer.key))
            main { current.submit(); current.next() }; ready(current)
            main { assertFalse(current.resumeDraft(legacy.key)) }
            assertEquals(1, current.state.value.attempts.size)
            assertEquals(legacy.text, current.state.value.drafts.getValue(legacy.key).text)
        } finally { shutdown(vm, db) }
    }

    @Test fun stoppedCourseReviewResumesAsReviewWithoutCreatingAnAttempt(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(db)
            val exercise = pack.exercises.first()
            val plan = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 20_000, 15)
            val review = PlannedActivity("review-slice", "preserved-review-work", ActivityKind.REVIEW,
                exercise.skillId, exercise.id, 2, lessonId = "writing-rule", exerciseVersion = exercise.version, exerciseSnapshot = exercise)
            val day = plan.days.first().copy(activities = listOf(review), exerciseIds = listOf(exercise.id), skillIds = listOf(exercise.skillId), isReview = true)
            val storedPlan = plan.copy(days = listOf(day) + plan.days.drop(1))
            val session = StudySession(exam = Exam.IELTS, mode = ContentSplit.PRACTICE, exercises = listOf(exercise),
                courseDay = 1, coursePlanId = plan.id, daySnapshot = day, activities = listOf(review), lessonSnapshots = pack.lessons)
            db.dao().put(StoredRecord("plan:IELTS", "plan", "IELTS", storageJson.encodeToString(storedPlan)))
            db.dao().put(StoredRecord("course_plan:IELTS", "course_plan", "IELTS", storageJson.encodeToString(storedPlan)))
            db.dao().put(StoredRecord("session:IELTS", "session", "IELTS", storageJson.encodeToString(session)))
            main { vm = StudyViewModel(application, db, MemorySettings()) }
            val current = vm!!; ready(current)
            main { assertTrue(current.finishForNow()) }; ready(current)
            assertTrue(current.state.value.attempts.isEmpty())
            assertTrue(current.state.value.drafts.values.none { it.workId == review.workId })
            assertEquals(2, current.state.value.plan!!.days.first().activities.single().remainingMinutes)
            main { current.startPlannedDay(2) }; ready(current)
            val carried = current.state.value.session!!.activity!!
            assertEquals(review.workId, carried.workId)
            assertEquals(ActivityKind.REVIEW, carried.kind)
            assertEquals(review.exerciseSnapshot, carried.exerciseSnapshot)
            assertTrue(current.state.value.plan!!.days.flatMap { it.activities }
                .none { it.workId == review.workId && it.kind == ActivityKind.PRACTICE })
            main { current.lessonSeen(current.state.value.session!!.stepKey) }; ready(current)
            assertTrue(current.state.value.attempts.isEmpty())
            assertTrue(current.state.value.session!!.activities.single { it.workId == review.workId }.remainingMinutes == 0)
        } finally { shutdown(vm, db) }
    }

    @Test fun explicitResumeHonoursTheActiveIntensivePriorities(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(db)
            val exercise = pack.exercises.first()
            val draft = StudyDraft(Exam.IELTS, exercise.id, exercise.version, "Saved writing outside this intensive.", workId = "outside-priority")
            val plan = StudyPlanner.intensive(pack, Exam.IELTS, emptyList(), 20_000, 2).copy(prioritySkillIds = listOf("z-reading"))
            db.dao().put(StoredRecord("draft:${draft.key}", "draft", "IELTS", storageJson.encodeToString(draft)))
            db.dao().put(StoredRecord("plan:IELTS", "plan", "IELTS", storageJson.encodeToString(plan)))
            main { vm = StudyViewModel(application, db, MemorySettings()) }
            val current = vm!!; ready(current)
            main { assertTrue(current.resumeDraft(draft.key)) }
            withTimeout(15_000) { current.state.first { !it.loading && it.pendingWrites == 0 } }
            assertNotNull(current.state.value.error)
            assertNull(current.state.value.session)
            assertEquals(draft, current.state.value.drafts.getValue(draft.key))
            assertEquals(listOf("z-reading"), current.state.value.plan!!.prioritySkillIds)
            assertTrue(current.state.value.attempts.isEmpty())
            main {
                current.clearError(); current.startSession(ContentSplit.DIAGNOSTIC)
                assertEquals("writing", current.state.value.session!!.exercise!!.skillId)
                current.draft("A diagnostic outside the practice priorities.")
                current.completeBlock(current.state.value.plan!!.blocks.single { it.type == PlanBlockType.DIAGNOSTIC }.id)
            }
            ready(current)
            assertTrue(current.state.value.session!!.stoppedEarly)
            assertTrue(current.state.value.plan!!.blocks.single { it.type == PlanBlockType.DIAGNOSTIC }.completed)
            assertFalse(current.state.value.profile.diagnosticCompleted)
            assertTrue(current.state.value.attempts.isEmpty())
        } finally { shutdown(vm, db) }
    }

    @Test fun stoppingDiagnosticDoesNotPretendCoverageIsComplete(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        var vm: StudyViewModel? = null
        try {
            fixture(db)
            main { vm = StudyViewModel(application, db, MemorySettings()) }
            val current = vm!!; ready(current)
            main { current.makePlan(2); current.startSession(ContentSplit.DIAGNOSTIC); current.draft("An unfinished diagnostic."); assertTrue(current.finishForNow()) }
            ready(current)
            assertTrue(current.state.value.attempts.isEmpty())
            assertFalse(current.state.value.profile.diagnosticCompleted)
            assertFalse(current.state.value.plan!!.blocks.single { it.type == PlanBlockType.DIAGNOSTIC }.completed)
            main { current.startSession(ContentSplit.PRACTICE, "writing"); current.lessonSeen(); current.draft("Practice before a revised intensive."); current.makePlan(4) }
            ready(current)
            assertTrue(current.state.value.session!!.stoppedEarly)
            assertEquals(240, current.state.value.plan!!.totalMinutes)
            assertTrue(current.state.value.attempts.isEmpty())
        } finally { shutdown(vm, db) }
    }
}
