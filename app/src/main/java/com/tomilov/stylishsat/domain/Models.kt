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

@Serializable
data class ChartData(
    val title: String,
    val xLabel: String,
    val yLabel: String,
    val unit: String,
    val labels: List<String>,
    val series: List<ChartSeries>,
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
)

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
