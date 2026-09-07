package com.tomilov.stylishsat

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.tomilov.stylishsat.data.*
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.time.LocalDate
import java.time.Clock
import java.util.UUID

@Serializable
data class StudySession(
    val id: String = UUID.randomUUID().toString(),
    val exam: Exam,
    val mode: ContentSplit,
    val exercises: List<Exercise>,
    val index: Int = 0,
    val draft: String = "",
    val draftRevision: Int = 0,
    val hintsUsed: Int = 0,
    val startedAt: Long = System.currentTimeMillis(),
    val activeSeconds: Int = 0,
    val result: AnswerResult? = null,
    val finished: Boolean = false,
    val lessonSeen: Boolean = false,
    val recordingPath: String? = null,
    val externalFeedback: String = "",
    val courseDay: Int? = null,
    val coursePlanId: String? = null,
    val daySnapshot: PlanDay? = null,
    val activities: List<PlannedActivity> = emptyList(),
    val lessonSnapshots: List<Lesson> = emptyList(),
    val previousWorkSeconds: Int = 0,
    val manualWorkId: String? = null,
    val resumingDraft: Boolean = false,
    val recordingPaths: List<String> = emptyList(),
    val stoppedEarly: Boolean = false,
    val timeLimitSeconds: Int? = null,
    val continuedWithoutTimeLimit: Boolean = false,
    val reviewGuidanceViewed: Boolean = false,
) {
    val activity: PlannedActivity? get() = activities.getOrNull(index)
    val stepCount: Int get() = activities.size.takeIf { it > 0 } ?: exercises.size
    val stepKey: String get() = "$id:$index"
    val workId: String get() = activity?.workId ?: manualWorkId ?: stepKey
    val exercise: Exercise? get() = if (activities.isEmpty()) exercises.getOrNull(index) else exercises.firstOrNull { it.id == activity?.exerciseId && (activity?.exerciseVersion == null || it.version == activity?.exerciseVersion) }
    val answerLockedByTimeLimit: Boolean get() = !continuedWithoutTimeLimit &&
        timeLimitSeconds?.let { previousWorkSeconds.toLong() + activeSeconds >= it } == true
}

@Serializable
data class StudyDraft(
    val exam: Exam, val exerciseId: String, val exerciseVersion: Int, val text: String,
    val workId: String? = null, val elapsedSeconds: Int = 0, val hintsUsed: Int = 0,
    val recordingPath: String? = null, val submitted: Boolean = false,
    val updatedAt: Long = 0,
    val recordingPaths: List<String> = emptyList(),
    val supersededByWorkId: String? = null,
    val timeLimitSeconds: Int? = null,
    val continuedWithoutTimeLimit: Boolean = false,
    val reviewGuidanceViewed: Boolean = false,
) { val key: String get() = "${exam.name}:$exerciseId:$exerciseVersion:${workId ?: "legacy"}" }

@Serializable
data class CourseProgress(val exam: Exam, val planId: String, val completedDays: List<Int> = emptyList())

data class StudyUiState(
    val loading: Boolean = true,
    val error: String? = null,
    val pendingWrites: Int = 0,
    val settings: Settings = Settings(),
    val pack: ContentPack? = null,
    val profiles: Map<Exam, ExamProfile> = Exam.entries.associateWith { ExamProfile(it) },
    val sessions: Map<Exam, StudySession> = emptyMap(),
    val plans: Map<Exam, StudyPlan> = emptyMap(),
    val dailyPlans: Map<Exam, StudyPlan> = emptyMap(),
    val attempts: List<Attempt> = emptyList(),
    val courseProgress: Map<Exam, CourseProgress> = emptyMap(),
    val feedback: List<Feedback> = emptyList(),
    val drafts: Map<String, StudyDraft> = emptyMap(),
) {
    val exam get() = settings.exam
    val language get() = settings.language
    val profile get() = profiles.getValue(exam)
    val session get() = sessions[exam]
    val plan get() = plans[exam]
    val examAttempts get() = attempts.filter { it.exam == exam }
    val skillStates get() = pack?.let { StudyPlanner.statesFromAttempts(it, attempts) }.orEmpty()
}

class StudyViewModel @JvmOverloads constructor(application: Application, databaseOverride: StudyDatabase? = null, settingsOverride: SettingsStore? = null, clockOverride: Clock? = null) : AndroidViewModel(application) {
    val runtime = com.tomilov.stylishsat.ai.RuntimeServices.get(application)
    val database = databaseOverride ?: StudyDatabase.get(application)
    val contentRepository = ContentRepository(application, database)
    private val settings: SettingsStore = settingsOverride ?: UserSettings(application)
    private val clock = clockOverride ?: Clock.systemDefaultZone()
    private fun today(): Long = LocalDate.now(clock).toEpochDay()
    private fun now(): Long = clock.millis()
    private val mutableState = MutableStateFlow(StudyUiState())
    val state = mutableState.asStateFlow()
    private val writes = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    private var resumingDraftKey: String? = null

    init {
        viewModelScope.launch {
            for (write in writes) {
                var retryDelay = 1_000L
                while (true) {
                    try {
                        write()
                        mutableState.update { it.copy(pendingWrites = (it.pendingWrites - 1).coerceAtLeast(0)) }
                        break
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        mutableState.update { it.copy(error = if (it.language == Language.RU)
                            "Не удалось сохранить данные. Освободите место и оставьте приложение открытым: сохранение повторяется. ${error.message.orEmpty()}"
                            else "Local save failed. Free storage and keep the app open: saving will retry. ${error.message.orEmpty()}") }
                        delay(retryDelay)
                        retryDelay = (retryDelay * 2).coerceAtMost(30_000)
                    }
                }
            }
        }
        viewModelScope.launch {
            try {
                val initialSettings = settings.flow.first()
                val pack = contentRepository.initialize()
                val records = database.dao().records()
                withContext(Dispatchers.Default) {
                fun <T> decode(kind: String, block: (String) -> T) = records.filter { it.kind == kind }.map { block(it.payload) }
                val attempts = decode("attempt") { storageJson.decodeFromString<Attempt>(it) }
                val profiles = decode("profile") { storageJson.decodeFromString<ExamProfile>(it) }.map { it.copy(diagnosticCompleted = StudyPlanner.diagnosticComplete(pack, it.exam, attempts)) }
                val sessions = decode("session") { storageJson.decodeFromString<StudySession>(it) }
                val dailyPlans = decode("course_plan") { storageJson.decodeFromString<StudyPlan>(it) }
                val courses = decode("course_progress") { storageJson.decodeFromString<CourseProgress>(it) }
                val drafts = decode("draft") { storageJson.decodeFromString<StudyDraft>(it) }
                val plans = decode("plan") { storageJson.decodeFromString<StudyPlan>(it) }
                mutableState.update { it.copy(
                    pack = pack, settings = initialSettings,
                    profiles = it.profiles + profiles.associateBy { p -> p.exam },
                    sessions = sessions.associateBy { s -> s.exam }, plans = plans.associateBy { p -> p.exam },
                    courseProgress = courses.associateBy { c -> c.exam },
                    dailyPlans = dailyPlans.associateBy { p -> p.exam },
                    attempts = attempts, drafts = drafts.groupBy { draft -> draft.key }.mapValues { (_, versions) -> versions.maxWith(compareBy<StudyDraft>({ it.updatedAt }, { it.submitted })) },
                    feedback = decode("feedback") { storageJson.decodeFromString<Feedback>(it) },
                ) }
                }
                Exam.entries.forEach { refreshStoredCourse(it) }
                mutableState.update { it.copy(loading = false) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) { mutableState.update { it.copy(loading = false, error = e.message ?: "Content could not be loaded") } }
        }
    }
    private fun enqueue(write: suspend () -> Unit) {
        mutableState.update { it.copy(pendingWrites = it.pendingWrites + 1) }
        if (writes.trySend(write).isFailure) mutableState.update { it.copy(pendingWrites = (it.pendingWrites - 1).coerceAtLeast(0),
            error = if (it.language == Language.RU) "Сессия закрыта. Откройте приложение снова перед изменением ответа." else "This session is closed. Reopen the app before editing.") }
    }
    private inline fun <reified T> persist(key: String, kind: String, exam: Exam, value: T) {
        enqueue {
            val json = withContext(Dispatchers.Default) { storageJson.encodeToString(value) }
            database.dao().put(StoredRecord(key, kind, exam.name, json))
        }
    }
    fun stopRecording() { viewModelScope.launch { runtime.recorder.stop() } }
    fun clearError() { mutableState.update { it.copy(error = null) } }
    fun selectExam(exam: Exam) {
        if (resumingDraftKey != null) return
        mutableState.update { it.copy(settings = it.settings.copy(exam = exam)) }
        enqueue { settings.exam(exam) }
    }
    fun prepareLocalAcceleration() {
        viewModelScope.launch { runtime.acceleration.prepare() }
    }

    fun selectLanguage(language: Language) {
        mutableState.update { it.copy(settings = it.settings.copy(language = language)) }
        enqueue { settings.language(language) }
    }
    fun saveProfile(profile: ExamProfile) {
        mutableState.update { it.copy(profiles = it.profiles + (profile.exam to profile)) }
        persist("profile:${profile.exam}", "profile", profile.exam, profile)
        refreshStoredCourse(profile.exam, force = true)
    }
    /** Refresh future days only; a running day's snapshots remain its recovery contract. */
    private fun refreshStoredCourse(exam: Exam, force: Boolean = false) {
        val s = state.value
        val pack = s.pack ?: return
        val selectedPlan = s.plans[exam]
        val old = selectedPlan?.takeIf { it.mode == PlanMode.COURSE } ?: s.dailyPlans[exam] ?: return
        if (!force && old.contentVersion == pack.version && old.plannerVersion == StudyPlanner.CURRENT_PLANNER_VERSION) return
        if (s.sessions[exam]?.let { !it.finished && it.courseDay != null } == true) return
        val progress = s.courseProgress[exam]?.takeIf { it.planId == old.id }
        val completed = old.days.filter { it.dayNumber in progress?.completedDays.orEmpty() }
        val refreshed = StudyPlanner.course(pack, exam, s.skillStates, s.attempts, old.createdEpochDay,
            s.profiles.getValue(exam).dailyMinutes, completed)
        val updateSelected = selectedPlan?.mode == PlanMode.COURSE
        mutableState.update { it.copy(dailyPlans = it.dailyPlans + (exam to refreshed),
            plans = if (updateSelected) it.plans + (exam to refreshed) else it.plans) }
        enqueue {
            database.withTransaction {
                database.dao().put(StoredRecord("course_plan:$exam", "course_plan", exam.name, storageJson.encodeToString(refreshed)))
                if (updateSelected) database.dao().put(StoredRecord("plan:$exam", "plan", exam.name, storageJson.encodeToString(refreshed)))
            }
        }
    }
    private fun snapshotDraft(session: StudySession): StudyDraft? {
        val exercise = session.exercise
        return if (exercise != null && session.result == null && session.activity?.isLesson != true) StudyDraft(
            session.exam, exercise.id, exercise.version, session.draft, session.workId,
            session.previousWorkSeconds + session.activeSeconds, session.hintsUsed, session.recordingPath, updatedAt = now(),
            recordingPaths = (session.recordingPaths + listOfNotNull(session.recordingPath)).distinct(),
            timeLimitSeconds = session.timeLimitSeconds, continuedWithoutTimeLimit = session.continuedWithoutTimeLimit,
            reviewGuidanceViewed = session.reviewGuidanceViewed,
        ) else null
    }
    private fun saveSession(session: StudySession) {
        val draft = if (session.lessonSeen && !session.finished) snapshotDraft(session) else null
        val drafts = draftUpdates(session, draft)
        mutableState.update { it.copy(sessions = it.sessions + (session.exam to session), drafts = it.drafts + drafts.associateBy { item -> item.key }) }
        enqueue {
            database.withTransaction {
                database.dao().put(StoredRecord("session:${session.exam}", "session", session.exam.name, storageJson.encodeToString(session)))
                drafts.forEach { database.dao().put(StoredRecord("draft:${it.key}", "draft", session.exam.name, storageJson.encodeToString(it))) }
            }
        }
    }
    private fun draftUpdates(session: StudySession, draft: StudyDraft?): List<StudyDraft> {
        // Ordinary practice can also discover a pre-work-ID draft. Keep its payload
        // as an explicit alias; subsequent edits belong only to the new work record.
        val previous = state.value.sessions[session.exam]
        val legacySession = previous?.let { it.id == session.id && it.activities.isEmpty() && it.manualWorkId == null && !it.finished && it.result == null } == true
        val legacy = draft?.takeIf { (session.resumingDraft || legacySession) && it.key !in state.value.drafts }?.let {
            state.value.drafts.values.singleOrNull { old -> old.exam == it.exam && old.exerciseId == it.exerciseId &&
                old.exerciseVersion == it.exerciseVersion && old.workId == null && old.supersededByWorkId == null && !old.submitted }
                ?.copy(supersededByWorkId = it.workId)
        }
        return listOfNotNull(legacy, draft)
    }
    private fun restoreStep(session: StudySession): StudySession {
        val exercise = session.exercise ?: return session
        val matching = state.value.drafts.values.filter { it.exam == session.exam && it.exerciseId == exercise.id && it.exerciseVersion == exercise.version && !it.submitted && it.supersededByWorkId == null }
        val existing = if (session.activity != null) matching.firstOrNull { it.workId == session.activity!!.workId }
            else matching.maxByOrNull { it.updatedAt }
        val confident = StudyPlanner.confidentForIndependentPractice(state.value.skillStates.firstOrNull { it.skillId == exercise.skillId })
        val closed = exercise.type !in setOf(ExerciseType.WRITING, ExerciseType.SPEAKING)
        val timed = closed && session.activity?.isLesson != true &&
            (exercise.split == ContentSplit.ASSESSMENT || exercise.split == ContentSplit.PRACTICE && confident)
        return session.copy(
            draft = existing?.text.orEmpty(), hintsUsed = existing?.hintsUsed ?: 0, recordingPath = existing?.recordingPath,
            recordingPaths = existing?.recordingPaths?.ifEmpty { listOfNotNull(existing.recordingPath) }.orEmpty(),
            previousWorkSeconds = existing?.elapsedSeconds ?: 0, activeSeconds = 0, result = null, externalFeedback = "",
            manualWorkId = if (session.activity == null && existing != null) existing.workId ?: "draft-${UUID.randomUUID()}" else null, resumingDraft = existing != null,
            lessonSeen = session.activity?.let { !it.isLesson } ?: (existing != null || session.mode != ContentSplit.PRACTICE || confident),
            mode = exercise.split,
            // A recovered work item keeps its original contract, including legacy untimed drafts.
            timeLimitSeconds = if (existing != null) existing.timeLimitSeconds else exercise.expectedSeconds.takeIf { timed && it > 0 },
            continuedWithoutTimeLimit = existing?.continuedWithoutTimeLimit ?: false,
            reviewGuidanceViewed = existing?.reviewGuidanceViewed ?: false,
        )
    }
    private fun recordingBlocksStop(): Boolean {
        if (runtime.recorder.state.value !is com.tomilov.stylishsat.speech.RecorderState.RecordingAudio) return false
        mutableState.update { it.copy(error = if (it.language == Language.RU)
            "Остановите запись перед завершением занятия. Запись и черновик сохранятся."
            else "Stop recording before finishing for now. Your recording and draft will be preserved.") }
        return true
    }

    /** Stops a batch without grading its current response. Course work is carried by
     * the same remaining-minutes/snapshot contract as an ordinary checkpoint. */
    fun finishForNow(): Boolean {
        val s = state.value
        val session = s.session ?: return false
        if (s.loading || session.finished || recordingBlocksStop()) return false
        val draft = snapshotDraft(session)
        val activities = if (session.courseDay != null && session.activities.isEmpty()) {
            // Older saved days listed exercises without activity records. Give their
            // already stable step/work IDs the same explicit carry representation.
            session.exercises.take(session.index + 1).mapIndexed { index, exercise ->
                val lesson = if (index == session.index && !session.lessonSeen && exercise.split == ContentSplit.PRACTICE)
                    (session.lessonSnapshots + s.pack?.lessons.orEmpty()).firstOrNull { it.skillId == exercise.skillId } else null
                PlannedActivity("${session.id}:$index", if (index == session.index) session.workId else "${session.id}:$index",
                    if (lesson != null) ActivityKind.LESSON else if (exercise.split == ContentSplit.ASSESSMENT) ActivityKind.ASSESSMENT else ActivityKind.PRACTICE,
                    exercise.skillId, exercise.id, lesson?.estimatedMinutes ?: ((exercise.expectedSeconds + 59) / 60).coerceAtLeast(1) + 2,
                    lessonId = lesson?.id, exerciseVersion = exercise.version, exerciseSnapshot = exercise)
            }
        } else session.activities
        val retained = activities.take(session.index + 1).mapIndexed { index, activity ->
            if (index == session.index && session.result == null) activity.copy(
                remainingMinutes = activity.minutes + activity.remainingMinutes,
                exerciseVersion = session.exercise?.version ?: activity.exerciseVersion,
                exerciseSnapshot = session.exercise ?: activity.exerciseSnapshot,
            ) else activity
        }
        val daySnapshot = session.daySnapshot ?: (s.plan?.takeIf { it.mode == PlanMode.COURSE } ?: s.dailyPlans[s.exam])
            ?.days?.firstOrNull { it.dayNumber == session.courseDay }
        val stopped = session.copy(finished = true, stoppedEarly = true,
            activities = retained,
            daySnapshot = daySnapshot?.let { day ->
                day.copy(activities = retained, exerciseIds = retained.map { it.exerciseId }.distinct(),
                    skillIds = retained.map { it.skillId }.distinct(), isReview = retained.any { it.kind == ActivityKind.REVIEW })
            })
        completeSession(stopped, draftUpdates(session, draft))
        return true
    }

    private fun resolveDraft(key: String, drafts: Map<String, StudyDraft>): StudyDraft? {
        var current = drafts[key] ?: return null
        val visited = mutableSetOf<String>()
        while (current.supersededByWorkId != null) {
            if (!visited.add(current.key)) return null
            current = drafts[current.copy(workId = current.supersededByWorkId).key] ?: return null
        }
        return current.takeUnless { it.submitted }
    }

    private suspend fun exerciseForDraft(draft: StudyDraft): Exercise? {
        fun Exercise.matches() = exam == draft.exam && id == draft.exerciseId && version == draft.exerciseVersion
        val s = state.value
        s.pack?.exercises?.firstOrNull { it.matches() }?.let { return it }
        s.sessions.values.asSequence().flatMap { it.exercises.asSequence() }.firstOrNull { it.matches() }?.let { return it }
        (s.plans.values + s.dailyPlans.values).asSequence().flatMap { it.days.asSequence() }
            .flatMap { it.activities.asSequence() }.mapNotNull { it.exerciseSnapshot }.firstOrNull { it.matches() }?.let { return it }
        // Read one bounded pack at a time; a newer prompt must never replace the
        // version that the saved response actually answered.
        for (header in database.dao().packHeaders().sortedByDescending { it.version }) {
            val archived = database.dao().pack(header.id, header.version) ?: continue
            val exercise = withContext(Dispatchers.Default) {
                storageJson.decodeFromString<ContentPack>(archived.payload).exercises.firstOrNull { it.matches() }
            }
            if (exercise != null) return exercise
        }
        return null
    }

    /** True means the request was queued. Loading stays visible until its exact
     * exercise version, migrated alias and restored session are durably ready. */
    fun resumeDraft(key: String): Boolean {
        val initial = state.value
        if (initial.loading || initial.session?.let { !it.finished } == true || resumingDraftKey != null || recordingBlocksStop()) return false
        val requested = resolveDraft(key, initial.drafts)?.takeIf { it.exam == initial.exam } ?: return false
        val exam = initial.exam
        resumingDraftKey = key
        mutableState.update { it.copy(loading = true, error = null) }
        enqueue {
            val s = state.value
            val draft = resolveDraft(key, s.drafts)
            val exercise = draft?.let { exerciseForDraft(it) }
            val allowed = exercise != null && (s.plan?.mode != PlanMode.INTENSIVE ||
                exercise.split == ContentSplit.DIAGNOSTIC || exercise.skillId in s.plan!!.prioritySkillIds.take(3))
            if (resumingDraftKey != key || s.exam != exam || s.session?.let { !it.finished } == true ||
                draft == null || draft.key != requested.key || !allowed) {
                resumingDraftKey = null
                mutableState.update { it.copy(loading = false, error = if (it.language == Language.RU)
                    "Не удалось открыть этот черновик. Проверьте раздел, активное занятие и приоритеты интенсива. Исходные данные сохранены."
                    else "This draft could not be opened. Check the exam, active session and intensive priorities. The saved work is preserved.") }
                return@enqueue
            }
            requireNotNull(draft); requireNotNull(exercise)
            val workId = draft.workId ?: "draft-${UUID.randomUUID()}"
            val restored = StudySession(exam = exam, mode = exercise.split, exercises = listOf(exercise),
                draft = draft.text, hintsUsed = draft.hintsUsed, lessonSeen = true,
                recordingPath = draft.recordingPath, recordingPaths = (draft.recordingPaths + listOfNotNull(draft.recordingPath)).distinct(),
                previousWorkSeconds = draft.elapsedSeconds, manualWorkId = workId, resumingDraft = true, startedAt = now(),
                timeLimitSeconds = draft.timeLimitSeconds, continuedWithoutTimeLimit = draft.continuedWithoutTimeLimit,
                reviewGuidanceViewed = draft.reviewGuidanceViewed)
            val migrated = draft.copy(workId = workId)
            val updates = listOfNotNull(draft.takeIf { it.workId == null }?.copy(supersededByWorkId = workId), migrated)
            database.withTransaction {
                updates.forEach { database.dao().put(StoredRecord("draft:${it.key}", "draft", exam.name, storageJson.encodeToString(it))) }
                database.dao().put(StoredRecord("session:$exam", "session", exam.name, storageJson.encodeToString(restored)))
            }
            resumingDraftKey = null
            mutableState.update { it.copy(loading = false, sessions = it.sessions + (exam to restored),
                drafts = it.drafts + updates.associateBy { item -> item.key }) }
        }
        return true
    }

    fun startSession(mode: ContentSplit, skillId: String? = null) {
        val s = state.value
        if (s.loading) return
        val pack = s.pack ?: return
        if (s.session?.let { !it.finished } == true) return
        val exercises = when (mode) {
            ContentSplit.DIAGNOSTIC -> StudyPlanner.diagnostic(pack, s.exam, s.attempts)
            ContentSplit.PRACTICE -> StudyPlanner.nextPractice(
                if (skillId == null) pack else pack.copy(exercises = pack.exercises.filter { it.skillId == skillId }),
                s.exam, s.skillStates, s.attempts, today(), limit = 12,
            )
            ContentSplit.ASSESSMENT -> StudyPlanner.assessment(pack, s.exam, s.attempts, limit = 64)
        }.filter { exercise -> s.plan?.mode != PlanMode.INTENSIVE || mode == ContentSplit.DIAGNOSTIC || exercise.skillId in s.plan!!.prioritySkillIds }
            .take(if (mode == ContentSplit.PRACTICE) 6 else if (mode == ContentSplit.ASSESSMENT) 8 else 16)
        if (exercises.isEmpty()) {
            mutableState.update { it.copy(error = if (s.language == Language.RU) "Новых заданий этого раздела в пилотном пакете больше нет. Можно повторить практику или импортировать новый пакет." else "No new items remain in this pilot section. Review practice or import an updated package.") }
            return
        }
        saveSession(restoreStep(StudySession(exam = s.exam, mode = mode, exercises = exercises, lessonSeen = mode != ContentSplit.PRACTICE)))
    }
    fun startPlannedDay(dayNumber: Int) {
        val s = state.value
        if (s.loading) return
        if (s.session?.let { !it.finished } == true) return
        val plan = s.plan?.takeIf { it.mode == PlanMode.COURSE } ?: return
        val completed = s.courseProgress[s.exam]?.takeIf { it.planId == plan.id }?.completedDays.orEmpty()
        val nextDay = plan.days.firstOrNull { it.dayNumber !in completed }?.dayNumber ?: return
        if (dayNumber != nextDay) {
            mutableState.update { it.copy(error = if (s.language == Language.RU) "Сначала завершите день $nextDay. Отдельные темы доступны в библиотеке." else "Finish day $nextDay first. Individual topics remain available in the library.") }
            return
        }
        val day = plan.days.firstOrNull { it.dayNumber == dayNumber } ?: return
        val referenced = if (day.activities.isEmpty()) day.exerciseIds.mapNotNull { id -> s.pack?.exercises?.find { it.id == id } }
            else day.activities.mapNotNull { it.exerciseSnapshot ?: s.pack?.exercises?.find { exercise -> exercise.id == it.exerciseId && (it.exerciseVersion == null || exercise.version == it.exerciseVersion) } }
        val continuing = day.activities.filter { it.continuation || it.kind == ActivityKind.REVIEW }.map { it.exerciseId to it.exerciseVersion }.toSet()
        val exercises = referenced.distinctBy { it.id to it.version }.filter {
            it.split != ContentSplit.ASSESSMENT || (it.id to it.version) in continuing || !StudyPlanner.isFamiliar(it, s.pack!!, s.attempts)
        }
        val eligible = exercises.map { it.id to it.version }.toSet()
        val activities = day.activities.filter { activity -> eligible.any { (id, version) -> id == activity.exerciseId && (activity.exerciseVersion == null || version == activity.exerciseVersion) } }
        if (exercises.isEmpty()) { mutableState.update { it.copy(error = if (s.language == Language.RU) "Для этого дня нет подходящих новых материалов. Обновите пакет или выберите практику в библиотеке." else "No suitable fresh material remains for this day. Update the package or use the library.") }; return }
        val mode = exercises.first().split
        saveSession(restoreStep(StudySession(exam = s.exam, mode = mode, exercises = exercises,
            courseDay = dayNumber, coursePlanId = plan.id, daySnapshot = day, activities = activities,
            lessonSnapshots = s.pack!!.lessons.filter { lesson -> activities.any { it.lessonId == lesson.id } },
        )))
    }
    fun draft(text: String, expectedStep: String? = null) {
        val session = state.value.session ?: return
        if (expectedStep != null && expectedStep != session.stepKey) return
        if (session.result == null && !session.finished && !session.answerLockedByTimeLimit && session.draft != text) saveSession(session.copy(draft = text))
    }
    fun applyTranscript(text: String, expectedStep: String? = null) {
        val session = state.value.session ?: return
        if (expectedStep != null && session.stepKey != expectedStep) return
        if (session.result == null && !session.finished && !session.answerLockedByTimeLimit) saveSession(session.copy(draft = text, draftRevision = session.draftRevision + 1))
    }
    fun tick(expectedStep: String? = null, currentAnswer: String? = null, expectedDraftRevision: Int? = null) {
        val session = state.value.session ?: return
        if (expectedStep != null && session.stepKey != expectedStep) return
        if (state.value.loading || !session.lessonSeen || session.result != null || session.finished || session.answerLockedByTimeLimit) return
        val next = session.copy(activeSeconds = session.activeSeconds + 1)
        // Freeze the latest editor text at expiry and recreate its local text state once.
        // A callback from before an applied transcript must never overwrite that revision.
        saveSession(if (next.answerLockedByTimeLimit) next.copy(
            draft = currentAnswer?.takeIf { expectedDraftRevision == session.draftRevision } ?: session.draft,
            draftRevision = next.draftRevision + 1,
        ) else next)
    }
    fun continueWithoutTimeLimit(expectedStep: String) {
        val session = state.value.session ?: return
        if (state.value.loading || session.finished || session.result != null || session.stepKey != expectedStep || !session.answerLockedByTimeLimit) return
        saveSession(session.copy(continuedWithoutTimeLimit = true))
    }
    fun hint() {
        val session = state.value.session ?: return
        val exercise = session.exercise ?: return
        if (!state.value.loading && !session.finished && !session.answerLockedByTimeLimit && session.mode == ContentSplit.PRACTICE && session.result == null) saveSession(session.copy(hintsUsed = (session.hintsUsed + 1).coerceAtMost(exercise.hints.size)))
    }
    fun reviewGuidance(expectedStep: String) {
        val s = state.value
        val session = s.session ?: return
        val exercise = session.exercise ?: return
        val pack = s.pack ?: return
        if (s.loading || session.finished || !session.lessonSeen || session.activity?.isLesson == true || session.result != null || session.mode != ContentSplit.PRACTICE ||
            session.stepKey != expectedStep || session.answerLockedByTimeLimit || session.reviewGuidanceViewed) return
        if (StudyPlanner.practiceChecks(pack, exercise, s.attempts).isEmpty()) return
        saveSession(session.copy(reviewGuidanceViewed = true))
    }
    fun lessonSeen(expectedStep: String? = null) {
        val session = state.value.session ?: return
        if (state.value.loading || session.finished) return
        if (expectedStep != null && expectedStep != session.stepKey) return
        if (session.activity?.isLesson == true) {
            val activity = session.activity!!
            if (activity.remainingMinutes > 0) {
                val done = activity.copy(remainingMinutes = 0)
                saveSession(session.copy(activities = session.activities.map { if (it.id == activity.id) done else it },
                    daySnapshot = session.daySnapshot?.let { day -> day.copy(activities = day.activities.map { if (it.id == activity.id) done else it }) }))
            }
            advanceSession()
        } else saveSession(session.copy(lessonSeen = true, startedAt = now()))
    }
    fun checkpoint(expectedStep: String) {
        val session = state.value.session ?: return
        if (state.value.loading || session.finished) return
        if (session.stepKey != expectedStep || session.result != null || session.activity?.remainingMinutes?.let { it > 0 } != true) return
        advanceSession()
    }
    fun attachRecording(path: String, expectedStep: String? = null) {
        val session = state.value.session ?: return
        if (session.finished || session.result != null || expectedStep != null && expectedStep != session.stepKey) return
        saveSession(session.copy(recordingPath = path, recordingPaths = (session.recordingPaths + listOfNotNull(session.recordingPath) + path).distinct()))
    }
    fun submit(skip: Boolean = false) {
        val s = state.value
        val session = s.session ?: return
        val exercise = session.exercise ?: return
        if (session.result != null || session.finished) return
        if (runtime.recorder.state.value is com.tomilov.stylishsat.speech.RecorderState.RecordingAudio) {
            mutableState.update { it.copy(error = if (s.language == Language.RU) "Остановите запись перед отправкой ответа." else "Stop recording before submitting the answer.") }
            return
        }
        val result = if (skip) AnswerResult(AnswerStatus.NEEDS_REVIEW, null, LocalizedText("Skipped. This skill needs more evidence.", "Пропущено. Для этого навыка нужно больше наблюдений.")) else AnswerChecker.check(exercise, session.draft)
        if (result.status == AnswerStatus.INVALID) {
            mutableState.update { it.copy(error = result.message.text(s.language)) }; return
        }
        val now = now()
        val attempt = Attempt(
            id = UUID.randomUUID().toString(), exerciseId = exercise.id, exerciseVersion = exercise.version,
            exam = exercise.exam, skillId = exercise.skillId, answer = session.draft, correct = result.correct,
            timestampEpochMillis = now, elapsedSeconds = session.previousWorkSeconds + session.activeSeconds,
            hintsUsed = session.hintsUsed + if (session.reviewGuidanceViewed) 1 else 0,
            isRepeat = StudyPlanner.isFamiliar(exercise, s.pack!!, s.attempts),
            errorType = if (skip) "SKIPPED" else result.errorType,
            difficulty = exercise.difficulty, split = exercise.split, expectedSeconds = exercise.expectedSeconds, familyId = exercise.familyId, sourceId = exercise.sourceId, recordingPath = session.recordingPath, localDateEpochDay = today(), workId = session.workId,
            recordingPaths = session.recordingPaths.ifEmpty { listOfNotNull(session.recordingPath) },
            timeLimitSeconds = session.timeLimitSeconds, continuedWithoutTimeLimit = session.continuedWithoutTimeLimit,
        )
        val completedActivity = session.activity?.copy(remainingMinutes = 0)
        val savedSession = session.copy(result = result,
            activities = session.activities.map { if (it.id == completedActivity?.id) completedActivity else it },
            daySnapshot = session.daySnapshot?.let { day -> day.copy(activities = day.activities.map { if (it.id == completedActivity?.id) completedActivity else it }) },
        )
        val draft = StudyDraft(s.exam, exercise.id, exercise.version, session.draft, session.workId,
            session.previousWorkSeconds + session.activeSeconds, session.hintsUsed, session.recordingPath, submitted = true, updatedAt = now(), recordingPaths = session.recordingPaths.ifEmpty { listOfNotNull(session.recordingPath) },
            timeLimitSeconds = session.timeLimitSeconds, continuedWithoutTimeLimit = session.continuedWithoutTimeLimit,
            reviewGuidanceViewed = session.reviewGuidanceViewed)
        mutableState.update { it.copy(attempts = it.attempts + attempt, sessions = it.sessions + (s.exam to savedSession), drafts = it.drafts + (draft.key to draft)) }
        enqueue {
            database.withTransaction {
                database.dao().put(StoredRecord("attempt:${attempt.id}", "attempt", s.exam.name, storageJson.encodeToString(attempt)))
                database.dao().put(StoredRecord("draft:${draft.key}", "draft", s.exam.name, storageJson.encodeToString(draft)))
                database.dao().put(StoredRecord("session:${s.exam}", "session", s.exam.name, storageJson.encodeToString(savedSession)))
            }
        }
    }
    fun next() {
        if (state.value.session?.result == null) return
        advanceSession()
    }
    private fun advanceSession() {
        val s = state.value
        val session = s.session ?: return
        if (session.finished) return
        if (session.index + 1 < session.stepCount) {
            saveSession(restoreStep(session.copy(index = session.index + 1, draft = "", hintsUsed = 0, activeSeconds = 0, previousWorkSeconds = 0, manualWorkId = null, resumingDraft = false,
                startedAt = now(), result = null, lessonSeen = session.mode != ContentSplit.PRACTICE, recordingPath = null, recordingPaths = emptyList(), externalFeedback = "")))
            return
        }
        completeSession(session.copy(finished = true))
    }
    private fun completeSession(finished: StudySession, savedDrafts: List<StudyDraft> = emptyList()) {
        val s = state.value
        val session = finished
        val profile = if (session.mode == ContentSplit.DIAGNOSTIC) s.profile.copy(diagnosticCompleted = StudyPlanner.diagnosticComplete(s.pack!!, s.exam, s.attempts)) else s.profile
        val currentCourse = s.plan?.takeIf { it.mode == PlanMode.COURSE } ?: s.dailyPlans[s.exam]
        val oldCourse = session.daySnapshot?.let { completedDay ->
            currentCourse?.takeIf { session.coursePlanId == null || it.id == session.coursePlanId }
                ?.copy(days = currentCourse.days.map { if (it.dayNumber == completedDay.dayNumber) completedDay else it })
        } ?: currentCourse
        val progress = session.courseDay?.let { day ->
            oldCourse?.let { course ->
                val previous = s.courseProgress[s.exam]?.takeIf { it.planId == course.id } ?: CourseProgress(s.exam, course.id)
                previous.copy(completedDays = (previous.completedDays + day).distinct())
            }
        } ?: s.courseProgress[s.exam]
        val plan = if (s.plan?.mode == PlanMode.INTENSIVE) {
            val intensive = s.plan!!
            if (session.mode == ContentSplit.DIAGNOSTIC) {
                val priorities = StudyPlanner.intensive(s.pack!!, s.exam, s.skillStates, today(), 4).prioritySkillIds
                intensive.copy(prioritySkillIds = priorities, blocks = intensive.blocks.map { block -> block.copy(
                    skillIds = if (block.type == PlanBlockType.BREAK) emptyList() else priorities,
                    completed = block.completed || block.type == PlanBlockType.DIAGNOSTIC && profile.diagnosticCompleted,
                ) })
            } else intensive
        } else {
            val fresh = StudyPlanner.course(s.pack!!, s.exam, s.skillStates, s.attempts, oldCourse?.createdEpochDay ?: today(), profile.dailyMinutes, completedDays = oldCourse?.days?.filter { progress?.planId == oldCourse.id && it.dayNumber in progress.completedDays }.orEmpty())
            fresh.copy(days = fresh.days.map { day ->
                if (progress?.planId == fresh.id && day.dayNumber in progress.completedDays) oldCourse?.days?.find { it.dayNumber == day.dayNumber } ?: day else day
            })
        }
        val savedCourse = if (plan.mode == PlanMode.COURSE) plan else oldCourse
        mutableState.update { it.copy(
            sessions = it.sessions + (s.exam to finished), profiles = it.profiles + (s.exam to profile), plans = it.plans + (s.exam to plan),
            drafts = it.drafts + savedDrafts.associateBy { draft -> draft.key },
            courseProgress = if (progress == null) it.courseProgress else it.courseProgress + (s.exam to progress),
            dailyPlans = if (savedCourse != null) it.dailyPlans + (s.exam to savedCourse) else it.dailyPlans,
        ) }
        enqueue {
            val records = withContext(Dispatchers.Default) {
                val planJson = storageJson.encodeToString(plan)
                mutableListOf(
                    StoredRecord("session:${s.exam}", "session", s.exam.name, storageJson.encodeToString(finished)),
                    StoredRecord("profile:${s.exam}", "profile", s.exam.name, storageJson.encodeToString(profile)),
                    StoredRecord("plan:${s.exam}", "plan", s.exam.name, planJson),
                ).apply {
                    savedDrafts.forEach { add(StoredRecord("draft:${it.key}", "draft", s.exam.name, storageJson.encodeToString(it))) }
                    progress?.let { add(StoredRecord("course_progress:${s.exam}", "course_progress", s.exam.name, storageJson.encodeToString(it))) }
                    savedCourse?.let { add(StoredRecord("course_plan:${s.exam}", "course_plan", s.exam.name,
                        if (it === plan) planJson else storageJson.encodeToString(it))) }
                }
            }
            database.withTransaction { records.forEach { database.dao().put(it) } }
        }
    }
    fun makePlan(hours: Int? = null) {
        var s = state.value
        if (s.loading) return
        val pack = s.pack ?: return
        if (hours == null && s.session?.let { !it.finished && it.courseDay != null } == true && s.plan?.mode == PlanMode.COURSE) return
        val requestedMode = if (hours == null) PlanMode.COURSE else PlanMode.INTENSIVE
        if (s.session?.let { !it.finished } == true && (hours != null || s.plan?.mode != requestedMode)) {
            if (!finishForNow()) return
            s = state.value
        }
        val oldCourse = s.plan?.takeIf { it.mode == PlanMode.COURSE } ?: s.dailyPlans[s.exam]
        val completion = s.courseProgress[s.exam]?.takeIf { it.planId == oldCourse?.id }
        val plan = if (hours == null) StudyPlanner.course(pack, s.exam, s.skillStates, s.attempts, oldCourse?.createdEpochDay ?: today(), s.profile.dailyMinutes, completedDays = oldCourse?.days?.filter { it.dayNumber in completion?.completedDays.orEmpty() }.orEmpty())
        else StudyPlanner.intensive(pack, s.exam, s.skillStates, today(), hours)
        if (plan.mode == PlanMode.COURSE) {
            mutableState.update { it.copy(dailyPlans = it.dailyPlans + (s.exam to plan)) }
            persist("course_plan:${s.exam}", "course_plan", s.exam, plan)
        }
        mutableState.update { it.copy(plans = it.plans + (s.exam to plan)) }
        persist("plan:${s.exam}", "plan", s.exam, plan)
    }
    fun recalculate(minutes: Int) {
        val s = state.value
        if (s.loading) return
        val plan = s.plan ?: return
        if (plan.mode != PlanMode.INTENSIVE || plan.blocks.all { it.completed }) return
        val updated = StudyPlanner.recalculateIntensive(plan, minutes.coerceAtLeast(0))
        mutableState.update { it.copy(plans = it.plans + (s.exam to updated)) }
        persist("plan:${s.exam}", "plan", s.exam, updated)
    }
    fun completeBlock(id: String) {
        var s = state.value
        if (s.loading) return
        var plan = s.plan ?: return
        val block = plan.blocks.firstOrNull { it.id == id } ?: return
        val session = s.session?.takeUnless { it.finished }
        val matchingMode = when (block.type) {
            PlanBlockType.DIAGNOSTIC -> ContentSplit.DIAGNOSTIC
            PlanBlockType.LESSON_PRACTICE, PlanBlockType.REVIEW -> ContentSplit.PRACTICE
            PlanBlockType.TIMED_CHECK -> ContentSplit.ASSESSMENT
            else -> null
        }
        if (plan.mode == PlanMode.INTENSIVE && !block.completed && session?.mode == matchingMode &&
            session != null && (matchingMode == ContentSplit.DIAGNOSTIC || session.exercise?.skillId in block.skillIds)) {
            if (!finishForNow()) return
            s = state.value
            plan = s.plan ?: return
        }
        val updated = plan.copy(blocks = plan.blocks.map { if (it.id == id) it.copy(completed = !block.completed) else it })
        mutableState.update { it.copy(plans = it.plans + (s.exam to updated)) }
        persist("plan:${s.exam}", "plan", s.exam, updated)
    }
    fun saveFeedback(text: String, source: String = "EXTERNAL_CHATGPT", expectedStep: String? = null) {
        val s = state.value
        if (s.loading) return
        val session = s.session ?: return
        if (expectedStep != null && session.stepKey != expectedStep) return
        val exercise = session.exercise ?: return
        if (text.isBlank()) return
        val attempt = s.attempts.lastOrNull { it.exerciseId == exercise.id && it.exerciseVersion == exercise.version && it.exam == s.exam }
        val feedback = Feedback(UUID.randomUUID().toString(), exercise.id, text, source, now(), exerciseVersion = exercise.version, attemptId = attempt?.id, exam = s.exam)
        mutableState.update { it.copy(feedback = it.feedback + feedback) }
        persist("feedback:${feedback.id}", "feedback", s.exam, feedback)
        saveSession(session.copy(externalFeedback = text))
    }
    fun importContent(uri: Uri) {
        viewModelScope.launch {
            try {
                val raw = withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri)!!.use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (true) {
                            val size = stream.read(buffer)
                            if (size < 0) break
                            require(output.size() + size <= 16 * 1024 * 1024) { "Package exceeds 16 MB" }
                            output.write(buffer, 0, size)
                        }
                        output.toString("UTF-8")
                    }
                }
                val pack = contentRepository.importPack(raw)
                mutableState.update { it.copy(pack = pack, error = if (it.language == Language.RU) "Пакет обновлён. История сохранена." else "Package updated. History preserved.") }
                Exam.entries.forEach { refreshStoredCourse(it, force = true) }
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) { mutableState.update { it.copy(error = e.message ?: "Invalid package") } }
        }
    }
}
