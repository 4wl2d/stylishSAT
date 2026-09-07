package com.tomilov.stylishsat.runtime

import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.AtomicFile
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.*
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/** Development-only instruction/role experiment. The production APK and builder stay unchanged.
 * Selection labels and baseline responses are deliberately absent from the staged DTO. */
@RunWith(AndroidJUnit4::class)
class PromptCandidateEvaluationTest {
    private val codec = Json { encodeDefaults = true }

    @Test fun collectFrozen24WithCandidateSystem(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("promptCandidateEvaluation") == "true")
        val runId = requireNotNull(args.getString("promptCandidateRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val requestSha = requireNotNull(args.getString("promptCandidateRequestSha256"))
        val manifestSha = requireNotNull(args.getString("promptCandidateManifestSha256"))
        val candidateSha = requireNotNull(args.getString("promptCandidateSystemSha256"))
        val testSha = requireNotNull(args.getString("promptCandidateTestApkSha256"))
        listOf(requestSha, manifestSha, candidateSha, testSha).forEach { require(it.matches(Regex("[a-f0-9]{64}"))) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val appSha = fileSha(File(context.applicationInfo.sourceDir))
        assertEquals(PRODUCTION_APK_SHA, appSha)
        assertEquals(testSha, fileSha(File(instrumentation.context.applicationInfo.sourceDir)))
        assertEquals("CPH2411", Build.MODEL)
        val staged = File(context.noBackupFilesDir, "testing/prompt-candidate-input/$requestSha/requests.json")
        require(staged.length() in 1L..2_097_152L)
        assertEquals(requestSha, fileSha(staged))
        val batch = codec.decodeFromString<PromptCandidateBatch>(staged.readText())
        require(batch.schemaVersion == 1 && batch.experimentId == "learner-reference-separation-v1")
        require(batch.sourceManifestSha256 == manifestSha && batch.productionAppApkSha256 == appSha)
        require(batch.candidateSystemSha256 == candidateSha && hash(batch.candidateSystem) == candidateSha)
        require(batch.inputRoleLayout in setOf("FROZEN_USER_PROMPT", "SYSTEM_CONTEXT_USER_ANSWER"))
        require(batch.thinkingTokenBudget in setOf(0, 32))
        require(batch.inputByteBudget == TutorPromptBuilder.INPUT_BYTE_BUDGET && batch.contextTokens == 4096 && batch.outputTokens == 512)
        require(batch.cases.size == 24 && batch.cases.map { it.caseId }.toSet().size == 24)
        val requests = batch.cases.map { item ->
            val input = item.requestJson
            validateInput(input)
            require(item.caseId == input.caseId && item.completeAnswer == input.answer)
            require(hash(item.completeAnswer) == item.completeAnswerSha256)
            val request = TutorRequest(input.task, input.answer, input.authoritativeExplanation, input.language,
                input.excerpts.mapIndexed { index, text -> TutorExcerpt("${input.caseId}:$index", text) })
            val baseline = TutorPromptBuilder.build(request) as PromptResult.Fits
            assertEquals("The old sent user prompt must be preserved byte-for-byte", item.frozenPrompt, baseline.text)
            assertEquals(item.frozenPromptSha256, hash(baseline.text))
            assertEquals(item.baselineSystemSha256, hash(baseline.system))
            assertEquals(item.baselineRequestSha256, hash(baseline.system + "\n" + baseline.text))
            assertEquals(0, baseline.omittedExcerpts)
            require(batch.candidateSystem.toByteArray().size <= baseline.system.toByteArray().size)
            val candidate = if (batch.inputRoleLayout == "FROZEN_USER_PROMPT") baseline.copy(system = batch.candidateSystem)
            else PromptResult.Fits(system = buildString {
                append(batch.candidateSystem)
                append("\n\nTASK:\n${input.task}\n\nPREPARED EXPLANATION / CRITERIA:\n${input.authoritativeExplanation}")
                input.excerpts.forEachIndexed { index, text -> append("\n\nSOURCE ${input.caseId}:$index:\n$text") }
            }, text = "LEARNER ANSWER (complete):\n${input.answer}", omittedExcerpts = 0)
            assertEquals("Role relocation must preserve the complete input byte total",
                bytes(batch.candidateSystem) + bytes(baseline.text), bytes(candidate.system) + bytes(candidate.text))
            require(candidate.text.contains(input.answer) && bytes(candidate.system) + bytes(candidate.text) + batch.thinkingTokenBudget <= TutorPromptBuilder.INPUT_BYTE_BUDGET)
            candidate
        }
        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.acceleration.eligible)
        assertTrue("Existing explicit GPU preparation is required; this experiment does not prepare it", runtime.acceleration.shouldUseGpu())
        val model = requireNotNull(runtime.downloads.modelFile(ModelCatalog.gemma))
        assertEquals(ModelCatalog.gemma.sha256, fileSha(model))
        val cache = File(context.noBackupFilesDir, "litert-cache")
        val parent = File(context.noBackupFilesDir, "testing/prompt-candidate").apply { mkdirs() }
        val directory = File(parent, runId)
        require(directory.mkdir()) { "Choose a new run ID; outputs are immutable" }
        File(directory, "requests.json").writeBytes(staged.readBytes())
        val shared = buildJsonObject {
            put("schemaVersion", 1); put("runId", runId); put("experimentId", batch.experimentId)
            put("requestFileSha256", requestSha); put("sourceManifestSha256", manifestSha)
            put("appApkSha256", appSha); put("testApkSha256", testSha)
            put("modelSha256", ModelCatalog.gemma.sha256); put("actualModelHashVerified", true)
            put("candidateSystemSha256", candidateSha); put("backend", "GPU")
            put("runtimeVersion", LocalAcceleration.RUNTIME_VERSION); put("contextTokens", 4096); put("outputTokens", 512)
            put("topK", 20); put("topP", .9); put("temperature", .2); put("seed", 0)
            put("thinking", batch.thinkingTokenBudget > 0); put("thinkingTokenBudget", batch.thinkingTokenBudget); put("automaticToolCalling", false)
            put("firstTokenDefinition", "First nonblank primary-content chunk after send; private-channel activity is separate")
            put("deviceFingerprint", Build.FINGERPRINT); put("deviceModel", Build.MODEL); put("processId", Process.myPid())
            put("productionSourceChanged", false); put("onlySystemChanged", batch.inputRoleLayout == "FROZEN_USER_PROMPT" && batch.thinkingTokenBudget == 0)
            put("inputRoleLayout", batch.inputRoleLayout); put("sourceDataPreserved", true)
            put("candidateInstruction", batch.candidateSystem)
            put("selectionMetadataSentToModel", false); put("baselineResponseSentToModel", false)
            put("humanAcceptance", JsonNull); put("qualityReview", "UNREVIEWED")
        }
        writeNew(File(directory, "run.json"), shared)
        val statuses = mutableListOf<String>()
        try {
            for ((index, item) in batch.cases.withIndex()) {
                currentCoroutineContext().ensureActive()
                val prompt = requests[index]
                RuntimeResourceGate.exclusive {
                    assertTrue(runtime.acceleration.shouldUseGpu())
                    RuntimeCapability.requireAvailableMemory(context)
                    val started = SystemClock.elapsedRealtime()
                    val utc = System.currentTimeMillis()
                    val before = memory()
                    val native = StringBuilder()
                    val privateChannelChars = mutableMapOf<String, Long>()
                    var firstPrivateChannel: Long? = null
                    var load: Long? = null; var first: Long? = null
                    var setup: Long? = null; var firstFromCase: Long? = null
                    var error: String? = null; var status = "FAILED"
                    var engine: Engine? = null
                    var cancellation: CancellationException? = null
                    atomicWrite(File(directory, "phase.json"), buildJsonObject {
                        put("index", index + 1); put("caseId", item.caseId); put("startedAtUtcMillis", utc); put("processId", Process.myPid())
                    })
                    try {
                        currentCoroutineContext().ensureActive()
                        engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.GPU(),
                            maxNumTokens = TutorPromptBuilder.CONTEXT_TOKENS, cacheDir = cache.absolutePath))
                        currentCoroutineContext().ensureActive()
                        engine.initialize()
                        currentCoroutineContext().ensureActive()
                        load = SystemClock.elapsedRealtime() - started
                        val setupStart = SystemClock.elapsedRealtime()
                        engine.createConversation(ConversationConfig(systemInstruction = Contents.of(prompt.system),
                            samplerConfig = SamplerConfig(topK = 20, topP = .9, temperature = .2),
                            automaticToolCalling = false, maxOutputToken = TutorPromptBuilder.OUTPUT_TOKENS,
                            thinkingConfig = ThinkingConfig(enableThinking = batch.thinkingTokenBudget > 0, thinkingTokenBudget = batch.thinkingTokenBudget))).use { conversation ->
                            setup = SystemClock.elapsedRealtime() - setupStart
                            val inferenceStart = SystemClock.elapsedRealtime()
                            val chunks = Channel<CandidateChunk>(Channel.UNLIMITED)
                            val finished = CompletableDeferred<Unit>()
                            conversation.sendMessageAsync(prompt.text, object : MessageCallback {
                                override fun onMessage(message: Message) {
                                    chunks.trySend(CandidateChunk(message.toString(), message.channels.mapValues { it.value.length }))
                                }
                                override fun onDone() { finished.complete(Unit); chunks.close() }
                                override fun onError(throwable: Throwable) { finished.complete(Unit); chunks.close(throwable) }
                            })
                            try {
                                withTimeout(180_000) {
                                    for (chunk in chunks) {
                                        chunk.channelChars.forEach { (name, count) ->
                                            if (count > 0 && firstPrivateChannel == null) firstPrivateChannel = SystemClock.elapsedRealtime() - inferenceStart
                                            privateChannelChars[name] = privateChannelChars.getOrDefault(name, 0) + count
                                        }
                                        if (chunk.text.isNotEmpty()) {
                                            if (first == null && chunk.text.isNotBlank()) {
                                                first = SystemClock.elapsedRealtime() - inferenceStart
                                                firstFromCase = SystemClock.elapsedRealtime() - started
                                            }
                                            native.append(chunk.text)
                                        }
                                    }
                                }
                            } finally {
                                if (!finished.isCompleted) conversation.cancelProcess()
                                withContext(NonCancellable) { finished.await() }
                                chunks.cancel()
                            }
                            status = if (first != null && native.isNotBlank()) "GENERATED" else "EMPTY"
                        }
                    } catch (failure: TimeoutCancellationException) {
                        error = failure.message; status = "TIMED_OUT"
                    } catch (failure: CancellationException) {
                        error = failure.message; status = "CANCELLED"; cancellation = failure
                    } catch (failure: Exception) {
                        error = "${failure.javaClass.name}: ${failure.message}"; status = "FAILED"
                    } catch (failure: LinkageError) {
                        error = "${failure.javaClass.name}: ${failure.message}"; status = "FAILED"
                    } finally {
                        try { engine?.let { if (it.isInitialized()) it.close() } }
                        catch (failure: Throwable) { error = "${error.orEmpty()} Cleanup: ${failure.javaClass.name}: ${failure.message}"; status = "CLEANUP_FAILED" }
                    }
                    val response = "AI training feedback\n\n" + native
                    val result = buildJsonObject {
                        shared.forEach { (key, value) -> put(key, value) }
                        put("caseId", item.caseId); put("index", index + 1); put("outputId", "$runId:${item.caseId}")
                        put("status", status); put("error", error); put("startedAtUtcMillis", utc)
                        put("elapsedMillis", SystemClock.elapsedRealtime() - started); put("loadMillis", load); put("firstTokenMillis", first)
                        put("conversationSetupMillis", setup); put("caseStartToFirstTokenMillis", firstFromCase)
                        put("firstPrivateChannelMillis", firstPrivateChannel)
                        put("privateChannelCharacterCounts", buildJsonObject { privateChannelChars.forEach { (name, count) -> put(name, count) } })
                        put("privateChannelTextRetained", false)
                        put("system", prompt.system); put("systemUtf8Bytes", bytes(prompt.system)); put("prompt", prompt.text)
                        put("systemSha256", hash(prompt.system))
                        put("promptSha256", hash(prompt.text)); put("requestSha256", hash(prompt.system + "\n" + prompt.text))
                        put("baselineRequestSha256", item.baselineRequestSha256); put("baselineResponseSha256", item.baselineResponseSha256)
                        put("baselineSystemSha256", item.baselineSystemSha256); put("completeAnswer", item.completeAnswer)
                        put("completeAnswerSha256", item.completeAnswerSha256); put("omittedExcerpts", prompt.omittedExcerpts)
                        put("nativeResponse", native.toString()); put("nativeResponseSha256", hash(native.toString()))
                        put("response", response); put("responseSha256", hash(response)); put("appGeneratedPrefix", "AI training feedback\n\n")
                        put("referenceCheckStatus", item.requestJson.referenceCheckStatus); put("referenceCheckSha256", item.requestJson.referenceCheckSha256)
                        put("memoryBefore", before); put("memoryAfterApiClose", memory())
                    }
                    withContext(NonCancellable) { writeNew(File(directory, "%02d-%s.json".format(index + 1, item.caseId)), result) }
                    statuses += result.getValue("status").jsonPrimitive.content
                    cancellation?.let { throw it }
                    result
                }
                Log.i("StylishPromptCandidate", "${index + 1}/24 ${item.caseId}: ${statuses.last()}")
                assertEquals("Candidate failure is preserved; do not continue with another backend", "GENERATED", statuses.last())
            }
        } finally {
            withContext(NonCancellable) { writeNew(File(directory, "completion.json"), buildJsonObject {
                shared.forEach { (key, value) -> put(key, value) }
                put("recordedCases", statuses.size); put("generatedCases", statuses.count { it == "GENERATED" })
                put("status", if (statuses.size == 24 && statuses.all { it == "GENERATED" }) "GENERATED_AWAITING_REVIEW" else "INCOMPLETE_OR_FAILED")
            }) }
        }
        assertEquals(24, statuses.size)
    }

    private fun validateInput(input: PromptCandidateInput) {
        require(input.schemaVersion == 2 && input.regressionSetId == "all-current-app-verdict-v2")
        require(input.language == "en" && input.maxOutputTokens == 512)
        require(hash(input.originalRequestJson) == input.originalRequestSha256)
        val original = codec.decodeFromString<PromptCandidateOriginal>(input.originalRequestJson)
        require(original.schemaVersion == 1 && original.maxOutputTokens == 512)
        require(original.caseId == input.caseId && original.task == input.task && original.answer == input.answer && original.excerpts == input.excerpts)
        require(original.sourceSha256 == input.sourceCasesSha256 && original.reviewStatus == input.sourceReviewStatus)
        val prefix = if (input.preparedKeys.isEmpty()) "" else "Prepared key: ${input.preparedKeys.joinToString(" / ")}\n"
        require(input.keyPrefixApplied == input.preparedKeys.isNotEmpty() && input.keyPrefixSha256 == prefix.takeIf(String::isNotEmpty)?.let(::hash))
        val eligible = input.sourceCaseType in setOf("NUMERIC", "MULTIPLE_CHOICE", "SHORT_ANSWER") && input.preparedKeys.isNotEmpty()
        if (eligible) {
            val exercise = requireNotNull(input.checkerExercise)
            require(exercise.id == input.caseId && exercise.prompt == input.task && exercise.type.name == input.sourceCaseType && exercise.acceptedAnswers == input.preparedKeys)
            val actual = TutorRequestFactory.closedAuthorityPrefix(exercise, input.answer)
            require(hash(actual) == input.factoryPrefixSha256 && AnswerChecker.check(exercise, input.answer).status.name == input.applicationCheckStatus)
            require(input.authoritativeExplanation == actual + original.authoritativeExplanation)
        } else {
            require(input.checkerExercise == null && input.applicationCheckStatus == null && input.factoryPrefixSha256 == null)
            require(input.authoritativeExplanation == prefix + original.authoritativeExplanation)
        }
        require(input.referenceCheckStatus in setOf("AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK", "INDEPENDENT_AI_REFERENCE_CHECKED_NOT_HUMAN_REVIEW", "INDEPENDENT_AI_REFERENCE_CHECK_RAISED_ISSUES"))
        if (input.referenceCheckStatus == "AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK") require(input.referenceCheckSha256 == null)
        else require(input.referenceCheckSha256?.matches(Regex("[a-f0-9]{64}")) == true)
    }
    private fun memory() = buildJsonObject {
        val m = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        put("pssKb", m.totalPss); put("nativeHeapBytes", Debug.getNativeHeapAllocatedSize())
        put("scope", "PSS and native heap are distinct process counters; API closure does not prove physical memory return")
    }
    private fun writeNew(file: File, value: JsonObject) { require(!file.exists()); atomicWrite(file, value) }
    private fun atomicWrite(file: File, value: JsonObject) {
        val atomic = AtomicFile(file); val out = atomic.startWrite()
        try { out.write((codec.encodeToString(JsonObject.serializer(), value) + "\n").toByteArray()); out.fd.sync(); atomic.finishWrite(out) }
        catch (failure: Throwable) { atomic.failWrite(out); throw failure }
    }
    private fun bytes(text: String) = text.toByteArray(Charsets.UTF_8).size
    private fun hash(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun fileSha(file: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(1 shl 20); while (true) { val n = input.read(b); if (n < 0) break; d.update(b, 0, n) } }
        return d.digest().joinToString("") { "%02x".format(it) }
    }
    companion object {
        const val PRODUCTION_APK_SHA = "006d3928df81e0eaea708cea90d34dcea85d6e602d9017e51a0e2730c3c933c3"
    }
}
@Serializable private data class PromptCandidateBatch(
    val schemaVersion: Int, val experimentId: String, val sourceManifestSha256: String,
    val productionAppApkSha256: String, val candidateSystem: String, val candidateSystemSha256: String,
    val inputByteBudget: Int, val contextTokens: Int, val outputTokens: Int, val cases: List<PromptCandidateCase>,
    val inputRoleLayout: String = "FROZEN_USER_PROMPT",
    val thinkingTokenBudget: Int = 0,
)
private data class CandidateChunk(val text: String, val channelChars: Map<String, Int>)
@Serializable private data class PromptCandidateCase(
    val caseId: String, val baselineRequestSha256: String, val baselineResponseSha256: String,
    val frozenPrompt: String, val frozenPromptSha256: String, val baselineSystemSha256: String,
    val requestJson: PromptCandidateInput, val completeAnswer: String, val completeAnswerSha256: String,
)
@Serializable private data class PromptCandidateInput(
    val schemaVersion: Int, val regressionSetId: String, val caseId: String, val task: String, val answer: String,
    val authoritativeExplanation: String, val excerpts: List<String>, val language: String, val maxOutputTokens: Int,
    val sourceReviewStatus: String, val sourceCasesSha256: String, val originalRequestsSha256: String,
    val originalRequestJson: String, val originalRequestSha256: String, val oldOutputId: String,
    val preparedKeys: List<String>, val keyPrefixApplied: Boolean, val keyPrefixSha256: String?,
    val referenceCheckStatus: String, val referenceCheckSha256: String?, val sourceCaseType: String? = null,
    val checkerExercise: Exercise? = null, val applicationCheckStatus: String? = null, val factoryPrefixSha256: String? = null,
)
@Serializable private data class PromptCandidateOriginal(
    val schemaVersion: Int, val caseId: String, val task: String, val answer: String,
    val authoritativeExplanation: String, val excerpts: List<String>, val reviewStatus: String,
    val sourceSha256: String, val maxOutputTokens: Int,
)
