package com.tomilov.stylishsat.runtime

import android.os.Build
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Real longest-sample feedback through the same request factory as the UI; no learner records. */
@RunWith(AndroidJUnit4::class)
class WritingFeedbackDeviceTest {
    @Test fun completeEssayReceivesEnglishAndRussianNativeFeedback(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("writingFeedbackValidation") == "true")
        assertEquals("CPH2411", Build.MODEL)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.capability.supported)
        assertTrue(runtime.downloads.isInstalled(ModelCatalog.gemma))
        val rawBank = context.assets.open("content/seed-v1.json").use { it.readBytes() }
        val pack = ContentPackCodec.decode(rawBank.toString(Charsets.UTF_8))
        val exercise = pack.exercises.filter { it.type == ExerciseType.WRITING && it.sampleAnswer != null }
            .maxBy { TutorPromptBuilder.core(TutorRequestFactory.create(it, it.sampleAnswer!!, Language.EN)).toByteArray().size }
        val answer = exercise.sampleAnswer!!
        assertTrue(answer.split(Regex("\\s+")).size >= 250)
        val appSha = File(context.applicationInfo.sourceDir).inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(65_536)
            while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        val directory = File(context.noBackupFilesDir, "testing/writing-feedback/$appSha").apply { mkdirs() }
        for (language in Language.entries) {
            val request = TutorRequestFactory.create(exercise, answer, language)
            val prompt = TutorPromptBuilder.build(request) as PromptResult.Fits
            assertTrue(prompt.text.contains(answer))
            val output = StringBuilder()
            var completion: TutorEvent.Complete? = null
            var failure: String? = null
            val target = AtomicFile(File(directory, "${language.name}.json"))
            if (target.baseFile.exists()) {
                val previous = Json.decodeFromString<WritingFeedbackResult>(target.readFully().toString(Charsets.UTF_8))
                assertEquals(appSha, previous.appApkSha256)
                assertEquals(sha(rawBank), previous.bankSha256)
                assertEquals(sha(answer.toByteArray()), previous.completeAnswerSha256)
                assertEquals(sha((prompt.system + "\n" + prompt.text).toByteArray()), previous.requestSha256)
                assertEquals("GENERATED", previous.status)
                continue
            }
            runtime.tutorEngine.explain(request).collect { event -> when (event) {
                is TutorEvent.Text -> output.append(event.delta)
                is TutorEvent.Complete -> completion = event
                is TutorEvent.Failure -> failure = event.message
                is TutorEvent.Unavailable -> failure = event.reason
                is TutorEvent.TooLong -> failure = "Complete sample unexpectedly exceeds the context budget"
                TutorEvent.Loading -> Unit
            } }
            val row = WritingFeedbackResult(exercise.id, exercise.version, pack.version, sha(rawBank), appSha,
                ModelCatalog.gemma.sha256, Build.FINGERPRINT, language.name, answer, sha(answer.toByteArray()),
                prompt.system, prompt.text, sha((prompt.system + "\n" + prompt.text).toByteArray()),
                prompt.system.toByteArray().size + prompt.text.toByteArray().size, prompt.omittedExcerpts,
                output.toString(), if (completion != null && failure == null) "GENERATED" else "FAILED",
                failure, completion?.loadMillis, completion?.firstTokenMillis)
            val stream = target.startWrite()
            try { stream.write(Json.encodeToString(row).toByteArray()); target.finishWrite(stream) }
            catch (error: Throwable) { target.failWrite(stream); throw error }
            assertEquals(failure, "GENERATED", row.status)
            val body = output.toString().removePrefix(if (language == Language.RU) "Тренировочная обратная связь ИИ\n\n" else "AI training feedback\n\n")
                .removePrefix(if (language == Language.RU) "Дополнительные выдержки сокращены, чтобы сохранить полный ответ.\n\n" else "Additional excerpts were reduced to keep your complete answer.\n\n")
            assertTrue("A substantive native body is required", body.length > 80)
            assertTrue("Language must come from model output, excluding app labels", (if (language == Language.RU) Regex("[А-Яа-яЁё]") else Regex("[A-Za-z]")).findAll(body).count() > 20)
            Log.i("StylishWritingQA", "$language generated; bytes=${row.inputUtf8Bytes}, firstToken=${row.firstTokenMillis}ms; ${target.baseFile}")
        }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

@Serializable
private data class WritingFeedbackResult(
    val exerciseId: String, val exerciseVersion: Int, val bankVersion: Int, val bankSha256: String,
    val appApkSha256: String, val modelSha256: String, val deviceFingerprint: String, val language: String,
    val completeAnswer: String, val completeAnswerSha256: String, val system: String, val prompt: String,
    val requestSha256: String, val inputUtf8Bytes: Int, val omittedExcerpts: Int, val response: String,
    val status: String, val failure: String?, val loadMillis: Long?, val firstTokenMillis: Long?,
    val expertReview: String = "UNREVIEWED", val contextTokens: Int = 4096, val outputTokens: Int = 512,
)
