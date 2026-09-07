package com.tomilov.stylishsat.ai

import org.junit.Assert.*
import org.junit.Test

class TutorPromptBuilderTest {
    @Test fun oversizedExcerptsAreRemovedBeforeAnEssayIsTouched() {
        val essay = "I believe access to education matters. ".repeat(35)
        val request = TutorRequest("Discuss public education.", essay, excerpts = listOf(TutorExcerpt("lesson", "extra ".repeat(2000))))
        val result = TutorPromptBuilder.build(request) as PromptResult.Fits
        assertTrue(result.text.contains(essay))
        assertEquals(1, result.omittedExcerpts)
        assertTrue(result.system.toByteArray().size + result.text.toByteArray().size <= TutorPromptBuilder.INPUT_BYTE_BUDGET)
    }

    @Test fun overBudgetEssayIsRejectedIntactAndHandoffContainsItAll() {
        val essay = "Education improves communities. ".repeat(400)
        val request = TutorRequest("Discuss education.", essay)
        assertTrue(TutorPromptBuilder.build(request) is PromptResult.TooLong)
        assertTrue(ChatGptHandoff.preview(request).contains(essay))
    }

    @Test fun multibyteRussianCountsUtf8BytesAndCannotExceedBudget() {
        val request = TutorRequest("Explain the evidence.", "Answer", language = "ru",
            excerpts = listOf(TutorExcerpt("ru", "Я".repeat(1600))))
        val result = TutorPromptBuilder.build(request) as PromptResult.Fits
        assertEquals(1, result.omittedExcerpts)
        assertTrue(result.system.contains("Russian"))
    }

    @Test fun trainingAndAuthorityRulesExistForOpenResponses() {
        val result = TutorPromptBuilder.build(TutorRequest("Speaking", "hello")) as PromptResult.Fits
        assertTrue(result.system.contains("never an official score or IELTS band"))
        assertTrue(result.system.contains("Never assess pronunciation from a transcript"))
        assertTrue(result.system.contains("Do not change progress"))
    }
}
