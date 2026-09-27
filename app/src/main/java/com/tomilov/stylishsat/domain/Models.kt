package com.tomilov.stylishsat.domain

import kotlinx.serialization.Serializable

@Serializable enum class Exam { SAT, IELTS }
@Serializable enum class Language { EN, RU }
@Serializable enum class ContentSplit { DIAGNOSTIC, PRACTICE, ASSESSMENT }
@Serializable enum class ExerciseType { MULTIPLE_CHOICE, NUMERIC, SHORT_ANSWER, WRITING, SPEAKING }
@Serializable enum class AnswerStatus { CORRECT, INCORRECT, INVALID, NEEDS_REVIEW }
@Serializable enum class ActivityKind { LESSON, REVIEW, PRACTICE, ASSESSMENT }
@Serializable enum class PlanMode { COURSE, INTENSIVE }
@Serializable enum class PlanBlockType { DIAGNOSTIC, LESSON_PRACTICE, BREAK, TIMED_CHECK, REVIEW, CHECKLIST }

@Serializable
data class LocalizedText(val en: String = "", val ru: String = "") {
    fun text(language: Language): String = if (language == Language.RU) ru.ifBlank { en } else en.ifBlank { ru }
}

@Serializable
data class Skill(
    val id: String,
    val exam: Exam,
    val title: LocalizedText,
    val section: String,
    val description: LocalizedText = LocalizedText(),
    val prerequisites: List<String> = emptyList(),
)

@Serializable
data class Lesson(
    val id: String,
    val skillId: String,
    val title: LocalizedText,
    val body: LocalizedText,
    val workedExample: LocalizedText,
    val estimatedMinutes: Int = 8,
)

@Serializable
data class TranscriptSegment(val startMs: Long, val endMs: Long, val text: String)

@Serializable
data class ChartSeries(val name: String, val values: List<Double>)

/** Schema 3 adds line, pie and table views of the same labelled series. */
@Serializable enum class ChartKind { BAR, LINE, PIE, TABLE }

@Serializable
data class ChartData(
    val title: String,
    val xLabel: String,
    val yLabel: String,
    val unit: String,
    val labels: List<String>,
    val series: List<ChartSeries>,
    val kind: ChartKind = ChartKind.BAR,
)

/** Question formats name the task a learner sees; marking still follows [ExerciseType]. */
@Serializable enum class QuestionFormat {
    TRUE_FALSE_NOT_GIVEN, YES_NO_NOT_GIVEN, MATCHING_HEADINGS, MATCHING_INFORMATION, MATCHING_FEATURES,
    SUMMARY_COMPLETION, SENTENCE_COMPLETION, NOTE_COMPLETION, TABLE_COMPLETION, DIAGRAM_LABEL, MAP_LABEL,
    MULTIPLE_CHOICE, SHORT_ANSWER,
}

@Serializable enum class FigureKind { PROCESS, MAP, DIAGRAM }
@Serializable enum class NodeShape { BOX, ROUND, LABEL }

/** Positions are fractions of the panel, so a figure scales without a bitmap. */
@Serializable
data class FigureNode(
    val id: String,
    val label: String,
    val x: Float,
    val y: Float,
    val width: Float = 0.24f,
    val height: Float = 0.14f,
    val shape: NodeShape = NodeShape.BOX,
)

@Serializable data class FigureLink(val from: String, val to: String, val label: String = "")
@Serializable data class FigurePanel(val title: String = "", val nodes: List<FigureNode>, val links: List<FigureLink> = emptyList())
@Serializable data class Figure(val kind: FigureKind, val title: String, val panels: List<FigurePanel>, val caption: String = "")

@Serializable data class GroupOption(val key: String, val text: String)

/** What a task check asks the learner to confirm about their own response. */
@Serializable enum class CheckKind { TASK_PART, VIEW, POSITION, SUPPORT, OVERVIEW, COMPARISON, DATA, ACCURACY }

/** One yes/no question about the learner's response to this exact task, e.g. "Did you explain view B?". */
@Serializable data class TaskCheck(val id: String, val kind: CheckKind, val text: LocalizedText)

/** Shared context for several questions: an instruction, a lettered list, a gapped text or a figure.
 * Gaps and figure labels refer to member questions as [[exercise-id]]. */
@Serializable
data class QuestionGroup(
    val id: String,
    val instruction: String,
    val options: List<GroupOption> = emptyList(),
    val text: String? = null,
    val figure: Figure? = null,
)

@Serializable
data class Exercise(
    val id: String,
    val version: Int = 1,
    val exam: Exam,
    val skillId: String,
    val difficulty: Int = 1,
    val split: ContentSplit,
    val familyId: String,
    val sourceId: String = familyId,
    val type: ExerciseType,
    val prompt: String,
    val passage: String? = null,
    val options: List<String> = emptyList(),
    val acceptedAnswers: List<String> = emptyList(),
    val hints: List<LocalizedText> = emptyList(),
    val explanation: LocalizedText = LocalizedText(),
    val typicalErrors: List<LocalizedText> = emptyList(),
    val expectedSeconds: Int = 90,
    val wordLimit: Int? = null,
    val minWords: Int? = null,
    val author: String,
    val audioAssetPath: String? = null,
    val transcript: String? = null,
    val transcriptSegments: List<TranscriptSegment> = emptyList(),
    val evidence: String? = null,
    val criteria: List<LocalizedText> = emptyList(),
    val sampleAnswer: String? = null,
    val chart: ChartData? = null,
    val sampleAudioAssetPath: String? = null,
    val format: QuestionFormat? = null,
    val group: QuestionGroup? = null,
    val figure: Figure? = null,
    val sourceTitle: String? = null,
    val taskChecklist: List<TaskCheck> = emptyList(),
) {
    /** Exact identity of the answered version. */
    val versionKey: String get() = "$id@$version"
}

@Serializable
data class ContentPack(
    val schemaVersion: Int = 1,
    val id: String,
    val version: Int,
    val title: LocalizedText,
    val skills: List<Skill>,
    val lessons: List<Lesson>,
    val exercises: List<Exercise>,
    val reviewStatus: String = "AI_DRAFT_MACHINE_VALIDATED",
)

@Serializable
data class ExamProfile(
    val exam: Exam,
    val target: String = "",
    val examDateEpochDay: Long? = null,
    val knownResult: String = "",
    val dailyMinutes: Int = 30,
    val intensiveHours: Int = 4,
    val diagnosticCompleted: Boolean = false,
)

@Serializable
data class LearnerProfile(
    val id: String = "local",
    val selectedExam: Exam = Exam.SAT,
    val language: Language = Language.RU,
    val examProfiles: List<ExamProfile> = Exam.entries.map { ExamProfile(it) },
)

@Serializable
data class Attempt(
    val id: String,
    val exerciseId: String,
    val exerciseVersion: Int,
    val exam: Exam,
    val skillId: String,
    val answer: String,
    val correct: Boolean?,
    val timestampEpochMillis: Long,
    val elapsedSeconds: Int = 0,
    val hintsUsed: Int = 0,
    val isRepeat: Boolean = false,
    val errorType: String? = null,
    val difficulty: Int = 1,
    val split: ContentSplit = ContentSplit.PRACTICE,
    val familyId: String? = null,
    val sourceId: String? = null,
    val expectedSeconds: Int = 0,
    val recordingPath: String? = null,
    val localDateEpochDay: Long? = null,
    val workId: String? = null,
    val recordingPaths: List<String> = emptyList(),
    val timeLimitSeconds: Int? = null,
    val continuedWithoutTimeLimit: Boolean = false,
    /** Attempts answered together in one section or exam sitting; siblings in a run are not repeats of each other. */
    val runId: String? = null,
) {
    val independent: Boolean get() = !isRepeat && hintsUsed == 0 && correct != null
}

@Serializable
data class SkillState(
    val skillId: String,
    val difficulty: Int = 1,
    val attemptsCount: Int = 0,
    val correctCount: Int = 0,
    val independentCount: Int = 0,
    val independentCorrectCount: Int = 0,
    val consecutiveErrors: Int = 0,
    val recentIndependent: List<Boolean> = emptyList(),
    val reviewStep: Int = 0,
    val nextReviewEpochDay: Long? = null,
    val totalElapsedSeconds: Long = 0,
    val totalExpectedSeconds: Long = 0,
    val lastReviewEpochDay: Long? = null,
) {
    val accuracy: Float get() = if (attemptsCount == 0) 0f else correctCount.toFloat() / attemptsCount
    val independence: Float get() = if (attemptsCount == 0) 0f else independentCount.toFloat() / attemptsCount
    val mastered: Boolean get() = difficulty == 3 && independentCorrectCount >= 6 && consecutiveErrors == 0
}

@Serializable
data class AnswerResult(val status: AnswerStatus, val correct: Boolean?, val message: LocalizedText, val errorType: String? = null)

@Serializable
data class PlanBlock(
    val id: String,
    val type: PlanBlockType,
    val minutes: Int,
    val skillIds: List<String> = emptyList(),
    val completed: Boolean = false,
)

@Serializable
data class PlannedActivity(
    val id: String,
    val workId: String,
    val kind: ActivityKind,
    val skillId: String,
    val exerciseId: String,
    val minutes: Int,
    val lessonId: String? = null,
    val remainingMinutes: Int = 0,
    val continuation: Boolean = false,
    val exerciseVersion: Int? = null,
    val exerciseSnapshot: Exercise? = null,
) {
    val isLesson: Boolean get() = kind == ActivityKind.LESSON || kind == ActivityKind.REVIEW
}

@Serializable
data class PlanDay(
    val dayNumber: Int,
    val epochDay: Long,
    val minutes: Int,
    val skillIds: List<String>,
    val exerciseIds: List<String> = emptyList(),
    val isReview: Boolean = false,
    val activities: List<PlannedActivity> = emptyList(),
    val contentExhausted: Boolean = false,
)

@Serializable
data class StudyPlan(
    val id: String,
    val exam: Exam,
    val mode: PlanMode,
    val createdEpochDay: Long,
    val totalMinutes: Int,
    val prioritySkillIds: List<String>,
    val days: List<PlanDay> = emptyList(),
    val blocks: List<PlanBlock> = emptyList(),
    val contentVersion: Int? = null,
    val plannerVersion: Int = 0,
)

@Serializable
data class Feedback(
    val id: String,
    val exerciseId: String,
    val text: String,
    val source: String,
    val timestampEpochMillis: Long,
    val trainingOnly: Boolean = true,
    val exerciseVersion: Int? = null,
    val attemptId: String? = null,
    val exam: Exam? = null,
)
