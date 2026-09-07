package com.tomilov.stylishsat.runtime

import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import com.tomilov.stylishsat.speech.WaveAudio
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections

/** Explicit preparation, fresh-process full essay and >=30-minute ASR/GPU alternation.
 * Each phase gets its own instrumentation process. Never reduces the production512 cap.
 * Published ASR references stay on the host; LLM outputs remain unreviewed candidates. */
@RunWith(AndroidJUnit4::class)
class AcceleratedRuntimeValidationTest {
    private val codec = Json { encodeDefaults = true }
    private lateinit var runtime: RuntimeServices
    private lateinit var directory: File
    private lateinit var shared: JsonObject
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun validatePreparedGpuProfile(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("acceleratedValidation") == "true")
        val runId = requireNotNull(args.getString("acceleratedRunId"))
        val phase = requireNotNull(args.getString("acceleratedPhase"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")) && phase in setOf("prepare", "cold", "mixed"))
        assertEquals("CPH2411", Build.MODEL)
        val appSha = fileHash(File(context.applicationInfo.sourceDir))
        assertEquals(args.getString("acceleratedAppSha256"), appSha)
        val serviceStart = SystemClock.elapsedRealtime()
        runtime = RuntimeServices.get(context)
        val serviceMillis = SystemClock.elapsedRealtime() - serviceStart
        assertTrue("Only the verified device/build profile may prepare GPU", runtime.acceleration.eligible)
        assertTrue(runtime.downloads.isInstalled(ModelCatalog.gemma))
        val parent = File(context.noBackupFilesDir, "testing/accelerated-runtime/$runId").apply { mkdirs() }
        directory = File(parent, phase)
        require(directory.mkdir()) { "Phase outputs are immutable; use a fresh run ID" }
        shared = buildJsonObject {
            put("schemaVersion", 1); put("runId", runId); put("phase", phase)
            put("processId", Process.myPid()); put("processStartedElapsedMillis", Process.getStartElapsedRealtime())
            put("appApkSha256", appSha)
            put("testApkSha256", fileHash(File(InstrumentationRegistry.getInstrumentation().context.applicationInfo.sourceDir)))
            put("deviceModel", Build.MODEL); put("deviceFingerprint", Build.FINGERPRINT); put("api", Build.VERSION.SDK_INT)
            put("gemmaModelSha256", ModelCatalog.gemma.sha256); put("whisperModelSha256", ModelCatalog.whisper.sha256)
            put("runtimeVersion", LocalAcceleration.RUNTIME_VERSION); put("contextTokens", TutorPromptBuilder.CONTEXT_TOKENS)
            put("outputLimit", TutorPromptBuilder.OUTPUT_TOKENS); put("latencyOnly", false)
            put("runtimeServicesAccessMillis", serviceMillis); put("initialAccelerationState", runtime.acceleration.state.value.toString())
            put("cachePolicy", "Existing disk and OS page caches retained; fresh process is not a physical cache-cold measurement")
            put("humanAcceptance", JsonNull); put("qualityReview", "UNREVIEWED")
        }
        writeNew("run.json", shared)
        when (phase) {
            "prepare" -> prepare()
            "cold" -> coldEssay()
            "mixed" -> mixed(requireNotNull(args.getString("acceleratedRequestSha256")),
                requireNotNull(args.getString("acceleratedAudioRequestSha256")),
                requireNotNull(args.getString("acceleratedAudioManifestSha256")))
        }
    }

    private suspend fun prepare() {
        val states = Collections.synchronizedList(mutableListOf<JsonObject>())
        val start = SystemClock.elapsedRealtime()
        coroutineScope {
            val observer = launch(start = CoroutineStart.UNDISPATCHED) {
                runtime.acceleration.state.collect { value -> states += buildJsonObject {
                    put("elapsedMillis", SystemClock.elapsedRealtime() - start); put("state", value.toString())
                } }
            }
            try {
                measured("prepare-native") { runtime.acceleration.prepare(); buildJsonObject {
                    put("status", if (runtime.acceleration.state.value is LocalAccelerationState.Ready) "READY" else "FAILED")
                    put("state", runtime.acceleration.state.value.toString())
                } }
            } finally { observer.cancelAndJoin() }
        }
        val receipt = File(context.noBackupFilesDir, "prepared-gpu.json")
        writeNew("preparation.json", buildJsonObject {
            shared.forEach { (key, value) -> put(key, value) }
            put("states", JsonArray(states.toList())); put("elapsedMillis", SystemClock.elapsedRealtime() - start)
            put("finalState", runtime.acceleration.state.value.toString())
            put("receipt", if (receipt.isFile) codec.parseToJsonElement(receipt.readText()) else JsonNull)
            put("receiptSha256", if (receipt.isFile) fileHash(receipt) else null)
            put("historicalFirstGpuInitializationMillis", 40367)
            put("historicalFirstSetupTarget", "MISSED; previously recorded uncached GPU compilation is not relabeled by this cache-ready preparation")
        })
        assertTrue("Preparation result is preserved before assertion", runtime.acceleration.state.value is LocalAccelerationState.Ready)
        assertTrue(runtime.acceleration.shouldUseGpu())
    }

    private suspend fun coldEssay() {
        assertTrue("A prior explicit preparation receipt must survive process restart", runtime.acceleration.state.value is LocalAccelerationState.Ready)
        val bankRaw = context.assets.open("content/seed-v1.json").use { it.readBytes() }
        val bank = ContentPackCodec.decode(bankRaw.toString(Charsets.UTF_8))
        val exercise = bank.exercises.single { it.id == "ielts-write-v3-task2-work-placements" }
        val answer = requireNotNull(exercise.sampleAnswer)
        assertEquals(309, answer.split(Regex("\\s+")).size)
        for ((index, language) in Language.entries.withIndex()) {
            val request = TutorRequestFactory.create(exercise, answer, language)
            val metadata = buildJsonObject {
                put("kind", "FACTORY_FULL_ESSAY"); put("caseId", exercise.id); put("language", language.name)
                put("coldProcessFirstInference", index == 0); put("bankSha256", hash(bankRaw))
                put("completeAnswer", answer); put("completeAnswerSha256", hash(answer.toByteArray()))
                put("answerWords", 309)
            }
            val result = infer("essay-${language.name}", request, metadata)
            assertEquals("GENERATED", result["status"]?.jsonPrimitive?.content)
            assertEquals("GPU", result["backend"]?.jsonPrimitive?.content)
        }
        writeNew("completion.json", buildJsonObject { put("status", "TWO_FULL_ESSAYS_GENERATED"); put("processId", Process.myPid()) })
    }

    private suspend fun mixed(requestSha: String, audioRequestSha: String, audioManifestSha: String) {
        assertTrue(runtime.acceleration.state.value is LocalAccelerationState.Ready)
        val staging = File(context.noBackupFilesDir, "testing/accelerated-input/$requestSha")
        val source = File(staging, "requests.jsonl")
        require(source.length() in 1L..2_097_152L)
        assertEquals(requestSha, fileHash(source))
        val lines = source.readLines().filter(String::isNotBlank)
        val requests = lines.map { codec.decodeFromString<AcceleratedRequest>(it) }
        require(requests.size == 96 && requests.map { it.caseId }.toSet().size == 96)
        requests.forEach(::validateRequest)
        require(requests.count { it.keyPrefixApplied } == 55)
        require(requests.count { it.checkerExercise != null } == 24)
        require(requests.count { it.referenceCheckStatus == "AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK" } == 84)
        require(requests.map { it.sourceCasesSha256 }.toSet().size == 1 && requests.map { it.originalRequestsSha256 }.toSet().size == 1)
        val audioStaging = File(context.noBackupFilesDir, "testing/human-asr/$audioManifestSha")
        val audioSource = File(audioStaging, "requests.json")
        assertEquals(audioRequestSha, fileHash(audioSource))
        val audio = codec.decodeFromString<AcceleratedAudioBatch>(audioSource.readText())
        require(audio.schemaVersion == 1 && !audio.synthetic && !audio.independentReferenceReview)
        require(audio.referenceKind == "PUBLISHED_CORPUS_TRANSCRIPT" && audio.trainingExposure == "UNKNOWN")
        require(audio.referenceReviewStatus == "SOURCE_TRANSCRIPT_INDEPENDENTLY_UNVERIFIED" && audio.sourceLicense == "CC-BY-4.0")
        require(audio.manifestSha256 == audioManifestSha && audio.sampleRate == 16000 && audio.cases.size == 30)
        require(audio.cases.map { it.caseId }.toSet().size == 30)
        val audioFiles = audio.cases.map { clip ->
            require(clip.caseId.matches(Regex("human-librispeech-test-(clean|other)-[0-9]+-[0-9]+-[0-9]+")))
            require(clip.audioFileName == "${clip.caseId}.wav")
            val file = File(audioStaging, "audio/${clip.audioFileName}")
            assertEquals(clip.audioSha256, fileHash(file)); assertEquals(clip.frames, WaveAudio.readSamples(file).size)
            file
        }
        File(directory, "requests.jsonl").writeText(source.readText())
        val start = SystemClock.elapsedRealtime()
        var count = 0
        try {
            // Finish all96 candidates and keep alternating if they finish before30minutes.
            // The host90-minute guard bounds native calls even when cancellation stalls.
            while ((count < 96 || SystemClock.elapsedRealtime() - start < 1_800_000) && count < 256) {
                currentCoroutineContext().ensureActive()
                val clipIndex = count % audio.cases.size
                val clip = audio.cases[clipIndex]
                val asr = measured("%03d-asr".format(count + 1)) {
                    var transcript = ""; var status = "FAILED"; var error: String? = null
                    try {
                        transcript = withTimeout(120_000) { runtime.speechTranscriber.transcribe(audioFiles[clipIndex]).text }
                        status = if (transcript.isNotBlank()) "TRANSCRIBED" else "EMPTY_TRANSCRIPT"
                    } catch (failure: TimeoutCancellationException) { status = "TIMED_OUT"; error = failure.message }
                    catch (failure: CancellationException) { throw failure }
                    catch (failure: Exception) { error = "${failure.javaClass.name}: ${failure.message}" }
                    buildJsonObject {
                        put("kind", "ASR"); put("iteration", count + 1); put("caseId", clip.caseId)
                        put("status", status); put("transcript", transcript); put("error", error)
                        put("audioSha256", clip.audioSha256); put("audioFrames", clip.frames); put("audioDurationMillis", clip.durationMs)
                        put("referenceSha256", clip.referenceSha256); put("audioManifestSha256", audioManifestSha)
                        put("audioRequestSha256", audioRequestSha); put("referenceKind", audio.referenceKind)
                        put("referenceReviewStatus", audio.referenceReviewStatus); put("trainingExposure", "UNKNOWN")
                        put("synthetic", false); put("backend", "whisper.cpp CPU(4);greedy;en;no_context")
                        put("datasetSplit", clip.datasetSplit); put("speakerId", clip.speakerId)
                    }
                }
                assertEquals("TRANSCRIBED", asr["status"]?.jsonPrimitive?.content)
                val input = requests[count % requests.size]
                val request = TutorRequest(input.task, input.answer, input.authoritativeExplanation, input.language,
                    input.excerpts.mapIndexed { i, text -> TutorExcerpt("${input.caseId}:$i", text) })
                val metadata = buildJsonObject {
                    put("kind", "CURRENT_CONTEXT_CANDIDATE"); put("iteration", count + 1); put("caseId", input.caseId)
                    put("primaryCandidate", count < 96); put("validationRepeat", count / 96)
                    put("oldOutputId", input.oldOutputId); put("requestFileSha256", requestSha)
                    put("requestLineSha256", hash(lines[count % requests.size].toByteArray()))
                    put("sourceRequestJson", lines[count % requests.size]); put("referenceCheckStatus", input.referenceCheckStatus)
                    put("referenceCheckSha256", input.referenceCheckSha256); put("keyPrefixApplied", input.keyPrefixApplied)
                    put("keyPrefixSha256", input.keyPrefixSha256)
                    put("sourceCaseType", input.sourceCaseType); put("applicationCheckStatus", input.applicationCheckStatus)
                    put("factoryPrefixSha256", input.factoryPrefixSha256)
                }
                val result = infer("%03d-llm".format(count + 1), request, metadata)
                assertEquals("GENERATED", result["status"]?.jsonPrimitive?.content)
                assertEquals("GPU", result["backend"]?.jsonPrimitive?.content)
                count++
                Log.i(TAG, "Cycle $count; elapsed=${SystemClock.elapsedRealtime() - start}; all native owners closed")
            }
        } finally {
            withContext(NonCancellable) { writeNew("completion.json", buildJsonObject {
                shared.forEach { (key, value) -> put(key, value) }
                put("completedCycles", count); put("elapsedMillis", SystemClock.elapsedRealtime() - start)
                put("primaryCandidates", minOf(count, 96)); put("minimumDurationMillis", 1_800_000)
                put("status", if (count >= 96 && SystemClock.elapsedRealtime() - start >= 1_800_000) "COMPLETED_AWAITING_QUALITY_REVIEW" else "INCOMPLETE_OR_FAILED")
                put("finalAccelerationState", runtime.acceleration.state.value.toString())
            }) }
        }
        assertTrue(count >= 96 && SystemClock.elapsedRealtime() - start >= 1_800_000)
    }

    private suspend fun infer(name: String, request: TutorRequest, metadata: JsonObject): JsonObject = measured(name) {
        val prompt = TutorPromptBuilder.build(request)
        val output = StringBuilder(); var completion: TutorEvent.Complete? = null
        var status = "INCOMPLETE"; var error: String? = null
        try {
            withTimeout(180_000) { runtime.tutorEngine.explain(request).collect { event -> when (event) {
                is TutorEvent.Text -> output.append(event.delta)
                is TutorEvent.Complete -> { completion = event; status = if (event.backend == "GPU") "GENERATED" else "BACKEND_MISMATCH" }
                is TutorEvent.Failure -> { status = "FAILED"; error = event.message }
                is TutorEvent.Unavailable -> { status = "UNAVAILABLE"; error = event.reason }
                is TutorEvent.TooLong -> { status = "TOO_LONG"; error = "${event.actualBudgetBytes}/${event.allowedBudgetBytes}" }
                TutorEvent.Loading -> Unit
            } } }
        } catch (failure: TimeoutCancellationException) { status = "TIMED_OUT"; error = failure.message }
        catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) { status = "FAILED"; error = "${failure.javaClass.name}: ${failure.message}" }
        buildJsonObject {
            metadata.forEach { (key, value) -> put(key, value) }
            put("response", output.toString()); put("responseSha256", hash(output.toString().toByteArray()))
            put("status", status); put("error", error); put("backend", completion?.backend)
            put("loadMillis", completion?.loadMillis); put("firstTokenMillis", completion?.firstTokenMillis)
            put("system", TutorPromptBuilder.system(request.language)); put("completeAnswer", request.answer)
            put("prompt", (prompt as? PromptResult.Fits)?.text); put("omittedExcerpts", (prompt as? PromptResult.Fits)?.omittedExcerpts)
            put("requestSha256", (prompt as? PromptResult.Fits)?.let { hash((it.system + "\n" + it.text).toByteArray()) })
            put("accelerationStateAfter", runtime.acceleration.state.value.toString())
        }
    }

    private suspend fun measured(name: String, block: suspend () -> JsonObject): JsonObject = coroutineScope {
        val start = SystemClock.elapsedRealtime(); val utc = System.currentTimeMillis()
        val samples = Collections.synchronizedList(mutableListOf<JsonObject>())
        fun snapshot() = buildJsonObject {
            val info = Debug.MemoryInfo().also(Debug::getMemoryInfo)
            val proc = File("/proc/self/status").readLines().mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 2 && parts[0] in setOf("VmRSS:", "VmHWM:")) parts[0] to parts[1].toLong() else null
            }.toMap()
            put("elapsedMillis", SystemClock.elapsedRealtime() - start); put("pssKb", info.totalPss)
            put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize()); put("processRssKb", proc["VmRSS:"])
            put("processHighWaterRssKb", proc["VmHWM:"]); put("graphicsKb", info.memoryStats["summary.graphics"]?.toLongOrNull())
            put("availableMemoryBytes", runtime.capability.availableMemoryBytes)
            put("thermalStatus", context.getSystemService(PowerManager::class.java).currentThermalStatus)
        }
        atomicWrite(File(directory, "phase.json"), buildJsonObject { put("name", name); put("startedAtUtcMillis", utc); put("processId", Process.myPid()) })
        samples += snapshot()
        val monitor = launch(Dispatchers.Default) { while (isActive) { delay(1000); samples += snapshot() } }
        var body = buildJsonObject { put("status", "INCOMPLETE") }
        try { body = block() }
        finally {
            withContext(NonCancellable) {
                monitor.cancelAndJoin(); samples += snapshot()
                writeNew("$name.json", buildJsonObject {
                    shared.forEach { (key, value) -> put(key, value) }; body.forEach { (key, value) -> put(key, value) }
                    put("startedAtUtcMillis", utc); put("elapsedMillis", SystemClock.elapsedRealtime() - start)
                    put("memorySamples", JsonArray(samples.toList()))
                    put("sampledPeakPssKb", samples.maxOf { it.getValue("pssKb").jsonPrimitive.long })
                    put("memoryScope", "One-second process-visible PSS/native/RSS sampling; not complete device GPU allocations")
                })
            }
        }
        body
    }

    private fun validateRequest(request: AcceleratedRequest) {
        require(request.schemaVersion == 2 && request.regressionSetId == "all-current-app-verdict-v2")
        require(request.caseId.matches(Regex("[A-Za-z0-9_-]{1,100}")) && request.language == "en" && request.maxOutputTokens == 512)
        require(hash(request.originalRequestJson.toByteArray()) == request.originalRequestSha256)
        val original = codec.decodeFromString<AcceleratedOriginalRequest>(request.originalRequestJson)
        require(original.schemaVersion == 1 && original.caseId == request.caseId && original.maxOutputTokens == 512)
        require(original.task == request.task && original.answer == request.answer && original.excerpts == request.excerpts)
        require(original.sourceSha256 == request.sourceCasesSha256 && original.reviewStatus == request.sourceReviewStatus)
        require(request.preparedKeys.all(String::isNotBlank))
        val prefix = if (request.preparedKeys.isEmpty()) "" else "Prepared key: ${request.preparedKeys.joinToString(" / ")}\n"
        require(request.keyPrefixApplied == request.preparedKeys.isNotEmpty())
        require(request.keyPrefixSha256 == prefix.takeIf(String::isNotEmpty)?.let { hash(it.toByteArray()) })
        val eligible = request.sourceCaseType in setOf("MULTIPLE_CHOICE", "NUMERIC", "SHORT_ANSWER") && request.preparedKeys.isNotEmpty()
        if (eligible) {
            val exercise = requireNotNull(request.checkerExercise)
            require(exercise.id == request.caseId && exercise.prompt == request.task)
            require(exercise.type.name == request.sourceCaseType && exercise.acceptedAnswers == request.preparedKeys)
            val actualPrefix = TutorRequestFactory.closedAuthorityPrefix(exercise, request.answer)
            require(hash(actualPrefix.toByteArray()) == request.factoryPrefixSha256)
            require(AnswerChecker.check(exercise, request.answer).status.name == request.applicationCheckStatus)
            require(request.authoritativeExplanation == actualPrefix + original.authoritativeExplanation)
        } else {
            require(request.checkerExercise == null && request.applicationCheckStatus == null && request.factoryPrefixSha256 == null)
            require(request.authoritativeExplanation == prefix + original.authoritativeExplanation)
        }
        require(request.referenceCheckStatus in setOf("AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK", "INDEPENDENT_AI_REFERENCE_CHECKED_NOT_HUMAN_REVIEW", "INDEPENDENT_AI_REFERENCE_CHECK_RAISED_ISSUES"))
        if (request.referenceCheckStatus == "AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK") require(request.referenceCheckSha256 == null)
        else require(request.referenceCheckSha256?.matches(Regex("[a-f0-9]{64}")) == true)
    }
    private fun writeNew(name: String, data: JsonObject) {
        val file = File(directory, name); require(!file.exists()); atomicWrite(file, data)
    }
    private fun atomicWrite(file: File, data: JsonObject) {
        val atomic = AtomicFile(file); val output = atomic.startWrite()
        try { output.write((codec.encodeToString(JsonObject.serializer(), data) + "\n").toByteArray()); output.fd.sync(); atomic.finishWrite(output) }
        catch (failure: Throwable) { atomic.failWrite(output); throw failure }
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun fileHash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val buffer = ByteArray(1 shl 20)
            while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object { const val TAG = "StylishAcceleratedQA" }
}

@Serializable private data class AcceleratedRequest(
    val schemaVersion: Int, val regressionSetId: String, val caseId: String,
    val task: String, val answer: String, val authoritativeExplanation: String, val excerpts: List<String>,
    val language: String, val maxOutputTokens: Int, val sourceReviewStatus: String, val sourceCasesSha256: String,
    val originalRequestsSha256: String, val originalRequestJson: String, val originalRequestSha256: String,
    val oldOutputId: String, val preparedKeys: List<String>, val keyPrefixApplied: Boolean,
    val keyPrefixSha256: String?, val referenceCheckStatus: String, val referenceCheckSha256: String?,
    val sourceCaseType: String? = null, val checkerExercise: Exercise? = null,
    val applicationCheckStatus: String? = null, val factoryPrefixSha256: String? = null,
)
@Serializable private data class AcceleratedOriginalRequest(
    val schemaVersion: Int, val caseId: String, val task: String, val answer: String,
    val authoritativeExplanation: String, val excerpts: List<String>, val reviewStatus: String,
    val sourceSha256: String, val maxOutputTokens: Int,
)
@Serializable private data class AcceleratedAudioBatch(
    val schemaVersion: Int, val synthetic: Boolean, val independentReferenceReview: Boolean,
    val referenceKind: String, val referenceReviewStatus: String, val trainingExposure: String,
    val datasetRevision: String, val sourceLicense: String, val manifestSha256: String, val sampleRate: Int,
    val cases: List<AcceleratedAudioCase>,
)
@Serializable private data class AcceleratedAudioCase(
    val caseId: String, val sourceCorpusId: String, val datasetSplit: String, val speakerId: String,
    val audioFileName: String, val audioSha256: String, val referenceSha256: String, val frames: Int, val durationMs: Int,
)
