package com.tomilov.stylishsat.ai

import com.tomilov.stylishsat.domain.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class PromptCandidateBudgetTest {
    private fun file(path: String) = listOf(File(path), File("../$path")).first { it.isFile }
    private fun manifests(): List<JsonObject> = listOf(
        Triple("manifest.json", "0a31393a81df8449cbf40655958332d91a1f797d179a38028ba8e8641026c78a", "260f8d05f774c4bd257f27184586b3c99e8d598f9efc924f31ae25847be1516d"),
        Triple("manifest-explicit-comparison-v2.json", "0c28d9f427d85a361f8d65a1a5d3ce8cd487bfd69e7dc71dbd06aa63317682f4", "17c92d93bdbd56887cf58f6b8406aa3a88779666087efcd95ce1cfd41784a612"),
    ).map { (name, expectedManifest, expectedSystem) ->
        val text = checkNotNull(javaClass.getResource("/prompt-candidates/$name")).readText()
        assertEquals(expectedManifest, sha(text))
        Json.parseToJsonElement(text).jsonObject.also {
            assertEquals(expectedSystem, sha(it.getValue("candidateSystem").jsonPrimitive.content))
        }
    }
    private fun system(manifest: JsonObject, language: Language): String {
        val value = manifest.getValue("candidateSystem").jsonPrimitive.content
        assertEquals(1, Regex("English").findAll(value).count())
        return if (language == Language.RU) value.replace("English", "Russian") else value
    }
    @Test fun candidateFitsAllTwelveFullSamplesAnd768ClosedQuestionsInBothLanguages() {
        val pack = ContentPackCodec.decode(file("app/src/main/assets/content/seed-v1.json").readText())
        val closed = pack.exercises.filter { it.type !in setOf(ExerciseType.WRITING, ExerciseType.SPEAKING) }
        val writing = pack.exercises.filter { it.type == ExerciseType.WRITING && it.sampleAnswer != null }
        assertEquals(768, closed.size); assertEquals(12, writing.size)
        for (manifest in manifests()) for (language in Language.entries) {
            val candidate = system(manifest, language)
            assertTrue(candidate.toByteArray().size <= TutorPromptBuilder.system(if (language == Language.RU) "ru" else "en").toByteArray().size)
            for (exercise in closed + writing) {
                val answer = if (exercise.type == ExerciseType.WRITING) exercise.sampleAnswer!! else exercise.acceptedAnswers.first()
                val request = TutorRequestFactory.create(exercise, answer, language)
                val baseline = TutorPromptBuilder.build(request)
                assertTrue("${exercise.id}/$language", baseline is PromptResult.Fits)
                baseline as PromptResult.Fits
                assertTrue(baseline.text.contains(answer))
                val changedSystemOnly = baseline.copy(system = candidate)
                assertEquals(baseline.text, changedSystemOnly.text)
                assertTrue(changedSystemOnly.system.toByteArray().size + changedSystemOnly.text.toByteArray().size <= TutorPromptBuilder.INPUT_BYTE_BUDGET)
            }
        }
    }
    @Test fun selected24UserPromptsMatchTheFrozenActualBaselines() {
        val variants = manifests()
        assertEquals(variants[0].getValue("cases"), variants[1].getValue("cases"))
        val cases = variants[0].getValue("cases").jsonArray
        assertEquals(24, cases.size)
        for (element in cases) {
            val item = element.jsonObject; val input = item.getValue("requestJson").jsonObject
            fun value(key: String) = input.getValue(key).jsonPrimitive.content
            val id = value("caseId")
            val request = TutorRequest(value("task"), value("answer"), value("authoritativeExplanation"), value("language"),
                input.getValue("excerpts").jsonArray.mapIndexed { index, text -> TutorExcerpt("$id:$index", text.jsonPrimitive.content) })
            val prompt = TutorPromptBuilder.build(request) as PromptResult.Fits
            assertEquals(item.getValue("frozenPrompt").jsonPrimitive.content, prompt.text)
            assertEquals(item.getValue("baselineRequestSha256").jsonPrimitive.content, sha(prompt.system + "\n" + prompt.text))
            assertEquals(0, prompt.omittedExcerpts)
        }
    }
    private fun sha(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
