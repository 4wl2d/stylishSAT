package com.tomilov.stylishsat.domain

import kotlinx.serialization.Serializable

/** The learner's own judgement of one version against one task check. Never converted into a band or score. */
@Serializable enum class CheckMark { YES, PARTLY, NO }

/**
 * One saved version of a written response. Version 1 is the submitted answer; later versions are revisions.
 * A version that is not [saved] is the editable draft of the next version.
 */
@Serializable
data class WritingRevision(
    val exam: Exam,
    val exerciseId: String,
    val exerciseVersion: Int,
    val workId: String,
    val number: Int,
    val text: String,
    val attemptId: String? = null,
    val checks: Map<String, CheckMark> = emptyMap(),
    val plan: String = "",
    val saved: Boolean = true,
    val createdAt: Long,
    val updatedAt: Long = createdAt,
    val elapsedSeconds: Int = 0,
) {
    val id: String get() = revisionId(workId, number)
    companion object { fun revisionId(workId: String, number: Int) = "$workId#$number" }
}

object Revisions {
    /** All versions of one piece of work, oldest first. */
    fun thread(all: Collection<WritingRevision>, workId: String): List<WritingRevision> =
        all.filter { it.workId == workId }.sortedBy { it.number }

    fun saved(all: Collection<WritingRevision>, workId: String): List<WritingRevision> = thread(all, workId).filter { it.saved }

    fun draft(all: Collection<WritingRevision>, workId: String): WritingRevision? = thread(all, workId).lastOrNull { !it.saved }

    /** The submitted answer becomes version 1 of its thread. */
    fun original(attempt: Attempt): WritingRevision = WritingRevision(
        exam = attempt.exam, exerciseId = attempt.exerciseId, exerciseVersion = attempt.exerciseVersion,
        workId = attempt.workId ?: "attempt:${attempt.id}", number = 1, text = attempt.answer, attemptId = attempt.id,
        createdAt = attempt.timestampEpochMillis, elapsedSeconds = attempt.elapsedSeconds,
    )

    /**
     * The checklist written for the task. An older answered version has none of its own; it borrows the list of a
     * newer version only when the learner saw the same task (identical prompt and visual).
     */
    fun checklist(answered: Exercise, versions: Collection<Exercise>): List<TaskCheck> =
        answered.taskChecklist.ifEmpty {
            versions.filter { it.id == answered.id && it.version > answered.version && sameTask(it, answered) && it.taskChecklist.isNotEmpty() }
                .minByOrNull { it.version }?.taskChecklist.orEmpty()
        }

    fun sameTask(a: Exercise, b: Exercise): Boolean =
        a.prompt == b.prompt && a.chart == b.chart && a.figure == b.figure && a.minWords == b.minWords

    /** Items the learner marked as missing or partial: what the next version should fix first. */
    fun focus(checklist: List<TaskCheck>, marks: Map<String, CheckMark>): List<TaskCheck> =
        checklist.filter { marks[it.id] == CheckMark.NO || marks[it.id] == CheckMark.PARTLY }

    data class Counts(val yes: Int, val partly: Int, val no: Int, val open: Int)

    /** How many checks are marked each way. A progress view of self-checks, not a grade. */
    fun counts(checklist: List<TaskCheck>, marks: Map<String, CheckMark>): Counts = Counts(
        checklist.count { marks[it.id] == CheckMark.YES }, checklist.count { marks[it.id] == CheckMark.PARTLY },
        checklist.count { marks[it.id] == CheckMark.NO }, checklist.count { it.id !in marks },
    )
}
