package com.tomilov.stylishsat.runtime

import android.os.Build
import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.ModelCatalog
import com.tomilov.stylishsat.ai.RuntimeServices
import com.tomilov.stylishsat.ai.TutorEvent
import com.tomilov.stylishsat.ai.TutorRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Opt-in physical-device gate. No model download, network inference, or synthetic transcription. */
@RunWith(AndroidJUnit4::class)
class RuntimeDeviceTest {
    @Test fun localInferenceAndSpeech(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e runtimeValidation true after provisioning pinned models", arguments.getString("runtimeValidation") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.capability.reason, runtime.capability.supported)
        val staging = File(context.noBackupFilesDir, "testing")
        for (spec in ModelCatalog.all) {
            if (!runtime.downloads.isInstalled(spec)) runtime.downloads.installLocal(spec, File(staging, spec.fileName))
            assertTrue("Verified ${spec.name} must be installed", runtime.downloads.isInstalled(spec))
        }
        val audio = File(staging, "jfk.wav")
        assertTrue("Provision the pinned upstream samples/jfk.wav", audio.isFile)
        val report = File(context.noBackupFilesDir, "runtime-validation.csv")
        report.writeText("device,api,iteration,asr_ms,load_ms,first_token_ms,total_llm_ms,native_heap_bytes,pss_kb,asr_word_count\n")
        val stressMinutes = arguments.getString("stressMinutes")?.toLongOrNull()?.coerceIn(0, 30) ?: 0
        val deadline = SystemClock.elapsedRealtime() + stressMinutes * 60_000
        var iteration = 0
        do {
            iteration++
            val asrStart = SystemClock.elapsedRealtime()
            val transcript = runtime.speechTranscriber.transcribe(audio)
            val asrMillis = SystemClock.elapsedRealtime() - asrStart
            assertTrue("Whisper must produce a real transcript", transcript.text.isNotBlank())
            assertTrue("Known public-domain sample must contain its spoken keyword", transcript.text.contains("country", ignoreCase = true))
            val start = SystemClock.elapsedRealtime()
            val output = StringBuilder()
            var completion: TutorEvent.Complete? = null
            runtime.tutorEngine.explain(TutorRequest(
                task = "Solve 2x + 3 = 11. Explain in two sentences.", answer = "x = 4",
                authoritativeExplanation = "Subtract 3: 2x = 8. Divide by 2: x = 4. The supplied answer is correct.",
            )).collect { event ->
                when (event) {
                    is TutorEvent.Text -> output.append(event.delta)
                    is TutorEvent.Complete -> completion = event
                    is TutorEvent.Failure -> fail(event.message)
                    is TutorEvent.Unavailable -> fail(event.reason)
                    is TutorEvent.TooLong -> fail("Small smoke prompt must fit")
                    TutorEvent.Loading -> Unit
                }
            }
            assertNotNull("Native generation must finish", completion)
            assertTrue("Native generation must emit a token", completion!!.firstTokenMillis >= 0)
            assertTrue("Expected prepared numeric solution", output.toString().contains("4"))
            if (iteration == 1) File(context.noBackupFilesDir, "runtime-smoke-output.txt").writeText(
                "Public-domain JFK sample (actual whisper.cpp transcript):\n${transcript.text}\n\n" +
                    "Original algebra smoke task (actual Gemma response):\n$output\n",
            )
            val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val row = listOf(Build.MODEL.replace(',', '_'), Build.VERSION.SDK_INT, iteration, asrMillis,
                completion!!.loadMillis, completion!!.firstTokenMillis, SystemClock.elapsedRealtime() - start,
                Debug.getNativeHeapAllocatedSize(), memory.totalPss, transcript.text.split(Regex("\\s+")).size).joinToString(",")
            report.appendText("$row\n")
            Log.i("StylishRuntimeQA", row)
        } while (SystemClock.elapsedRealtime() < deadline)
        Log.i("StylishRuntimeQA", "Completed $iteration ASR/LLM alternations; report=${report.absolutePath}")
        if (stressMinutes == 0L) {
            val russian = StringBuilder()
            var completed = false
            runtime.tutorEngine.explain(TutorRequest(
                task = "Solve 2x + 3 = 11. Explain in two sentences.", answer = "x = 4",
                authoritativeExplanation = "Subtract 3: 2x = 8. Divide by 2: x = 4. The supplied answer is correct.",
                language = "ru",
            )).collect { event ->
                when (event) {
                    is TutorEvent.Text -> russian.append(event.delta)
                    is TutorEvent.Complete -> completed = true
                    is TutorEvent.Failure -> fail(event.message)
                    is TutorEvent.Unavailable -> fail(event.reason)
                    is TutorEvent.TooLong -> fail("The Russian smoke request must fit")
                    TutorEvent.Loading -> Unit
                }
            }
            assertTrue("Russian native generation must finish", completed)
            val modelText = russian.toString().removePrefix("Тренировочная обратная связь ИИ\n\n")
            assertTrue("The actual model output, excluding the app label, must contain Russian text", Regex("[А-Яа-яЁё]").containsMatchIn(modelText))
            File(context.noBackupFilesDir, "runtime-russian-smoke-output.txt").writeText(russian.toString())
        }
    }
}
