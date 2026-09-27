package com.tomilov.stylishsat.domain

import kotlinx.serialization.Serializable

/** A sitting with several questions answered together: one source section, or an uncalibrated exam paper. */
@Serializable enum class PaperKind { SECTION, SAT, IELTS_READING, IELTS_LISTENING, IELTS_WRITING }

/** WORKING: answering. TRANSFER: recordings have finished and the check time runs. BREAK: between SAT sections. FINISHED: marked. */
@Serializable enum class PaperPhase { WORKING, TRANSFER, BREAK, FINISHED }

/** Which second module a first-module raw result led to. An uncalibrated practice rule, not the official routing. */
@Serializable enum class ModuleRoute { HIGHER, LOWER }

@Serializable
data class AudioProgress(val positionMs: Long = 0, val completed: Boolean = false, val started: Boolean = false)

/** Exercise snapshots keep the exact versions a sitting was built from, even after a content update. */
@Serializable
data class PaperPart(
    val id: String,
    val title: String,
    val exercises: List<Exercise>,
    val timeLimitSeconds: Int? = null,
    val transferSeconds: Int = 0,
    /** SAT modules show one question per screen with a navigator, flags and a review page. */
    val oneAtATime: Boolean = false,
    /** Exam stage such as RW1, MATH2, READING or TASK1. */
    val stage: String? = null,
    val route: ModuleRoute? = null,
    /** Questions short of the standard length because too few fresh items remained. */
    val shortfall: Int = 0,
    /** A break offered after this part, before the next one starts. */
    val breakAfterSeconds: Int = 0,
) {
    val audioAssetPath: String? get() = exercises.firstNotNullOfOrNull { it.audioAssetPath }
    val passage: String? get() = exercises.firstNotNullOfOrNull { it.passage }
    /** Recordings in play order; a listening paper plays each once. */
    val audioPaths: List<String> get() = exercises.mapNotNull { it.audioAssetPath }.distinct()
    val sourceIds: List<String> get() = exercises.map { it.sourceId }.distinct()
}

@Serializable
data class PaperRun(
    val id: String,
    val exam: Exam,
    val kind: PaperKind,
    /** Exam conditions: a clock for reading, one uninterrupted play for listening, no replay. */
    val strict: Boolean,
    val parts: List<PaperPart>,
    val partIndex: Int = 0,
    val phase: PaperPhase = PaperPhase.WORKING,
    /** Answers keyed by [Exercise.versionKey]. */
    val answers: Map<String, String> = emptyMap(),
    val elapsedSeconds: Map<String, Int> = emptyMap(),
    val transferElapsedSeconds: Map<String, Int> = emptyMap(),
    val audio: Map<String, AudioProgress> = emptyMap(),
    val submittedParts: List<String> = emptyList(),
    /** Parts where the learner chose to keep working after the clock; the time is kept, the answers still count. */
    val overtimeParts: List<String> = emptyList(),
    val startedAt: Long,
    val finishedAt: Long? = null,
    val abandoned: Boolean = false,
    val sourceSkillId: String? = null,
    val sourceId: String? = null,
    /** Current question in a one-at-a-time module. */
    val itemIndex: Int = 0,
    /** Questions marked for review, by [Exercise.versionKey]. */
    val flagged: List<String> = emptyList(),
    /** The module review page is open. */
    val reviewing: Boolean = false,
    /** SAT stages still to be built; a second module is chosen from the first module's raw result. */
    val plannedStages: List<String> = emptyList(),
    val breakElapsedSeconds: Int = 0,
) {
    val part: PaperPart? get() = parts.getOrNull(partIndex)
    val finished: Boolean get() = finishedAt != null
    val active: Boolean get() = !finished && !abandoned
    val exercises: List<Exercise> get() = parts.flatMap { it.exercises }
    fun elapsed(part: PaperPart): Int = elapsedSeconds[part.id] ?: 0
    fun transferElapsed(part: PaperPart): Int = transferElapsedSeconds[part.id] ?: 0
    /** Seconds left on the part's clock, negative once over; null when the part is untimed. */
    fun remaining(part: PaperPart): Int? = when {
        phase == PaperPhase.BREAK -> parts.getOrNull(partIndex - 1)?.breakAfterSeconds?.let { it - breakElapsedSeconds }
        phase == PaperPhase.TRANSFER && part.transferSeconds > 0 -> part.transferSeconds - transferElapsed(part)
        else -> part.timeLimitSeconds?.let { it - elapsed(part) }
    }
    fun timeUp(part: PaperPart): Boolean = phase != PaperPhase.BREAK && remaining(part)?.let { it <= 0 } == true && part.id !in overtimeParts
    fun answered(part: PaperPart): Int = part.exercises.count { answers[it.versionKey].orEmpty().isNotBlank() }
}

/** Exercise snapshots of a sitting, stored apart from its small, frequently saved state. */
@Serializable
data class PaperPartsRecord(val runId: String, val parts: List<PaperPart>)

/** Marking stays with [AnswerChecker]; a sitting only adds how blanks and malformed entries are recorded. */
object PaperScoring {
    data class Outcome(val exercise: Exercise, val answer: String, val correct: Boolean?, val errorType: String?, val status: AnswerStatus)

    data class Raw(val correct: Int, val closed: Int, val answered: Int, val open: Int) {
        val incorrect: Int get() = closed - correct
    }

    fun score(part: PaperPart, answers: Map<String, String>): List<Outcome> = part.exercises.map { exercise ->
        val answer = answers[exercise.versionKey].orEmpty()
        if (answer.isBlank()) {
            // A blank is not evidence of a skill either way, but it is not a correct answer in the raw count.
            Outcome(exercise, answer, null, "SKIPPED", AnswerStatus.NEEDS_REVIEW)
        } else {
            val result = AnswerChecker.check(exercise, answer)
            if (result.status == AnswerStatus.INVALID) Outcome(exercise, answer, false, result.errorType ?: "INVALID", AnswerStatus.INCORRECT)
            else Outcome(exercise, answer, result.correct, result.errorType, result.status)
        }
    }

    fun raw(outcomes: List<Outcome>): Raw {
        val closed = outcomes.filterNot { StudyPlanner.openResponse(it.exercise) }
        return Raw(closed.count { it.correct == true }, closed.size, outcomes.count { it.answer.isNotBlank() }, outcomes.size - closed.size)
    }

    /** Whole seconds shared across questions so that their sum equals the part's clock. */
    fun apportion(total: Int, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        val safe = total.coerceAtLeast(0)
        return List(count) { index -> safe / count + if (index < safe % count) 1 else 0 }
    }

    /** One attempt per question. Familiarity ignores the sitting's own answers, so a fresh passage stays independent evidence. */
    fun attempts(
        run: PaperRun, part: PaperPart, outcomes: List<Outcome>, pack: ContentPack, history: List<Attempt>,
        now: Long, today: Long, newId: () -> String,
    ): List<Attempt> {
        val seconds = apportion(run.elapsed(part) + run.transferElapsed(part), outcomes.size)
        val prior = history.filter { it.runId != run.id }
        return outcomes.mapIndexed { index, outcome ->
            val exercise = outcome.exercise
            Attempt(
                id = newId(), exerciseId = exercise.id, exerciseVersion = exercise.version, exam = exercise.exam,
                skillId = exercise.skillId, answer = outcome.answer, correct = outcome.correct, timestampEpochMillis = now,
                elapsedSeconds = seconds[index], hintsUsed = 0, isRepeat = StudyPlanner.isFamiliar(exercise, pack, prior),
                errorType = outcome.errorType, difficulty = exercise.difficulty, split = exercise.split,
                familyId = exercise.familyId, sourceId = exercise.sourceId, expectedSeconds = exercise.expectedSeconds,
                localDateEpochDay = today, workId = "${run.id}:${exercise.versionKey}", runId = run.id,
                timeLimitSeconds = part.timeLimitSeconds?.let { limit -> apportion(limit, outcomes.size)[index] },
                continuedWithoutTimeLimit = part.id in run.overtimeParts,
            )
        }
    }
}
