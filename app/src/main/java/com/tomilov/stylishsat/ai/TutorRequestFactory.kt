package com.tomilov.stylishsat.ai

import com.tomilov.stylishsat.domain.Exercise
import com.tomilov.stylishsat.domain.ExerciseType
import com.tomilov.stylishsat.domain.Language
import com.tomilov.stylishsat.domain.AnswerChecker

/** Preserve the learner's complete work; extra source material competes for the remaining budget. */
object TutorRequestFactory {
    /** The same code that marks the answer supplies its verdict; model text cannot regrade it. */
    fun closedAuthorityPrefix(exercise: Exercise, answer: String): String {
        require(exercise.type != ExerciseType.WRITING && exercise.type != ExerciseType.SPEAKING)
        val checked = AnswerChecker.check(exercise, answer)
        return buildString {
            append("Application check: ${checked.status.name}. ${checked.message.en}\n")
            if (exercise.acceptedAnswers.isNotEmpty()) append("Prepared key: ${exercise.acceptedAnswers.joinToString(" / ")}\n")
        }
    }

    fun create(exercise: Exercise, answer: String, language: Language): TutorRequest {
        val open = exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING
        val chart = exercise.chart?.let { chart -> buildString {
            append("${chart.title}\n${chart.xLabel}; ${chart.yLabel}; unit: ${chart.unit}\n")
            chart.labels.forEachIndexed { index, label ->
                append(label)
                chart.series.forEach { series -> append("; ${series.name}=${series.values[index]}") }
                append('\n')
            }
        } }
        val authority = if (open) {
            // Full criterion details and sample annotations are supplemental, not the student's answer.
            // English criterion names keep the core small while the system sets the feedback language.
            exercise.criteria.joinToString("\n") { it.en.ifBlank { it.ru }.substringBefore(':') }
        } else buildString {
            append(closedAuthorityPrefix(exercise, answer))
            append(exercise.explanation.text(language))
            exercise.evidence?.let { append("\nEvidence: $it") }
        }
        val excerpts = buildList {
            exercise.passage?.let { add(TutorExcerpt("${exercise.sourceId}/passage", it)) }
            if (open) {
                exercise.criteria.forEachIndexed { index, criterion ->
                    add(TutorExcerpt("${exercise.id}/criterion-${index + 1}", criterion.en.ifBlank { criterion.ru }))
                }
                exercise.sampleAnswer?.let { sample ->
                    add(TutorExcerpt("${exercise.id}/illustrative-sample",
                        "Illustrative sample, distinct from the learner's work:\n$sample\n\nDraft sample annotation:\n${exercise.explanation.text(language)}"))
                }
            }
        }
        return TutorRequest(task = listOfNotNull(exercise.prompt, chart).joinToString("\n\n"),
            answer = answer, authoritativeExplanation = authority,
            language = if (language == Language.RU) "ru" else "en", excerpts = excerpts)
    }
}
