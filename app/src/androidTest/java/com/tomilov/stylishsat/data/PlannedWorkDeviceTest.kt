package com.tomilov.stylishsat.data

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.CourseProgress
import com.tomilov.stylishsat.StudySession
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class PlannedWorkDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext as Application
    @get:Rule val testName = TestName()
    private var waitNumber = 0
    private class MemorySettings : SettingsStore {
        override val flow = MutableStateFlow(Settings(Exam.IELTS, Language.EN))
        override suspend fun exam(value: Exam) { flow.value = flow.value.copy(exam = value) }
        override suspend fun language(value: Language) { flow.value = flow.value.copy(language = value) }
    }
    private fun main(block: () -> Unit) = instrumentation.runOnMainSync(block)
    private fun trace(vm: StudyViewModel, phase: String, detail: String = "") {
        val state = vm.state.value
        val session = state.session
        Log.i("PlannedWorkTest", "test=${testName.methodName} phase=$phase elapsedMs=${SystemClock.elapsedRealtime()} " +
            "day=${session?.courseDay} step=${session?.index}/${session?.stepCount} kind=${session?.activity?.kind} " +
            "work=${session?.workId} loading=${state.loading} pendingWrites=${state.pendingWrites} " +
            "attempts=${state.attempts.size} finished=${session?.finished} draftChars=${session?.draft?.length} $detail")
    }
    private suspend fun ready(vm: StudyViewModel, phase: String = "await-state") {
        val label = "$phase#${++waitNumber}"
        val started = SystemClock.elapsedRealtime()
        trace(vm, "$label/begin")
        try {
            withTimeout(10_000) { vm.state.first { !it.loading && it.pendingWrites == 0 } }
            assertNull(vm.state.value.error)
            trace(vm, "$label/ready", "waitMs=${SystemClock.elapsedRealtime() - started}")
        } catch (error: Throwable) {
            trace(vm, "$label/failed", "waitMs=${SystemClock.elapsedRealtime() - started} error=${error.javaClass.simpleName}")
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
                Log.i("PlannedWorkTest", "test=${testName.methodName} phase=$phase/database-close-begin elapsedMs=${SystemClock.elapsedRealtime()}")
                database.close()
                Log.i("PlannedWorkTest", "test=${testName.methodName} phase=$phase/database-closed elapsedMs=${SystemClock.elapsedRealtime()}")
            }
        }
    }
    private suspend fun fixture(
        database: StudyDatabase,
        skillId: String = "writing",
        responseType: ExerciseType = ExerciseType.WRITING,
        expectedSeconds: Int = 2400,
    ): ContentPack {
        val info = bundledPackInfo(application)
        val skill = Skill(skillId, Exam.IELTS, LocalizedText("Open response", "Открытый ответ"), "Open response")
        val lesson = Lesson("$skillId-rule", skill.id, LocalizedText("Rule", "Правило"), LocalizedText("Body", "Текст"), LocalizedText("Example", "Пример"), 8)
        fun item(id: String, split: ContentSplit) = Exercise(id, exam = Exam.IELTS, skillId = skill.id, split = split, familyId = id, type = responseType,
            prompt = "Give an original response.", expectedSeconds = expectedSeconds, author = "test", criteria = listOf(LocalizedText("Support each point", "Обоснуйте мысли")), hints = listOf(LocalizedText("Outline first", "Сначала план")))
        val pack = ContentPack(id = info.contentId, version = info.contentVersion + 1, title = LocalizedText("Fixture", "Тест"),
            skills = listOf(skill), lessons = listOf(lesson), exercises = listOf(item("$skillId-p", ContentSplit.PRACTICE)) +
                (1..3).map { item("$skillId-a$it", ContentSplit.ASSESSMENT) })
        ContentPackCodec.validate(pack)
        database.dao().insertPack(StoredPack(pack.id, pack.version, storageJson.encodeToString(pack)))
        return pack
    }

    private suspend fun startFirstWritingPiece(vm: StudyViewModel) {
        main { vm.saveProfile(vm.state.value.profile.copy(dailyMinutes = 15)); vm.makePlan(); vm.startPlannedDay(1) }
        ready(vm)
        assertTrue(vm.state.value.session!!.activity!!.isLesson)
        main { vm.lessonSeen(vm.state.value.session!!.stepKey) }
        ready(vm)
        assertEquals(ActivityKind.PRACTICE, vm.state.value.session!!.activity!!.kind)
        assertTrue(vm.state.value.session!!.activity!!.remainingMinutes > 0)
    }
    @Test fun longDraftSurvivesNewViewModelAndDoesNotCreatePartialAttempts(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(database)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current)
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 15)); current.makePlan(); current.startPlannedDay(1) }
            ready(current)
            assertTrue(current.state.value.session!!.activity!!.isLesson)
            main { current.lessonSeen(current.state.value.session!!.stepKey) }
            ready(current)
            val workId = current.state.value.session!!.activity!!.workId
            assertTrue(current.state.value.session!!.activity!!.remainingMinutes > 0)
            val text = "An intact draft with evidence, an unfinished argument and a next step."
            main {
                current.draft(text, current.state.value.session!!.stepKey)
                current.hint(); current.attachRecording("/test/original-recording.wav")
                repeat(3) { current.tick() }
                current.checkpoint(current.state.value.session!!.stepKey)
            }
            ready(current)
            assertTrue(current.state.value.session!!.finished)
            assertTrue(current.state.value.attempts.isEmpty())
            assertEquals(listOf(1), current.state.value.courseProgress[Exam.IELTS]!!.completedDays)
            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            main { current.startPlannedDay(2) }; ready(current)
            val restored = current.state.value.session!!
            assertEquals(workId, restored.activity!!.workId)
            assertEquals(text, restored.draft)
            assertEquals(1, restored.hintsUsed)
            assertEquals(3, restored.previousWorkSeconds)
            assertEquals("/test/original-recording.wav", restored.recordingPath)
            var guard = 0
            while (current.state.value.session!!.activity!!.remainingMinutes > 0 && guard++ < 10) {
                main { current.checkpoint(current.state.value.session!!.stepKey) }; ready(current)
                val nextDay = current.state.value.courseProgress[Exam.IELTS]!!.completedDays.max() + 1
                main { current.startPlannedDay(nextDay) }; ready(current)
            }
            assertTrue(guard < 10)
            assertTrue(current.state.value.attempts.isEmpty())
            main { current.submit(); current.next() }; ready(current)
            val attempt = current.state.value.attempts.single()
            assertEquals(text, attempt.answer)
            assertEquals(workId, attempt.workId)
            assertEquals(1, attempt.hintsUsed)
            assertEquals(3, attempt.elapsedSeconds)
            assertNull(attempt.correct)
            assertEquals(0, current.state.value.skillStates.single().independentCount)
        } finally { shutdown(vm, "teardown", database) }
    }
    @Test fun staleEditorAndLessonCallbacksCannotChangeTheNextStep(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        var vm: StudyViewModel? = null
        try {
            fixture(database)
            main { vm = StudyViewModel(application, database, MemorySettings()) }
            val current = vm!!; ready(current)
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 15)); current.makePlan(); current.startPlannedDay(1) }; ready(current)
            val lessonKey = current.state.value.session!!.stepKey
            main { current.lessonSeen(lessonKey) }; ready(current)
            val newKey = current.state.value.session!!.stepKey
            assertNotEquals(lessonKey, newKey)
            main {
                current.draft("current answer", newKey); current.draft("stale overwrite", lessonKey)
                current.applyTranscript("stale ASR", lessonKey); current.lessonSeen(lessonKey); current.checkpoint(lessonKey)
                current.attachRecording("/test/stale-callback.wav", lessonKey)
                current.saveFeedback("A delayed result for the previous step", "LOCAL_AI", lessonKey)
            }
            ready(current)
            assertEquals(newKey, current.state.value.session!!.stepKey)
            assertEquals("current answer", current.state.value.session!!.draft)
            assertFalse(current.state.value.session!!.finished)
            assertNull(current.state.value.session!!.recordingPath)
            assertTrue(current.state.value.session!!.recordingPaths.isEmpty())
            assertTrue(current.state.value.feedback.isEmpty())
            assertEquals("", current.state.value.session!!.externalFeedback)
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun ordinaryPracticeResumesTheCourseDraftAndASeparateRepeatCannotOverwriteIt(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(database)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current)
            startFirstWritingPiece(current)
            val originalWork = current.state.value.session!!.workId
            val originalText = "The course draft already contains a reason and a carefully chosen example."
            main {
                current.draft(originalText, current.state.value.session!!.stepKey)
                current.hint(); current.attachRecording("/test/course-recording.wav")
                repeat(4) { current.tick() }
                current.checkpoint(current.state.value.session!!.stepKey)
            }
            ready(current)
            assertTrue(current.state.value.session!!.finished)
            assertTrue(current.state.value.attempts.isEmpty())

            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            main { current.startSession(ContentSplit.PRACTICE, "writing") }; ready(current)
            val manual = current.state.value.session!!
            assertNull(manual.activity)
            assertTrue(manual.resumingDraft)
            assertTrue("resuming cannot open a blank lesson/editor over the saved work", manual.lessonSeen)
            assertEquals(originalWork, manual.workId)
            assertEquals(originalText, manual.draft)
            assertEquals(4, manual.previousWorkSeconds)
            assertEquals(1, manual.hintsUsed)
            assertEquals("/test/course-recording.wav", manual.recordingPath)

            val revised = "$originalText The conclusion is now complete."
            main {
                current.draft(revised, manual.stepKey)
                repeat(2) { current.tick() }
                current.submit(); current.next()
            }
            ready(current)
            val attempt = current.state.value.attempts.single()
            assertEquals(originalWork, attempt.workId)
            assertEquals(revised, attempt.answer)
            assertEquals(6, attempt.elapsedSeconds)
            assertEquals(listOf(1), current.state.value.courseProgress[Exam.IELTS]!!.completedDays)
            assertTrue(current.state.value.plan!!.days.drop(1).flatMap { it.activities }.none { it.workId == originalWork })

            main { current.startSession(ContentSplit.PRACTICE, "writing") }; ready(current)
            val repeated = current.state.value.session!!
            assertNotEquals(originalWork, repeated.workId)
            assertFalse(repeated.resumingDraft)
            assertEquals("", repeated.draft)
            main { current.lessonSeen(repeated.stepKey) }; ready(current)
            val drafts = current.state.value.drafts.values.filter { it.exerciseId == "writing-p" }
            assertEquals(2, drafts.size)
            assertEquals(revised, drafts.single { it.workId == originalWork }.text)
            assertTrue(drafts.single { it.workId == originalWork }.submitted)
            assertEquals("", drafts.single { it.workId == repeated.workId }.text)
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun importedExerciseRevisionCannotReplaceAnUnfinishedCoursesPromptOrDraft(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val originalPack = fixture(database)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current)
            startFirstWritingPiece(current)
            val originalSession = current.state.value.session!!
            val originalExercise = originalSession.exercise!!
            val originalWork = originalSession.workId
            val draft = "An unfinished response to the original prompt must remain attached to that prompt."
            main {
                current.draft(draft, originalSession.stepKey)
                current.hint(); current.attachRecording("/test/version-one.wav")
                repeat(5) { current.tick() }
                current.checkpoint(originalSession.stepKey)
            }
            ready(current)
            val changed = originalExercise.copy(version = originalExercise.version + 1,
                prompt = "A revised prompt with a different task and a shorter expected duration.", expectedSeconds = 60)
            val updatedPack = originalPack.copy(version = originalPack.version + 1,
                exercises = originalPack.exercises.map { if (it.id == changed.id) changed else it })
            current.contentRepository.importPack(ContentPackCodec.encode(updatedPack))
            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            assertEquals(changed, current.state.value.pack!!.exercises.single { it.id == changed.id })
            main { current.makePlan(); current.startPlannedDay(2) }; ready(current)
            val restored = current.state.value.session!!
            assertEquals(originalExercise, restored.exercise)
            assertEquals(originalExercise.version, restored.activity!!.exerciseVersion)
            assertEquals(originalWork, restored.workId)
            assertEquals(draft, restored.draft)
            assertEquals(5, restored.previousWorkSeconds)
            assertEquals(1, restored.hintsUsed)
            assertEquals("/test/version-one.wav", restored.recordingPath)

            // An early submission should finish this historical work, not manufacture a v2 answer.
            main { current.submit(); current.next() }; ready(current)
            val attempt = current.state.value.attempts.single()
            assertEquals(originalExercise.version, attempt.exerciseVersion)
            assertEquals(originalExercise.expectedSeconds, attempt.expectedSeconds)
            assertEquals(originalWork, attempt.workId)
            assertEquals(draft, attempt.answer)
            assertTrue(current.state.value.plan!!.days.drop(2).flatMap { it.activities }.none { it.workId == originalWork })
            assertEquals(2, current.contentRepository.allExercises().count { it.id == originalExercise.id })
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun submittingTheFirstWritingPieceEarlyDoesNotLeavePhantomContinuations(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(database)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current)
            startFirstWritingPiece(current)
            val work = current.state.value.session!!.workId
            main {
                current.draft("A complete response submitted before the estimated allocation ends.")
                repeat(3) { current.tick() }
                current.submit(); current.next()
            }
            ready(current)
            assertEquals(1, current.state.value.attempts.size)
            assertEquals(3, current.state.value.attempts.single().elapsedSeconds)
            assertEquals(0, current.state.value.plan!!.days.first().activities.last().remainingMinutes)
            assertTrue(current.state.value.plan!!.days.drop(1).flatMap { it.activities }.none { it.workId == work })
            assertTrue(current.state.value.plan!!.days[1].activities.any { it.kind == ActivityKind.REVIEW })

            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            main { current.startPlannedDay(2) }; ready(current)
            assertEquals(ActivityKind.REVIEW, current.state.value.session!!.activity!!.kind)
            main { current.lessonSeen(current.state.value.session!!.stepKey) }; ready(current)
            assertEquals("reviewing a submitted answer must not create another attempt", 1, current.state.value.attempts.size)
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun savingAnIdleCoursesDailyBudgetRefreshesFutureDaysWithoutChangingCompletedWork(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val pack = fixture(database)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current, "idle-budget/initial-load")
            startFirstWritingPiece(current)
            val text = "An unfinished argument remains safe while tomorrow's budget changes."
            main {
                current.draft(text); repeat(3) { current.tick() }
                current.checkpoint(current.state.value.session!!.stepKey)
            }
            ready(current)
            val oldPlan = current.state.value.plan!!
            val completedDay = oldPlan.days.first()
            val finishedSession = current.state.value.session!!
            val drafts = current.state.value.drafts
            val progress = current.state.value.courseProgress[Exam.IELTS]
            trace(current, "idle-budget/save-profile")
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 5)) }
            ready(current, "idle-budget/profile-saved")
            val refreshed = current.state.value.plan!!
            assertEquals(oldPlan.id, refreshed.id)
            assertEquals(pack.version, refreshed.contentVersion)
            assertEquals(completedDay, refreshed.days.first())
            assertTrue(refreshed.days.drop(1).all { it.minutes == 5 && it.activities.sumOf { activity -> activity.minutes } <= 5 })
            assertEquals(progress, current.state.value.courseProgress[Exam.IELTS])
            assertEquals(finishedSession, current.state.value.session)
            assertEquals(drafts, current.state.value.drafts)
            assertTrue(current.state.value.attempts.isEmpty())

            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current, "idle-budget/reloaded")
            assertEquals(5, current.state.value.profile.dailyMinutes)
            assertEquals(refreshed, current.state.value.plan)
            main { current.startPlannedDay(2) }; ready(current, "idle-budget/start-next-day")
            assertEquals(text, current.state.value.session!!.draft)
            assertEquals(finishedSession.workId, current.state.value.session!!.workId)
            assertEquals(3, current.state.value.session!!.previousWorkSeconds)
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun changingTheBudgetKeepsAnActiveDayIntactAndAppliesAfterItsCheckpoint(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(database)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current)
            startFirstWritingPiece(current)
            main {
                current.draft("First day's preserved notes."); repeat(2) { current.tick() }
                current.checkpoint(current.state.value.session!!.stepKey)
            }
            ready(current)
            main { current.startPlannedDay(2) }; ready(current)
            val text = "The second day adds evidence without discarding the first day's work."
            main {
                current.draft(text); current.hint(); current.attachRecording("/test/active-day.wav")
                repeat(3) { current.tick() }
            }
            ready(current)
            val active = current.state.value.session!!
            val planBefore = current.state.value.plan!!
            val draftsBefore = current.state.value.drafts
            val progressBefore = current.state.value.courseProgress[Exam.IELTS]
            assertTrue(active.activity!!.remainingMinutes > 0)
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 5)) }; ready(current)
            assertEquals(5, current.state.value.profile.dailyMinutes)
            assertEquals(active, current.state.value.session)
            assertEquals(planBefore, current.state.value.plan)
            assertEquals(draftsBefore, current.state.value.drafts)
            assertEquals(progressBefore, current.state.value.courseProgress[Exam.IELTS])

            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            assertEquals(active, current.state.value.session)
            assertEquals(5, current.state.value.profile.dailyMinutes)
            main { current.checkpoint(active.stepKey) }; ready(current)
            val refreshed = current.state.value.plan!!
            assertEquals(planBefore.days.first(), refreshed.days.first())
            assertEquals(active.daySnapshot, refreshed.days[1])
            assertEquals(listOf(1, 2), current.state.value.courseProgress[Exam.IELTS]!!.completedDays)
            assertTrue(refreshed.days.drop(2).all { it.minutes == 5 && it.activities.sumOf { activity -> activity.minutes } <= 5 })
            assertTrue(current.state.value.attempts.isEmpty())
            main { current.startPlannedDay(3) }; ready(current)
            assertEquals(text, current.state.value.session!!.draft)
            assertEquals(active.workId, current.state.value.session!!.workId)
            assertEquals(5, current.state.value.session!!.previousWorkSeconds)
            assertEquals("/test/active-day.wav", current.state.value.session!!.recordingPath)
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun restoringALegacyCourseRebuildsOnlyFutureDaysFromTheUpdatedBank(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            val oldPack = fixture(database)
            val oldExercise = oldPack.exercises.single { it.split == ContentSplit.PRACTICE }
            val start = LocalDate.now().toEpochDay()
            val legacy = StudyPlan("course-IELTS-$start", Exam.IELTS, PlanMode.COURSE, start, 28 * 15,
                listOf("writing"), days = (1..28).map { day ->
                    PlanDay(day, start + day - 1, 15, listOf("writing"), listOf(oldExercise.id))
                })
            val progress = CourseProgress(Exam.IELTS, legacy.id, listOf(1))
            val historicalAnswer = "The original completed answer and its version remain part of the history."
            val historical = Attempt("historical-attempt", oldExercise.id, oldExercise.version, Exam.IELTS,
                "writing", historicalAnswer, null, start * 86_400_000L, elapsedSeconds = 19,
                split = ContentSplit.PRACTICE, familyId = oldExercise.familyId, sourceId = oldExercise.sourceId,
                expectedSeconds = oldExercise.expectedSeconds, localDateEpochDay = start)
            val finished = StudySession(id = "legacy-finished", exam = Exam.IELTS, mode = ContentSplit.PRACTICE,
                exercises = listOf(oldExercise), draft = historicalAnswer, activeSeconds = 19,
                result = AnswerResult(AnswerStatus.NEEDS_REVIEW, null, LocalizedText("Saved", "Сохранено")),
                finished = true, lessonSeen = true, courseDay = 1, coursePlanId = legacy.id, daySnapshot = legacy.days.first())
            val legacyPayload = JsonObject(storageJson.parseToJsonElement(storageJson.encodeToString(legacy)).jsonObject - "contentVersion").toString()
            assertFalse("the fixture really predates the plan-version field", legacyPayload.contains("contentVersion"))
            listOf(
                StoredRecord("plan:IELTS", "plan", "IELTS", legacyPayload),
                StoredRecord("course_plan:IELTS", "course_plan", "IELTS", legacyPayload),
                StoredRecord("course_progress:IELTS", "course_progress", "IELTS", storageJson.encodeToString(progress)),
                StoredRecord("profile:IELTS", "profile", "IELTS", storageJson.encodeToString(ExamProfile(Exam.IELTS, dailyMinutes = 10))),
                StoredRecord("attempt:${historical.id}", "attempt", "IELTS", storageJson.encodeToString(historical)),
                StoredRecord("session:IELTS", "session", "IELTS", storageJson.encodeToString(finished)),
            ).forEach { database.dao().put(it) }
            val replacement = oldExercise.copy(id = "writing-new-p", familyId = "new-writing-family", sourceId = "new-writing-source",
                prompt = "A new practice task introduced by the bank update.")
            val newPack = oldPack.copy(version = oldPack.version + 1,
                exercises = oldPack.exercises.filter { it.id != oldExercise.id } + replacement)
            ContentRepository(application, database).importPack(ContentPackCodec.encode(newPack))
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!
            withTimeout(10_000) { current.state.first { !it.loading && it.plan?.contentVersion == newPack.version && it.pendingWrites == 0 } }
            ready(current)
            val refreshed = current.state.value.plan!!
            assertEquals(legacy.id, refreshed.id)
            assertEquals(newPack.version, refreshed.contentVersion)
            assertEquals(legacy.days.first(), refreshed.days.first())
            assertTrue(refreshed.days.drop(1).all { it.minutes == 10 })
            val future = refreshed.days.drop(1).flatMap { it.activities }
            assertTrue(future.isNotEmpty())
            assertTrue(future.any { it.exerciseId == replacement.id })
            assertTrue(future.none { it.exerciseId == oldExercise.id })
            assertTrue(future.all { it.exerciseSnapshot != null })
            assertEquals(progress, current.state.value.courseProgress[Exam.IELTS])
            assertEquals(listOf(historical), current.state.value.attempts)
            assertEquals(finished, current.state.value.session)

            val stored = database.dao().records()
            assertEquals(refreshed, storageJson.decodeFromString<StudyPlan>(stored.single { it.key == "course_plan:IELTS" }.payload))
            assertEquals(historical, storageJson.decodeFromString<Attempt>(stored.single { it.key == "attempt:${historical.id}" }.payload))
            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            assertEquals("a second restore must not regenerate an already current route", refreshed, current.state.value.plan)
            assertEquals(progress, current.state.value.courseProgress[Exam.IELTS])
            assertEquals(listOf(historical), current.state.value.attempts)
        } finally { shutdown(vm, "teardown", database) }
    }

    @Test fun everySpeakingTakeSurvivesDraftRestartAndSubmission(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(application, StudyDatabase::class.java).build()
        val settings = MemorySettings()
        var vm: StudyViewModel? = null
        try {
            fixture(database, skillId = "speaking", responseType = ExerciseType.SPEAKING, expectedSeconds = 600)
            main { vm = StudyViewModel(application, database, settings) }
            var current = vm!!; ready(current)
            main { current.saveProfile(current.state.value.profile.copy(dailyMinutes = 15)); current.makePlan(); current.startPlannedDay(1) }
            ready(current)
            main { current.lessonSeen(current.state.value.session!!.stepKey) }; ready(current)
            val step = current.state.value.session!!
            assertEquals(ExerciseType.SPEAKING, step.exercise!!.type)
            assertTrue(step.activity!!.remainingMinutes > 0)
            val takes = listOf("/test/speaking-take-one.wav", "/test/speaking-take-two.wav")
            main {
                current.draft("A reviewed transcript covering the two recorded answers.", step.stepKey)
                current.attachRecording(takes[0], step.stepKey)
                current.attachRecording(takes[1], step.stepKey)
                current.attachRecording(takes[1], step.stepKey) // Start/stop may report the same file twice.
                current.checkpoint(step.stepKey)
            }
            ready(current)
            val savedDraft = current.state.value.drafts.values.single { it.workId == step.workId }
            assertEquals(takes, savedDraft.recordingPaths)
            assertEquals(takes.last(), savedDraft.recordingPath)
            shutdown(current, "restart")
            main { vm = StudyViewModel(application, database, settings) }
            current = vm!!; ready(current)
            main { current.startPlannedDay(2) }; ready(current)
            assertEquals(takes, current.state.value.session!!.recordingPaths)
            assertEquals(takes.last(), current.state.value.session!!.recordingPath)
            main { current.submit() }; ready(current)
            val attempt = current.state.value.attempts.single()
            assertEquals(step.workId, attempt.workId)
            assertEquals(takes, attempt.recordingPaths)
            assertEquals(takes.last(), attempt.recordingPath)
            main { current.attachRecording("/test/late-take.wav", current.state.value.session!!.stepKey) }; ready(current)
            assertEquals("a graded response cannot receive a delayed recording callback", takes, current.state.value.session!!.recordingPaths)
            main { current.next(); current.startSession(ContentSplit.PRACTICE, "speaking") }; ready(current)
            assertTrue(current.state.value.session!!.recordingPaths.isEmpty())
            assertNull(current.state.value.session!!.recordingPath)
        } finally { shutdown(vm, "teardown", database) }
    }
}
