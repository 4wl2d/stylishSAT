package com.tomilov.stylishsat.runtime

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.ModelCatalog
import com.tomilov.stylishsat.ai.RuntimeServices
import com.tomilov.stylishsat.speech.WaveAudio
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Opt-in published human-corpus collection through the real app/JNI path.
 * Reference text stays on the host; published transcripts are independently unverified.
 * Training exposure is unknown. No microphone, model download or LLM invocation. */
@RunWith(AndroidJUnit4::class)
class HumanCorpusAsrEvaluationTest {
    @Test fun collectPublishedCorpusCandidates(): Unit = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue("Pass humanAsrEvaluation=true for this provisioned published-human-corpus batch",
            arguments.getString("humanAsrEvaluation") == "true")
        val runId = requireNotNull(arguments.getString("humanAsrRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val manifestSha = requireNotNull(arguments.getString("humanAsrManifestSha256"))
        val requestSha = requireNotNull(arguments.getString("humanAsrRequestSha256"))
        val appSha = requireNotNull(arguments.getString("humanAsrAppApkSha256"))
        listOf(manifestSha, requestSha, appSha).forEach { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val timeoutMillis = arguments.getString("humanAsrTimeoutMillis")?.toLong() ?: 120_000L
        require(timeoutMillis in 30_000L..300_000L)
        assertEquals("This run is reserved for the explicitly selected physical phone", "CPH2411", Build.MODEL)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val actualAppSha = fileSha256(File(context.applicationInfo.sourceDir))
        assertEquals("Installed app must match the host-pinned APK", appSha, actualAppSha)
        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.capability.reason, runtime.capability.supported)
        assertTrue("Expected the provisioned 12 GB-class phone", runtime.capability.totalMemoryBytes >= 10L * 1024 * 1024 * 1024)
        assertTrue("Whisper must already be installed and verified; this test never installs it",
            runtime.downloads.isInstalled(ModelCatalog.whisper))

        val staging = File(context.noBackupFilesDir, "testing/human-asr/$manifestSha")
        val requestFile = File(staging, "requests.json")
        assertEquals("Staged request integrity", requestSha, fileSha256(requestFile))
        val request = Json.decodeFromString<HumanCorpusAsrBatchRequest>(requestFile.readText())
        require(request.schemaVersion == 1 && !request.synthetic && !request.independentReferenceReview)
        require(request.referenceReviewStatus == "SOURCE_TRANSCRIPT_INDEPENDENTLY_UNVERIFIED" && request.trainingExposure == "UNKNOWN")
        require(request.datasetRevision.matches(Regex("[a-f0-9]{40}")) && request.sourceLicense == "CC-BY-4.0")
        require(request.referenceKind == "PUBLISHED_CORPUS_TRANSCRIPT")
        require(request.manifestSha256 == manifestSha && request.sampleRate == WaveAudio.SAMPLE_RATE)
        require(request.cases.size == 30 && request.cases.map { it.caseId }.toSet().size == 30)
        require(request.cases.all { it.caseId.matches(Regex("human-librispeech-test-(clean|other)-[0-9]+-[0-9]+-[0-9]+")) })
        require(request.cases.count { it.datasetSplit == "test.clean" } == 20 && request.cases.count { it.datasetSplit == "test.other" } == 10)
        require(request.cases.map { it.speakerId }.toSet().size == 30)
        val audioDirectory = File(staging, "audio").canonicalFile
        val audioFiles = request.cases.associate { case ->
            require(case.audioFileName == "${case.caseId}.wav")
            require(case.audioSha256.matches(Regex("[a-f0-9]{64}")) && case.referenceSha256.matches(Regex("[a-f0-9]{64}")))
            require(case.frames in 1..(WaveAudio.SAMPLE_RATE * 60))
            val file = File(audioDirectory, case.audioFileName).canonicalFile
            require(file.parentFile == audioDirectory && file.isFile)
            assertEquals("Fixture checksum ${case.caseId}", case.audioSha256, fileSha256(file))
            assertEquals("Fixture PCM frame count ${case.caseId}", case.frames, WaveAudio.readSamples(file).size)
            require(kotlin.math.abs(case.durationMs - case.frames * 1000.0 / request.sampleRate) <= 0.5)
            case.caseId to file
        }

        val outputDirectory = File(context.noBackupFilesDir, "testing/human-asr-runs/$runId").apply { mkdirs() }
        val rows = mutableListOf<HumanCorpusAsrResult>()
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        for ((index, case) in request.cases.withIndex()) {
            val target = File(outputDirectory, "${case.caseId}.json")
            if (target.exists()) {
                val previous = Json.decodeFromString<HumanCorpusAsrResult>(AtomicFile(target).readFully().toString(Charsets.UTF_8))
                require(previous.runId == runId && previous.manifestSha256 == manifestSha && previous.requestSha256 == requestSha)
                require(previous.caseId == case.caseId && previous.audioSha256 == case.audioSha256 && previous.referenceSha256 == case.referenceSha256)
                require(previous.appApkSha256 == actualAppSha && previous.modelSha256 == ModelCatalog.whisper.sha256)
                require(previous.deviceFingerprint == Build.FINGERPRINT)
                rows += previous
                Log.i(TAG, "Resume ${index + 1}/30 ${case.caseId}: ${previous.status}")
                continue
            }
            val before = memorySnapshot(runtime)
            val startedAtUtcMillis = System.currentTimeMillis()
            val start = SystemClock.elapsedRealtime()
            var text = ""
            var engine = "whisper.cpp base.en"
            var status = "FAILED"
            var error: String? = null
            Log.i(TAG, "Start ${index + 1}/30 ${case.caseId}; pid=${Process.myPid()}")
            try {
                val transcript = withTimeout(timeoutMillis) { runtime.speechTranscriber.transcribe(audioFiles.getValue(case.caseId)) }
                text = transcript.text
                engine = transcript.engine
                status = if (text.isBlank()) "EMPTY_TRANSCRIPT" else "TRANSCRIBED"
            } catch (timeout: TimeoutCancellationException) {
                status = "TIMED_OUT"
                error = "ASR exceeded ${timeoutMillis}ms; cancellation propagated through the real transcriber"
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                error = "${failure.javaClass.name}: ${failure.message}"
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            val result = HumanCorpusAsrResult(
                schemaVersion = 1, runId = runId, caseId = case.caseId, sourceCorpusId = case.sourceCorpusId,
                datasetSplit = case.datasetSplit, speakerId = case.speakerId, synthetic = false, independentReferenceReview = false,
                referenceReviewStatus = request.referenceReviewStatus, trainingExposure = request.trainingExposure,
                datasetRevision = request.datasetRevision, sourceLicense = request.sourceLicense,
                referenceKind = "PUBLISHED_CORPUS_TRANSCRIPT", manifestSha256 = manifestSha, requestSha256 = requestSha,
                audioSha256 = case.audioSha256, referenceSha256 = case.referenceSha256, audioFrames = case.frames,
                audioDurationMs = case.durationMs, startedAtUtcMillis = startedAtUtcMillis, elapsedMillis = elapsed,
                realTimeFactor = elapsed.toDouble() / (case.frames.toDouble() * 1000 / request.sampleRate),
                status = status, transcript = text, error = error, engine = engine,
                runtimeRevision = "whisper.cpp 371b5a7561823ab2bb32142d2751e35e7534727b via stylish_whisper JNI",
                backend = "CPU;4 threads;greedy;language=en;translate=false;no_context=true;no_timestamps=true",
                modelId = ModelCatalog.whisper.id, modelSha256 = ModelCatalog.whisper.sha256,
                appApkSha256 = actualAppSha, appVersionCode = packageInfo.longVersionCode,
                appVersionName = packageInfo.versionName ?: "", deviceModel = Build.MODEL,
                deviceFingerprint = Build.FINGERPRINT, api = Build.VERSION.SDK_INT, processId = Process.myPid(),
                memoryBefore = before, memoryAfter = memorySnapshot(runtime),
                reviewStatus = "PUBLISHED_CORPUS_REFERENCE_WER_INDEPENDENTLY_UNVERIFIED",
            )
            atomicWrite(target, Json.encodeToString(result))
            rows += result
            // Per-case AtomicFiles are canonical. The export can be reconstructed after interruption.
            atomicWrite(File(outputDirectory, "results.jsonl"), rows.joinToString("\n", postfix = "\n") { Json.encodeToString(it) })
            Log.i(TAG, "Done ${index + 1}/30 ${case.caseId}: $status; ${elapsed}ms")
        }
        atomicWrite(File(outputDirectory, "results.jsonl"), rows.joinToString("\n", postfix = "\n") { Json.encodeToString(it) })
        assertEquals(30, rows.size)
        assertTrue("All results are preserved; inspect failures in the supplemental run output", rows.all { it.status == "TRANSCRIBED" })
        Log.i(TAG, "Completed 30 published human-corpus candidates; no independent expert sign-off claimed; output=${outputDirectory.absolutePath}")
    }

    private fun memorySnapshot(runtime: RuntimeServices): HumanCorpusAsrMemory {
        val memory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val capability = runtime.capability
        return HumanCorpusAsrMemory(Debug.getNativeHeapAllocatedSize(), memory.totalPss,
            capability.totalMemoryBytes, capability.availableMemoryBytes)
    }

    private fun atomicWrite(file: File, text: String) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try {
            output.write(text.toByteArray(Charsets.UTF_8))
            output.fd.sync()
            atomic.finishWrite(output)
        } catch (failure: Throwable) {
            atomic.failWrite(output)
            throw failure
        }
    }

    private fun fileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    private companion object { const val TAG = "StylishHumanASR" }
}

@Serializable
private data class HumanCorpusAsrBatchRequest(
    val schemaVersion: Int, val synthetic: Boolean, val independentReferenceReview: Boolean,
    val referenceKind: String, val referenceReviewStatus: String, val trainingExposure: String,
    val datasetRevision: String, val sourceLicense: String, val manifestSha256: String, val sampleRate: Int,
    val cases: List<HumanCorpusAsrCaseRequest>,
)

@Serializable
private data class HumanCorpusAsrCaseRequest(
    val caseId: String, val sourceCorpusId: String, val datasetSplit: String, val speakerId: String,
    val audioFileName: String, val audioSha256: String, val referenceSha256: String,
    val frames: Int, val durationMs: Int,
)

@Serializable
private data class HumanCorpusAsrMemory(
    val nativeHeapBytes: Long, val totalPssKb: Int, val totalRamBytes: Long, val availableRamBytes: Long,
)

@Serializable
private data class HumanCorpusAsrResult(
    val schemaVersion: Int, val runId: String, val caseId: String, val sourceCorpusId: String,
    val datasetSplit: String, val speakerId: String, val synthetic: Boolean, val independentReferenceReview: Boolean,
    val referenceKind: String, val referenceReviewStatus: String, val trainingExposure: String,
    val datasetRevision: String, val sourceLicense: String, val manifestSha256: String, val requestSha256: String,
    val audioSha256: String, val referenceSha256: String, val audioFrames: Int, val audioDurationMs: Int,
    val startedAtUtcMillis: Long, val elapsedMillis: Long, val realTimeFactor: Double,
    val status: String, val transcript: String, val error: String?, val engine: String,
    val runtimeRevision: String, val backend: String, val modelId: String, val modelSha256: String,
    val appApkSha256: String, val appVersionCode: Long, val appVersionName: String,
    val deviceModel: String, val deviceFingerprint: String, val api: Int, val processId: Int,
    val memoryBefore: HumanCorpusAsrMemory, val memoryAfter: HumanCorpusAsrMemory, val reviewStatus: String,
)
