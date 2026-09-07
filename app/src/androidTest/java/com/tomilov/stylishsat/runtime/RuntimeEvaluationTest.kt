package com.tomilov.stylishsat.runtime

import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.ModelCatalog
import com.tomilov.stylishsat.ai.RuntimeServices
import com.tomilov.stylishsat.ai.TutorEvent
import com.tomilov.stylishsat.ai.TutorExcerpt
import com.tomilov.stylishsat.ai.TutorRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Generates reviewable candidate outputs only. It does not manufacture expert labels or grades. */
@RunWith(AndroidJUnit4::class)
class RuntimeEvaluationTest {
    @Test fun collectExpertCandidates(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e runtimeEvaluation true to generate candidate outputs", args.getString("runtimeEvaluation") == "true")
        val runId = args.getString("candidateRunId") ?: "gemma4-${System.currentTimeMillis()}"
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.capability.reason, runtime.capability.supported)
        assertTrue("Install and verify Gemma before this opt-in batch", runtime.downloads.isInstalled(ModelCatalog.gemma))
        val source = File(context.noBackupFilesDir, "testing/evaluation-requests.jsonl")
        val requests = source.readLines().filter(String::isNotBlank).map { Json.decodeFromString<EvaluationRequest>(it) }
        require(requests.size == 96 && requests.map { it.caseId }.toSet().size == 96)
        require(requests.all { it.schemaVersion == 1 && it.maxOutputTokens == 512 })
        require(requests.map { it.sourceSha256 }.toSet().size == 1)
        val output = File(context.noBackupFilesDir, "candidate-responses-$runId.jsonl")
        val existing = if (output.exists()) output.readLines().filter(String::isNotBlank).map { Json.decodeFromString<CandidateResponse>(it) } else emptyList()
        require(existing.all { it.runId == runId && it.sourceSha256 == requests.first().sourceSha256 })
        require(existing.map { it.caseId }.toSet().size == existing.size)
        val completeIds = existing.map { it.caseId }.toSet()
        for ((index, request) in requests.withIndex()) {
            if (request.caseId in completeIds) continue
            val response = StringBuilder()
            var status = "INCOMPLETE"
            var detail: String? = null
            var timing: TutorEvent.Complete? = null
            try {
                runtime.tutorEngine.explain(TutorRequest(
                    task = request.task,
                    answer = request.answer,
                    authoritativeExplanation = request.authoritativeExplanation,
                    excerpts = request.excerpts.mapIndexed { excerptIndex, text -> TutorExcerpt("${request.caseId}:$excerptIndex", text) },
                )).collect { event ->
                    when (event) {
                        is TutorEvent.Text -> response.append(event.delta)
                        is TutorEvent.Complete -> { timing = event; status = "GENERATED" }
                        is TutorEvent.TooLong -> { status = "TOO_LONG"; detail = "${event.actualBudgetBytes} UTF-8 bytes exceed ${event.allowedBudgetBytes}; complete input preserved" }
                        is TutorEvent.Unavailable -> { status = "UNAVAILABLE"; detail = event.reason }
                        is TutorEvent.Failure -> { status = "FAILED"; detail = event.message }
                        TutorEvent.Loading -> Unit
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                status = "FAILED"; detail = error.message
            }
            val row = CandidateResponse(request.caseId, runId, "$runId:${request.caseId}", response.toString(),
                ModelCatalog.gemma.id, ModelCatalog.gemma.sha256, request.sourceSha256, status, detail,
                "UNREVIEWED", request.reviewStatus, "LiteRT-LM 0.16.1", "CPU(4)", Build.MODEL, Build.VERSION.SDK_INT,
                4096, 512, timing?.loadMillis, timing?.firstTokenMillis)
            // Each case is durable before the next starts, so the same runId resumes completed rows.
            java.io.FileOutputStream(output, true).use { stream ->
                stream.write((Json.encodeToString(row) + "\n").toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            Log.i("StylishRuntimeEval", "${index + 1}/96 ${request.caseId}: $status")
        }
        val rows = output.readLines().filter(String::isNotBlank).map { Json.decodeFromString<CandidateResponse>(it) }
        assertEquals("Each candidate must have a measured result or explicit failure", 96, rows.size)
        Log.i("StylishRuntimeEval", "Completed candidate run $runId; expert review remains UNREVIEWED; output=${output.absolutePath}")
    }
}

@Serializable
private data class EvaluationRequest(
    val schemaVersion: Int,
    val caseId: String,
    val task: String,
    val answer: String,
    val authoritativeExplanation: String,
    val excerpts: List<String>,
    val reviewStatus: String,
    val sourceSha256: String,
    val maxOutputTokens: Int,
)

@Serializable
private data class CandidateResponse(
    val caseId: String,
    val runId: String,
    val outputId: String,
    val response: String,
    val modelId: String,
    val modelSha256: String,
    val sourceSha256: String,
    val status: String,
    val error: String?,
    val reviewStatus: String,
    val sourceReviewStatus: String,
    val runtime: String,
    val backend: String,
    val device: String,
    val api: Int,
    val contextTokens: Int,
    val maxOutputTokens: Int,
    val loadMillis: Long?,
    val firstTokenMillis: Long?,
)
