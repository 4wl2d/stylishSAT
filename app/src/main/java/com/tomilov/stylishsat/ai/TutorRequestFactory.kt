package com.tomilov.stylishsat.ai

import com.tomilov.stylishsat.domain.ContentPackCodec
import com.tomilov.stylishsat.domain.Exercise
import com.tomilov.stylishsat.domain.Figure
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
        // Shared question context: a lettered list, a gapped summary or a figure described as text.
        val group = exercise.group?.let { group -> buildString {
            append(group.instruction)
            group.options.forEach { append("\n${it.key}: ${it.text}") }
            group.text?.let { append("\n\n").append(ContentPackCodec.fillGaps(it, mapOf(exercise.id to 1))) }
        } }
        val figure = listOfNotNull(exercise.figure, exercise.group?.figure).joinToString("\n\n") { describe(it, exercise.id) }.ifBlank { null }
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
        return TutorRequest(task = listOfNotNull(group, exercise.prompt, chart, figure).joinToString("\n\n"),
            answer = answer, authoritativeExplanation = authority,
            language = if (language == Language.RU) "ru" else "en", excerpts = excerpts)
    }

    private fun describe(figure: Figure, questionId: String): String = buildString {
        append(figure.title)
        figure.panels.forEach { panel ->
            val labels = panel.nodes.associate { it.id to ContentPackCodec.fillGaps(it.label, mapOf(questionId to 1)) }
            append("\n").append(panel.title.ifBlank { figure.kind.name.lowercase() }).append(": ")
            append(panel.nodes.joinToString("; ") { labels.getValue(it.id) })
            panel.links.forEach { append("\n${labels[it.from]} -> ${labels[it.to]}${if (it.label.isBlank()) "" else " (${it.label})"}") }
        }
    }
}
