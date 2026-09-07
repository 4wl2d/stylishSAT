package com.tomilov.stylishsat.ai

import com.tomilov.stylishsat.domain.*
import java.io.File
import org.junit.Assert.*
import org.junit.Test

class TutorRequestFactoryTest {
    private fun bank(): ContentPack = ContentPackCodec.decode(listOf(
        File("src/main/assets/content/seed-v1.json"), File("app/src/main/assets/content/seed-v1.json")
    ).first { it.isFile }.readText())

    @Test fun allTwelveCompleteWrittenSamplesFitWithoutDroppingLearnerTextOrChartValues() {
        val writing = bank().exercises.filter { it.type == ExerciseType.WRITING && it.sampleAnswer != null }
        assertEquals(12, writing.size)
        writing.forEach { exercise -> Language.entries.forEach { language ->
            val request = TutorRequestFactory.create(exercise, exercise.sampleAnswer!!, language)
            val prompt = TutorPromptBuilder.build(request)
            assertTrue("${exercise.id}/$language: $prompt", prompt is PromptResult.Fits)
            prompt as PromptResult.Fits
            assertTrue(prompt.text.contains(exercise.sampleAnswer!!))
            assertTrue(prompt.text.contains(exercise.prompt))
            exercise.chart?.let { chart ->
                assertTrue(prompt.text.contains(chart.unit))
                chart.labels.forEach { assertTrue(prompt.text.contains(it)) }
                chart.series.forEach { series -> series.values.forEach { assertTrue(prompt.text.contains(it.toString())) } }
            }
            assertEquals(if (language == Language.RU) "ru" else "en", request.language)
            assertTrue(request.excerpts.any { it.sourceId.endsWith("/illustrative-sample") && it.text.contains(exercise.explanation.text(language)) })
        } }
    }

    @Test fun everyClosedQuestionKeepsItsPreparedKeyAndEvidenceInTheMandatoryCore() {
        val closed = bank().exercises.filter { it.type !in listOf(ExerciseType.WRITING, ExerciseType.SPEAKING) }
        assertEquals(768, closed.size)
        closed.forEach { exercise -> Language.entries.forEach { language ->
            val request = TutorRequestFactory.create(exercise, exercise.acceptedAnswers.first(), language)
            val result = TutorPromptBuilder.build(request)
            assertTrue("${exercise.id}/$language", result is PromptResult.Fits)
            assertTrue(request.authoritativeExplanation.contains(exercise.acceptedAnswers.first()))
            exercise.evidence?.let { assertTrue(request.authoritativeExplanation.contains(it)) }
            exercise.passage?.let { assertTrue(ChatGptHandoff.preview(request).contains(it)) }
        } }
    }

    @Test fun aTrulyOversizedEssayIsExplicitlyRejectedAndCompleteInHandoff() {
        val exercise = bank().exercises.first { it.type == ExerciseType.WRITING }
        val answer = "Complete learner paragraph 🧭. ".repeat(200)
        val request = TutorRequestFactory.create(exercise, answer, Language.RU)
        assertTrue(TutorPromptBuilder.build(request) is PromptResult.TooLong)
        assertTrue(ChatGptHandoff.preview(request).contains(answer))
        assertEquals(answer, request.answer)
    }

    @Test fun closedAuthorityUsesTheActualCheckerForWrongEquivalentAndInvalidNumericAnswers() {
        val exercise = bank().exercises.first { it.type == ExerciseType.NUMERIC }.copy(acceptedAnswers = listOf("76"))
        assertTrue(TutorRequestFactory.create(exercise, "80", Language.EN).authoritativeExplanation.startsWith("Application check: INCORRECT."))
        assertTrue(TutorRequestFactory.create(exercise, "152/2", Language.RU).authoritativeExplanation.startsWith("Application check: CORRECT."))
        assertTrue(TutorRequestFactory.create(exercise, "not a number", Language.EN).authoritativeExplanation.startsWith("Application check: INVALID."))
    }

    @Test fun ieltsWordLimitIsPartOfTheVerdictEvenWhenThePhraseMatchesTheKey() {
        val exercise = bank().exercises.first { it.type == ExerciseType.SHORT_ANSWER }.copy(
            acceptedAnswers = listOf("the research team"), wordLimit = 2)
        val authority = TutorRequestFactory.create(exercise, "the research team", Language.EN).authoritativeExplanation
        assertTrue(authority.startsWith("Application check: INCORRECT."))
        assertTrue(authority.contains("exceeds the 2-word limit"))
    }

    @Test fun openWorkReceivesCriteriaWithoutAnAutomaticCorrectnessVerdict() {
        bank().exercises.filter { it.type in listOf(ExerciseType.WRITING, ExerciseType.SPEAKING) }.forEach {
            assertFalse(TutorRequestFactory.create(it, "A learner response.", Language.EN).authoritativeExplanation.contains("Application check:"))
        }
    }
}
