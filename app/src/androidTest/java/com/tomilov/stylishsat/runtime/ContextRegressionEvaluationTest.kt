package com.tomilov.stylishsat.runtime

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.ai.ModelCatalog
import com.tomilov.stylishsat.ai.LiteRtTutorEngine
import com.tomilov.stylishsat.ai.PromptResult
import com.tomilov.stylishsat.ai.RuntimeServices
import com.tomilov.stylishsat.ai.TutorEvent
import com.tomilov.stylishsat.ai.TutorExcerpt
import com.tomilov.stylishsat.ai.TutorPromptBuilder
import com.tomilov.stylishsat.ai.TutorRequest
import com.tomilov.stylishsat.ai.TutorRequestFactory
import com.tomilov.stylishsat.domain.AnswerChecker
import com.tomilov.stylishsat.domain.ContentSplit
import com.tomilov.stylishsat.domain.Exam
import com.tomilov.stylishsat.domain.Exercise
import com.tomilov.stylishsat.domain.ExerciseType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/** Opt-in generation only. Existing 96-case artifacts and human labels are never touched. */
@RunWith(AndroidJUnit4::class)
class ContextRegressionEvaluationTest {
    private val codec = Json { encodeDefaults = true }

    /** Export from the actual application formatter without loading a model. The
     * input deliberately has no author correctness labels or model responses. */
    @Test fun exportApplicationVerdictRequests(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass contextVerdictExport=true to opt in", args.getString("contextVerdictExport") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val exportId = requireNotNull(args.getString("contextVerdictExportId"))
        require(exportId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        fun input(name: String): ByteArray {
            val supplied = File(requireNotNull(args.getString(name)))
            val file = if (supplied.isAbsolute) supplied else File(context.noBackupFilesDir, supplied.path)
            return readLimited(file, 2 * 1024 * 1024)
        }
        val baseBytes = input("contextVerdictBasePath")
        val metadataBytes = input("contextVerdictMetadataPath")
        require(hash(baseBytes) == args.getString("contextVerdictBaseSha256"))
        require(hash(metadataBytes) == args.getString("contextVerdictMetadataSha256"))
        val base = baseBytes.toString(Charsets.UTF_8).lineSequence().filter(String::isNotBlank)
            .map { codec.decodeFromString<ContextRegressionRequest>(it) }.toList()
        val metadata = codec.decodeFromString<ContextVerdictMetadata>(metadataBytes.toString(Charsets.UTF_8))
        require(metadata.schemaVersion == 1 && metadata.baseRequestSha256 == hash(baseBytes))
        require(metadata.sourceCasesSha256.matches(SHA) && metadata.cpuBaseRequestSha256.matches(SHA))
        require(base.size == 96 && base.map { it.caseId }.toSet().size == 96)
        base.forEach(::validateRequest)
        require(base.all { it.regressionSetId == "all-current-key-prefix-v1" && it.sourceCasesSha256 == metadata.sourceCasesSha256 })
        require(metadata.cases.size == 96 && metadata.cases.map { it.caseId }.toSet() == base.map { it.caseId }.toSet())
        require(metadata.flaggedCaseIds.size == 12 && metadata.flaggedCaseIds.toSet().size == 12)
        val byId = metadata.cases.associateBy { it.caseId }
        val all = base.map { request ->
            val source = byId.getValue(request.caseId)
            require(source.acceptedAnswers == request.preparedKeys)
            val eligible = source.sourceCaseType in CLOSED_TYPES && source.acceptedAnswers.isNotEmpty()
            val exercise = if (eligible) Exercise(id = request.caseId, exam = Exam.valueOf(source.exam),
                skillId = source.skillId, split = ContentSplit.PRACTICE, familyId = "evaluation/${request.caseId}",
                type = ExerciseType.valueOf(source.sourceCaseType), prompt = request.task, options = source.options,
                acceptedAnswers = source.acceptedAnswers, wordLimit = source.wordLimit, author = "Original evaluation fixture; declared type retained") else null
            val prefix = exercise?.let { TutorRequestFactory.closedAuthorityPrefix(it, request.answer) }
            val original = codec.decodeFromString<ContextOriginalEvaluationRequest>(request.originalRequestJson)
            request.copy(schemaVersion = 2, regressionSetId = "all-current-app-verdict-v2", sourceCaseType = source.sourceCaseType,
                checkerExercise = exercise, applicationCheckStatus = exercise?.let { AnswerChecker.check(it, request.answer).status.name },
                factoryPrefixSha256 = prefix?.let { hash(it.toByteArray(Charsets.UTF_8)) },
                authoritativeExplanation = prefix?.let { it + original.authoritativeExplanation } ?: request.authoritativeExplanation)
                .also(::validateRequest)
        }
        val allById = all.associateBy { it.caseId }
        val cpu = metadata.flaggedCaseIds.map { allById.getValue(it).copy(regressionSetId = "current-app-verdict-v2") }
        require(all.count { it.checkerExercise != null } == 24 && cpu.count { it.checkerExercise != null } == 2)
        require(all.count { it.authoritativeExplanation != base.first { old -> old.caseId == it.caseId }.authoritativeExplanation } == 24)
        val directory = File(context.noBackupFilesDir, "context-verdict-export/$exportId")
        directory.parentFile!!.mkdirs()
        require(directory.mkdir()) { "Export IDs are immutable; choose a fresh ID" }
        fun export(rows: List<ContextRegressionRequest>): Pair<String, String> {
            val bytes = (rows.joinToString("\n") { codec.encodeToString(ContextRegressionRequest.serializer(), it) } + "\n").toByteArray(Charsets.UTF_8)
            val digest = hash(bytes)
            val name = "requests-$digest.jsonl"
            writeNew(File(directory, name), bytes)
            return name to digest
        }
        val cpuFile = export(cpu); val allFile = export(all)
        val appHashes = apkHashes(context)
        val testHashes = apkHashes(InstrumentationRegistry.getInstrumentation().context)
        writeNew(File(directory, "source-metadata.json"), metadataBytes)
        writeNew(File(directory, "base-requests.jsonl"), baseBytes)
        writeNewJson(File(directory, "export.json"), buildJsonObject {
            put("schemaVersion", 2); put("exportId", exportId); put("nativeInference", false)
            put("sourceCasesSha256", metadata.sourceCasesSha256); put("metadataSha256", hash(metadataBytes))
            put("baseAll96RequestSha256", hash(baseBytes)); put("baseCpu12RequestSha256", metadata.cpuBaseRequestSha256)
            put("cpu12File", cpuFile.first); put("cpu12Sha256", cpuFile.second); put("cpu12ActualCheckCount", 2)
            put("all96File", allFile.first); put("all96Sha256", allFile.second); put("all96ActualCheckCount", 24)
            put("systemPromptSha256", hash(TutorPromptBuilder.system("en").toByteArray(Charsets.UTF_8)))
            put("appApkSha256", buildJsonObject { appHashes.forEach { (path, digest) -> put(path, digest) } })
            put("testApkSha256", buildJsonObject { testHashes.forEach { (path, digest) -> put(path, digest) } })
            put("checker", "Actual AnswerChecker.check and TutorRequestFactory.closedAuthorityPrefix")
            put("wordLimitPolicy", "Preserve supplied metadata; missing stays null, never inferred from prompt")
            put("referenceStatusPolicy", "Retained exactly from the previous requests; no label upgrade")
            put("humanAcceptance", JsonNull)
        })
        Log.i("StylishContextRegression", "Exported actual-factory CPU12 and all96 requests at ${directory.absolutePath}")
    }

    @Test fun collectCurrentContextRegression(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("Pass -e contextRegression true to opt in", args.getString("contextRegression") == "true")
        val runId = requireNotNull(args.getString("contextRegressionRunId")) { "A separate runId is required" }
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        val requestedPath = requireNotNull(args.getString("contextRegressionRequestPath")) { "A separate request path is required" }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val source = File(requestedPath).let { if (it.isAbsolute) it else File(context.noBackupFilesDir, requestedPath) }.canonicalFile
        require(source.isFile) { "Request file does not exist: $source" }
        val inputBytes = withContext(Dispatchers.IO) { readLimited(source, 2 * 1024 * 1024) }
        val requestFileSha = hash(inputBytes)
        args.getString("contextRegressionExpectedRequestSha256")?.let { require(it == requestFileSha) { "Request digest mismatch" } }
        val inputText = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(inputBytes)).toString()
        val lines = inputText.split('\n').filter(String::isNotBlank)
        val requests = lines.map { codec.decodeFromString<ContextRegressionRequest>(it) }
        require(requests.size == 12 && requests.map { it.caseId }.toSet().size == 12)
        requests.forEach(::validateRequest)
        require(requests.map { it.regressionSetId }.toSet().size == 1 && requests.first().regressionSetId in setOf("current-key-prefix-v1", "current-app-verdict-v2"))
        require(requests.count { it.checkerExercise != null } == if (requests.first().schemaVersion == 2) 2 else 0)
        require(requests.map { it.sourceCasesSha256 }.toSet().size == 1)
        require(requests.map { it.originalRequestsSha256 }.toSet().size == 1)
        require(requests.map { it.referenceCheckSha256 }.toSet().size == 1)
        require(requests.count { it.keyPrefixApplied } == 8)

        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.capability.reason, runtime.capability.supported)
        // Keep this context-only regression on the same CPU path even if the app
        // now offers a separately prepared GPU engine through RuntimeServices.
        val engine = LiteRtTutorEngine(context, runtime.downloads, acceleration = null)
        val model = requireNotNull(runtime.downloads.modelFile(ModelCatalog.gemma)) { "Install and verify Gemma before this opt-in run" }
        val modelSha = fileHash(model)
        require(modelSha == ModelCatalog.gemma.sha256) { "Actual model bytes do not match the installed model identity" }
        val appHashes = apkHashes(context)
        val testHashes = apkHashes(instrumentation.context)
        val appVersion = context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode
        val outputParent = File(context.noBackupFilesDir, "context-regression").apply { mkdirs() }
        val outputDirectory = File(outputParent, runId)
        require(outputDirectory.mkdir()) { "Run directory already exists; use a new runId rather than overwriting any result" }
        val shared = buildJsonObject {
            put("schemaVersion", requests.first().schemaVersion); put("runId", runId); put("regressionSetId", requests.first().regressionSetId)
            put("requestFileSha256", requestFileSha); put("requestInputPath", source.absolutePath)
            put("sourceCasesSha256", requests.first().sourceCasesSha256)
            put("originalRequestsSha256", requests.first().originalRequestsSha256)
            put("modelId", ModelCatalog.gemma.id); put("modelSha256", modelSha)
            put("modelHashVerification", "STREAMED_ACTUAL_FILE_SHA256_MATCHES_CATALOG")
            put("appPackage", context.packageName); put("appVersionCode", appVersion)
            put("appApkSha256", buildJsonObject { appHashes.forEach { (path, value) -> put(path, value) } })
            put("testApkSha256", buildJsonObject { testHashes.forEach { (path, value) -> put(path, value) } })
            put("device", Build.MODEL); put("api", Build.VERSION.SDK_INT)
            put("runtime", "LiteRT-LM 0.16.1"); put("requestedBackend", REQUIRED_BACKEND)
            put("contextTokens", TutorPromptBuilder.CONTEXT_TOKENS); put("maxOutputTokens", TutorPromptBuilder.OUTPUT_TOKENS)
            put("systemOverride", false); put("runtimePolicy", "Production LiteRtTutorEngine with acceleration=null; explicit CPU isolation")
            put("humanAcceptance", JsonNull)
        }
        withContext(Dispatchers.IO) {
            writeNew(File(outputDirectory, "requests.jsonl"), inputBytes)
            writeNewJson(File(outputDirectory, "run.json"), buildJsonObject {
                shared.forEach { (key, value) -> put(key, value) }
                put("startedAtUtc", Instant.now().toString()); put("caseCount", requests.size)
                put("referenceStatuses", buildJsonArray { requests.map { it.referenceCheckStatus }.distinct().forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("referenceCheckSha256", requests.first().referenceCheckSha256?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                put("status", "COLLECTION_STARTED"); put("reviewStatus", "UNREVIEWED")
            })
        }
        val statuses = mutableListOf<String>()
        try {
            for ((index, input) in requests.withIndex()) {
                currentCoroutineContext().ensureActive()
                val request = TutorRequest(input.task, input.answer, input.authoritativeExplanation,
                    input.language, input.excerpts.mapIndexed { excerptIndex, text -> TutorExcerpt("${input.caseId}:$excerptIndex", text) })
                val prompt = TutorPromptBuilder.build(request)
                val response = StringBuilder()
                val startedAt = Instant.now().toString()
                val startedMillis = SystemClock.elapsedRealtime()
                var status = "INCOMPLETE"
                var detail: String? = null
                var timing: TutorEvent.Complete? = null
                var cancelled: CancellationException? = null
                try {
                    withTimeout(120_000) {
                        engine.explain(request).collect { event ->
                            when (event) {
                                is TutorEvent.Text -> response.append(event.delta)
                                is TutorEvent.Complete -> { timing = event; status = "GENERATED" }
                                is TutorEvent.TooLong -> { status = "TOO_LONG"; detail = "${event.actualBudgetBytes} bytes exceed ${event.allowedBudgetBytes}; input preserved" }
                                is TutorEvent.Unavailable -> { status = "UNAVAILABLE"; detail = event.reason }
                                is TutorEvent.Failure -> { status = "FAILED"; detail = event.message }
                                TutorEvent.Loading -> Unit
                            }
                        }
                    }
                } catch (error: TimeoutCancellationException) {
                    status = "TIMED_OUT"; detail = error.message
                } catch (error: CancellationException) {
                    status = "CANCELLED"; detail = error.message; cancelled = error
                } catch (error: Exception) {
                    status = "FAILED"; detail = "${error.javaClass.simpleName}: ${error.message}"
                }
                if (status == "GENERATED" && timing?.backend != REQUIRED_BACKEND) {
                    status = "BACKEND_MISMATCH"
                    detail = "Required $REQUIRED_BACKEND but completion reported ${timing?.backend}"
                }
                val row = buildJsonObject {
                    shared.forEach { (key, value) -> put(key, value) }
                    put("caseId", input.caseId); put("outputId", "$runId:${input.caseId}")
                    put("oldOutputId", input.oldOutputId); put("status", status); put("response", response.toString())
                    put("error", detail?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    put("requestLineSha256", hash(lines[index].toByteArray(Charsets.UTF_8)))
                    put("originalRequestSha256", input.originalRequestSha256)
                    put("language", input.language); put("keyPrefixApplied", input.keyPrefixApplied)
                    put("backend", timing?.backend)
                    put("preparedKeyCount", input.preparedKeys.size)
                    put("sourceCaseType", input.sourceCaseType); put("applicationCheckStatus", input.applicationCheckStatus)
                    put("factoryPrefixSha256", input.factoryPrefixSha256)
                    put("keyPrefixSha256", input.keyPrefixSha256?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    put("referenceCheckStatus", input.referenceCheckStatus)
                    put("referenceCheckSha256", input.referenceCheckSha256?.let { kotlinx.serialization.json.JsonPrimitive(it) } ?: JsonNull)
                    put("sourceReviewStatus", input.sourceReviewStatus); put("reviewStatus", "UNREVIEWED")
                    put("systemPromptSha256", hash(TutorPromptBuilder.system(input.language).toByteArray(Charsets.UTF_8)))
                    put("mandatoryCoreSha256", hash(TutorPromptBuilder.core(request).toByteArray(Charsets.UTF_8)))
                    when (prompt) {
                        is PromptResult.Fits -> {
                            put("sentPromptSha256", hash(prompt.text.toByteArray(Charsets.UTF_8)))
                            put("omittedExcerpts", prompt.omittedExcerpts); put("promptFits", true)
                        }
                        is PromptResult.TooLong -> {
                            put("sentPromptSha256", JsonNull); put("promptFits", false)
                            put("actualBudgetBytes", prompt.actualBytes); put("allowedBudgetBytes", prompt.allowedBytes)
                        }
                    }
                    put("startedAtUtc", startedAt); put("finishedAtUtc", Instant.now().toString())
                    put("elapsedMillis", SystemClock.elapsedRealtime() - startedMillis)
                    put("loadMillis", timing?.loadMillis); put("firstTokenMillis", timing?.firstTokenMillis)
                }
                withContext(NonCancellable + Dispatchers.IO) {
                    writeNewJson(File(outputDirectory, "${index + 1}-${input.caseId}.json"), row)
                }
                statuses += status
                Log.i("StylishContextRegression", "${index + 1}/12 ${input.caseId}: $status; output=${outputDirectory.absolutePath}")
                cancelled?.let { throw it }
                check(status != "BACKEND_MISMATCH") { detail.orEmpty() }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.IO) {
                writeNewJson(File(outputDirectory, "completion.json"), buildJsonObject {
                    shared.forEach { (key, value) -> put(key, value) }
                    put("finishedAtUtc", Instant.now().toString()); put("recordedCases", statuses.size)
                    put("generatedCases", statuses.count { it == "GENERATED" })
                    put("status", if (statuses.size == 12 && statuses.all { it == "GENERATED" }) "GENERATED_AWAITING_REVIEW" else "INCOMPLETE_OR_FAILED")
                    put("statusCounts", buildJsonObject { statuses.groupingBy { it }.eachCount().forEach { (key, count) -> put(key, count) } })
                    put("independentReferencesConfirmed", requests.all { it.referenceCheckStatus == "INDEPENDENT_AI_REFERENCE_CHECKED_NOT_HUMAN_REVIEW" })
                    put("criticalContradictions", JsonNull); put("usefulAndGroundedRate", JsonNull)
                    put("passesReleaseCriterion", JsonNull); put("humanAcceptance", JsonNull)
                })
            }
        }
        assertTrue("All 12 cases must generate; failures remain preserved in $outputDirectory", statuses.size == 12 && statuses.all { it == "GENERATED" })
    }

    private fun validateRequest(request: ContextRegressionRequest) {
        require(request.regressionSetId in setOf("current-key-prefix-v1", "all-current-key-prefix-v1", "current-app-verdict-v2", "all-current-app-verdict-v2"))
        require(request.schemaVersion == if (request.regressionSetId.endsWith("v2")) 2 else 1)
        require(request.caseId.matches(Regex("[A-Za-z0-9_-]{1,100}")))
        require(request.language == "en" && request.maxOutputTokens == TutorPromptBuilder.OUTPUT_TOKENS)
        require(request.sourceCasesSha256.matches(SHA) && request.originalRequestsSha256.matches(SHA))
        require(hash(request.originalRequestJson.toByteArray(Charsets.UTF_8)) == request.originalRequestSha256)
        val original = codec.decodeFromString<ContextOriginalEvaluationRequest>(request.originalRequestJson)
        require(original.schemaVersion == 1 && original.caseId == request.caseId && original.maxOutputTokens == 512)
        require(original.task == request.task && original.answer == request.answer && original.excerpts == request.excerpts)
        require(original.sourceSha256 == request.sourceCasesSha256 && original.reviewStatus == request.sourceReviewStatus)
        require(request.preparedKeys.all(String::isNotBlank))
        val prefix = if (request.preparedKeys.isEmpty()) "" else "Prepared key: ${request.preparedKeys.joinToString(" / ")}\n"
        require(request.keyPrefixApplied == request.preparedKeys.isNotEmpty())
        if (request.schemaVersion == 1) {
            require(request.sourceCaseType == null && request.checkerExercise == null && request.applicationCheckStatus == null && request.factoryPrefixSha256 == null)
            require(request.authoritativeExplanation == prefix + original.authoritativeExplanation)
        } else {
            require(!request.sourceCaseType.isNullOrBlank())
            val eligible = request.sourceCaseType in CLOSED_TYPES && request.preparedKeys.isNotEmpty()
            if (eligible) {
                val exercise = requireNotNull(request.checkerExercise)
                require(exercise.type.name == request.sourceCaseType && exercise.acceptedAnswers == request.preparedKeys)
                require(exercise.id == request.caseId && exercise.prompt == request.task)
                val actualPrefix = TutorRequestFactory.closedAuthorityPrefix(exercise, request.answer)
                require(request.applicationCheckStatus == AnswerChecker.check(exercise, request.answer).status.name)
                require(request.factoryPrefixSha256 == hash(actualPrefix.toByteArray(Charsets.UTF_8)))
                require(request.authoritativeExplanation == actualPrefix + original.authoritativeExplanation)
            } else {
                require(request.checkerExercise == null && request.applicationCheckStatus == null && request.factoryPrefixSha256 == null)
                require(request.authoritativeExplanation == prefix + original.authoritativeExplanation)
            }
        }
        require(request.keyPrefixSha256 == prefix.takeIf(String::isNotEmpty)?.let { hash(it.toByteArray(Charsets.UTF_8)) })
        require(request.referenceCheckStatus in setOf("AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK",
            "INDEPENDENT_AI_REFERENCE_CHECKED_NOT_HUMAN_REVIEW", "INDEPENDENT_AI_REFERENCE_CHECK_RAISED_ISSUES"))
        if (request.referenceCheckStatus == "AUTHOR_PROPOSED_PENDING_INDEPENDENT_CHECK") require(request.referenceCheckSha256 == null)
        else require(request.referenceCheckSha256?.matches(SHA) == true)
    }

    private suspend fun apkHashes(context: Context): Map<String, String> {
        val info = context.applicationInfo
        val paths = (listOf(info.sourceDir) + info.splitSourceDirs.orEmpty()).distinct().sorted()
        val result = linkedMapOf<String, String>()
        for (path in paths) result[path] = fileHash(File(path))
        return result
    }

    private suspend fun fileHash(file: File): String = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(65_536)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = stream.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readLimited(file: File, maximum: Int): ByteArray = file.inputStream().use { stream ->
        val result = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            require(result.size() + count <= maximum) { "Regression request exceeds the bounded input size" }
            result.write(buffer, 0, count)
        }
        result.toByteArray()
    }

    private fun writeNewJson(file: File, value: JsonObject) = writeNew(file,
        (codec.encodeToString(JsonObject.serializer(), value) + "\n").toByteArray(Charsets.UTF_8))

    private fun writeNew(file: File, bytes: ByteArray) {
        require(!file.exists()) { "Refusing to overwrite immutable output: $file" }
        val temporary = File(file.parentFile, ".${file.name}.${UUID.randomUUID()}.part")
        FileOutputStream(temporary).use { stream -> stream.write(bytes); stream.fd.sync() }
        // The run directory is exclusively created by this collector; no replace option is used.
        Files.move(temporary.toPath(), file.toPath())
    }

    companion object {
        private val SHA = Regex("[a-f0-9]{64}")
        private const val REQUIRED_BACKEND = "CPU(4)"
        private val CLOSED_TYPES = setOf("MULTIPLE_CHOICE", "NUMERIC", "SHORT_ANSWER")
    }
}

@Serializable
private data class ContextRegressionRequest(
    val schemaVersion: Int, val regressionSetId: String, val caseId: String,
    val task: String, val answer: String, val authoritativeExplanation: String,
    val excerpts: List<String>, val language: String, val maxOutputTokens: Int,
    val sourceReviewStatus: String, val sourceCasesSha256: String, val originalRequestsSha256: String,
    val originalRequestJson: String, val originalRequestSha256: String, val oldOutputId: String,
    val preparedKeys: List<String>, val keyPrefixApplied: Boolean, val keyPrefixSha256: String?,
    val referenceCheckStatus: String, val referenceCheckSha256: String?,
    val sourceCaseType: String? = null, val checkerExercise: Exercise? = null,
    val applicationCheckStatus: String? = null, val factoryPrefixSha256: String? = null,
)

@Serializable
private data class ContextOriginalEvaluationRequest(
    val schemaVersion: Int, val caseId: String, val task: String, val answer: String,
    val authoritativeExplanation: String, val excerpts: List<String>, val reviewStatus: String,
    val sourceSha256: String, val maxOutputTokens: Int,
)

@Serializable
private data class ContextVerdictMetadata(
    val schemaVersion: Int, val sourceCasesSha256: String, val baseRequestSha256: String,
    val cpuBaseRequestSha256: String, val flaggedCaseIds: List<String>, val cases: List<ContextVerdictSource>,
)

@Serializable
private data class ContextVerdictSource(
    val caseId: String, val sourceCaseType: String, val exam: String, val skillId: String, val prompt: String,
    val options: List<String>, val acceptedAnswers: List<String>, val wordLimit: Int?,
)
