package com.tomilov.stylishsat.domain

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** The learner's own reading of why an answer went wrong. A label for reflection, never an input to marking. */
@Serializable enum class MistakeCause { MISREAD_QUESTION, MISSED_EVIDENCE, RULE_OR_CONCEPT, CALCULATION_OR_SLIP, ANSWER_FORM, TIME_PRESSURE, GUESSED }

/** Notes and a follow-up for one wrong or skipped attempt. The attempt itself is never changed. */
@Serializable
data class NotebookEntry(
    val attemptId: String,
    val exam: Exam,
    val exerciseId: String,
    val exerciseVersion: Int,
    val note: String = "",
    val cause: MistakeCause? = null,
    val resolved: Boolean = false,
    val queuedExerciseId: String? = null,
    val queuedExerciseVersion: Int? = null,
    val queuedFromFamily: Boolean = false,
    val queuedAt: Long? = null,
    val updatedAt: Long = 0,
)

object Notebook {
    /** Wrong, skipped and marked-for-review answers for one exam, newest first, one row per answered exercise version. */
    fun mistakes(attempts: List<Attempt>, exam: Exam, marked: Set<String> = emptySet()): List<Attempt> = attempts.asSequence()
        .filter { it.exam == exam && (it.correct == false || it.errorType == "SKIPPED" || it.workId?.let { work -> work in marked } == true) }
        .sortedWith(compareByDescending<Attempt> { it.timestampEpochMillis }.thenBy { it.id })
        .distinctBy { it.exerciseId to it.exerciseVersion }.toList()

    data class FreshItem(val exercise: Exercise, val fromFamily: Boolean)

    /**
     * A question the learner has not answered, preferably from the same problem family (the same passage, recording
     * or item template). Otherwise a fresh practice question in the same skill with the same format or listed slip.
     * Reserved full-length assessment passages are never used.
     */
    fun freshItem(pack: ContentPack, source: Exercise, attempts: List<Attempt>, alreadyQueued: Set<String> = emptySet()): FreshItem? {
        val answered = attempts.asSequence().filter { it.exam == source.exam }.map { it.exerciseId }.toSet()
        val reserved = StudyPlanner.paperSources(pack)
        val open = pack.exercises.filter { it.exam == source.exam && it.id != source.id && it.id !in answered && it.id !in alreadyQueued &&
            it.sourceId !in reserved && !StudyPlanner.openResponse(it) }
        val closeness = compareBy<Exercise>({ it.split != ContentSplit.PRACTICE }, { abs(it.difficulty - source.difficulty) }, { it.id })
        open.filter { it.familyId == source.familyId }.minWithOrNull(closeness)?.let { return FreshItem(it, fromFamily = true) }
        fun likeness(candidate: Exercise): Int = when {
            source.format != null && candidate.format == source.format -> 0
            source.typicalErrors.any { it in candidate.typicalErrors } -> 1
            candidate.type == source.type -> 2
            else -> 3
        }
        return open.filter { it.skillId == source.skillId && it.split == ContentSplit.PRACTICE && !StudyPlanner.isFamiliar(it, pack, attempts) }
            .minWithOrNull(compareBy<Exercise>(::likeness).then(closeness))?.let { FreshItem(it, fromFamily = false) }
    }

    /** Queued follow-ups not yet answered since they were queued. */
    fun pending(entries: Collection<NotebookEntry>, attempts: List<Attempt>, exam: Exam): List<NotebookEntry> = entries.filter { entry ->
        entry.exam == exam && entry.queuedExerciseId != null && attempts.none { attempt ->
            attempt.exam == exam && attempt.exerciseId == entry.queuedExerciseId && attempt.timestampEpochMillis >= (entry.queuedAt ?: 0)
        }
    }.sortedBy { it.queuedAt }
}
