package com.tomilov.stylishsat.runtime

import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.util.AtomicFile
import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomilov.stylishsat.data.ContentRepository
import com.tomilov.stylishsat.data.StudyDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.google.ai.edge.litertlm.*
import com.tomilov.stylishsat.ai.*
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** Opt-in32-token latency experiment. Never changes the production CPU4 backend. */
@RunWith(AndroidJUnit4::class)
class BackendLatencyBenchmarkTest {
    @Test fun measureLongestEssayBackend(): Unit = runBlocking(Dispatchers.IO) {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("backendLatencyValidation") == "true")
        assertEquals("CPH2411", Build.MODEL)
        val backendName = requireNotNull(args.getString("benchmarkBackend"))
        require(backendName in setOf("cpu4", "cpu6", "cpu8", "gpu"))
        val runId = requireNotNull(args.getString("benchmarkRunId"))
        require(runId.matches(Regex("[A-Za-z0-9_-]{1,80}")))
        val expectedApkSha = requireNotNull(args.getString("benchmarkAppSha256"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val appSha = fileSha(File(context.applicationInfo.sourceDir))
        assertEquals(expectedApkSha, appSha)
        val runtime = RuntimeServices.get(context)
        assertTrue(runtime.capability.supported)
        assertTrue(runtime.downloads.isInstalled(ModelCatalog.gemma))
        val model = requireNotNull(runtime.downloads.modelFile(ModelCatalog.gemma))
        val bankBytes = context.assets.open("content/seed-v1.json").use { it.readBytes() }
        val bank = ContentPackCodec.decode(bankBytes.toString(Charsets.UTF_8))
        val essay = bank.exercises.single { it.id == "ielts-write-v3-task2-work-placements" }
        val essayAnswer = requireNotNull(essay.sampleAnswer)
        assertEquals(309, essayAnswer.split(Regex("\\s+")).size)
        val closed = bank.exercises.single { it.id == "sat-algebra-p3" }
        val db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        val closedRequest = try {
            val repository = ContentRepository(context, db)
            repository.initialize()
            val lessons = repository.contextFor(closed.skillId, closed.prompt)
            assertTrue(lessons.isNotEmpty())
            val base = TutorRequestFactory.create(closed, "3", Language.EN)
            base.copy(excerpts = base.excerpts + lessons.map { TutorExcerpt(it.id, it.body.text(Language.EN) + "\n" + it.workedExample.text(Language.EN)) })
        } finally { db.close() }
        val benchmarkCases = listOf(
            BackendInput("essay", essay, essayAnswer, TutorRequestFactory.create(essay, essayAnswer, Language.EN)),
            BackendInput("closed-rag", closed, "3", closedRequest),
        )
        val directory = File(context.noBackupFilesDir, "testing/backend-benchmark/$runId/$backendName").apply { mkdirs() }
        val cache = File(context.noBackupFilesDir, "litert-cache").apply { mkdirs() }
        val power = context.getSystemService(PowerManager::class.java)
        for (input in benchmarkCases) {
        val exercise = input.exercise
        val answer = input.answer
        val request = input.request
        val prompt = TutorPromptBuilder.build(request) as PromptResult.Fits
        assertTrue(prompt.text.contains(answer))
        val requestSha = sha((prompt.system + "\n" + prompt.text).toByteArray())
        if (input.name == "essay") assertEquals("5f51a0fdda92a25b561f7147d26c1a19f33dfb8c0f5d4894affa4d7241fb09de", requestSha)
        for (repeat in 1..2) {
            val target = File(directory, "${input.name}-trial-$repeat.json")
            require(!target.exists()) { "Measured trials are immutable; use a new benchmarkRunId" }
            RuntimeResourceGate.exclusive {
                RuntimeCapability.requireAvailableMemory(context)
                val samples = Collections.synchronizedList(mutableListOf<BackendMemorySample>())
                val baseline = System.nanoTime()
                fun snapshot(): BackendMemorySample {
                    val mi = Debug.MemoryInfo().also(Debug::getMemoryInfo)
                    val proc = File("/proc/self/status").readLines().mapNotNull { line ->
                        val parts = line.trim().split(Regex("\\s+"))
                        if (parts.size >= 2 && parts[0] in setOf("VmRSS:", "VmHWM:")) parts[0] to parts[1].toLong() else null
                    }.toMap()
                    return BackendMemorySample((System.nanoTime() - baseline) / 1_000_000,
                        mi.totalPss, Debug.getNativeHeapAllocatedSize(),
                        Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory(),
                        proc["VmRSS:"] ?: -1, proc["VmHWM:"] ?: -1,
                        mi.memoryStats["summary.graphics"]?.toLongOrNull(),
                        runtime.capability.availableMemoryBytes, power.currentThermalStatus)
                }
                samples += snapshot()
                val monitor = launch(Dispatchers.Default) {
                    while (isActive) { delay(500); samples += snapshot() }
                }
                var phase = "INITIALIZE"
                fun recordPhase() = atomicWrite(File(directory, "phase.json"), Json.encodeToString(
                    BackendPhase(runId, backendName, input.name, repeat, phase, Process.myPid(), System.currentTimeMillis(), appSha, requestSha)))
                var engine: Engine? = null
                var initMillis: Long? = null
                var conversationMillis: Long? = null
                var firstMillis: Long? = null
                var generationMillis: Long? = null
                var failure: String? = null
                var status = "FAILED"
                val output = StringBuilder()
                var chunks = 0
                val initStart = System.nanoTime()
                try {
                    recordPhase()
                    val backend = when (backendName) {
                        "gpu" -> Backend.GPU()
                        else -> Backend.CPU(threadCount = backendName.removePrefix("cpu").toInt())
                    }
                    engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = backend,
                        maxNumTokens = 4096, cacheDir = cache.absolutePath))
                    engine.initialize()
                    initMillis = (System.nanoTime() - initStart) / 1_000_000
                    samples += snapshot()
                    phase = "CONVERSATION"; recordPhase()
                    val conversationStart = System.nanoTime()
                    engine.createConversation(ConversationConfig(systemInstruction = Contents.of(prompt.system),
                        samplerConfig = SamplerConfig(topK = 20, topP = 0.9, temperature = 0.2),
                        automaticToolCalling = false, maxOutputToken = 512,
                        thinkingConfig = ThinkingConfig(enableThinking = false, thinkingTokenBudget = 0))).use { conversation ->
                        conversationMillis = (System.nanoTime() - conversationStart) / 1_000_000
                        phase = "GENERATE"; recordPhase()
                        val inferenceStart = System.nanoTime()
                        val first = AtomicLong(-1)
                        val callbackFailure = AtomicReference<String?>(null)
                        val terminal = CompletableDeferred<Unit>()
                        conversation.sendMessageAsync(prompt.text, object : MessageCallback {
                            override fun onMessage(message: Message) {
                                val text = message.toString()
                                if (text.isNotEmpty()) first.compareAndSet(-1, System.nanoTime())
                                synchronized(output) { output.append(text); chunks++ }
                            }
                            override fun onDone() { terminal.complete(Unit) }
                            override fun onError(throwable: Throwable) {
                                callbackFailure.set("${throwable.javaClass.name}: ${throwable.message}"); terminal.complete(Unit)
                            }
                        }, maxOutputToken = 32)
                        try { withTimeout(150_000) { terminal.await() } }
                        finally {
                            if (!terminal.isCompleted) conversation.cancelProcess()
                            withContext(NonCancellable) { terminal.await() }
                        }
                        firstMillis = first.get().takeIf { it >= 0 }?.let { (it - inferenceStart) / 1_000_000 }
                        generationMillis = (System.nanoTime() - inferenceStart) / 1_000_000
                        failure = callbackFailure.get()
                        status = if (failure == null && firstMillis != null && output.isNotBlank()) "GENERATED_LATENCY_ONLY" else "FAILED"
                    }
                } catch (cancelled: CancellationException) {
                    failure = "${cancelled.javaClass.name}: ${cancelled.message}"
                    status = "TIMED_OUT_OR_CANCELLED"
                } catch (error: Exception) {
                    failure = "${error.javaClass.name}: ${error.message}"
                } catch (error: LinkageError) {
                    failure = "${error.javaClass.name}: ${error.message}"
                } finally {
                    val failedPhase = phase
                    phase = "CLOSE"; recordPhase()
                    try { engine?.let { if (it.isInitialized()) it.close() } }
                    catch (error: Exception) { failure = "${failure.orEmpty()} Close: ${error.message}"; status = "FAILED" }
                    monitor.cancelAndJoin()
                    samples += snapshot()
                    val measurements = synchronized(samples) { samples.toList() }
                    val row = BackendLatencyResult(runId, backendName, input.name, repeat, request.excerpts.map { it.sourceId }, prompt.omittedExcerpts, status, failedPhase, failure,
                        appSha, sha(bankBytes), bank.version, exercise.id, exercise.version, sha(answer.toByteArray()),
                        answer, prompt.system, prompt.text, requestSha, ModelCatalog.gemma.sha256,
                        model.length(), model.lastModified(), "LiteRT-LM0.16.1", Build.FINGERPRINT, Process.myPid(),
                        4096, 512, 512, 32, true, cache.absolutePath,
                        "Fresh Engine per trial; existing disk/OS caches retained. Trial1 is first Engine in this process; no physical-cache-cold claim.",
                        initMillis, (System.nanoTime() - initStart) / 1_000_000, conversationMillis, firstMillis,
                        generationMillis, chunks, output.toString(), measurements,
                        measurements.maxOf { it.totalPssKb }, measurements.maxOf { it.nativeHeapBytes },
                        measurements.maxOf { it.vmRssKb }, measurements.maxOf { it.vmHighWaterKb })
                    atomicWrite(target, Json.encodeToString(row))
                    Log.i("StylishBackendBench", "$backendName trial$repeat $status init=$initMillis first=$firstMillis pss=${row.sampledPeakPssKb}")
                }
                if (status != "GENERATED_LATENCY_ONLY") return@exclusive
            }
            val completed = Json.decodeFromString<BackendLatencyResult>(target.readText())
            if (completed.status != "GENERATED_LATENCY_ONLY") return@runBlocking
        }
        }
    }
    private fun atomicWrite(file: File, text: String) {
        val atomic = AtomicFile(file); val stream = atomic.startWrite()
        try { stream.write(text.toByteArray()); stream.fd.sync(); atomic.finishWrite(stream) }
        catch (error: Throwable) { atomic.failWrite(stream); throw error }
    }
    private fun fileSha(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(65_536)
        while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
}
private data class BackendInput(val name: String, val exercise: Exercise, val answer: String, val request: TutorRequest)
@Serializable private data class BackendPhase(val runId: String, val backend: String, val inputCase: String, val repeat: Int, val phase: String, val pid: Int, val atUtcMillis: Long, val appSha256: String, val requestSha256: String)
@Serializable private data class BackendMemorySample(val elapsedMillis: Long, val totalPssKb: Int, val nativeHeapBytes: Long, val javaHeapBytes: Long, val vmRssKb: Long, val vmHighWaterKb: Long, val graphicsKb: Long?, val availableRamBytes: Long, val thermalStatus: Int)
@Serializable private data class BackendLatencyResult(
    val runId: String, val backend: String, val inputCase: String, val repeat: Int, val requestedExcerptIds: List<String>, val omittedExcerpts: Int, val status: String, val lastWorkPhase: String, val failure: String?,
    val appApkSha256: String, val bankSha256: String, val bankVersion: Int, val exerciseId: String, val exerciseVersion: Int,
    val completeAnswerSha256: String, val completeAnswer: String, val system: String, val prompt: String, val requestSha256: String,
    val modelSha256: String, val modelBytes: Long, val modelModifiedMillis: Long, val runtime: String, val deviceFingerprint: String, val pid: Int,
    val contextTokens: Int, val factoryOutputReservation: Int, val conversationOutputLimit: Int, val latencyDecodeLimit: Int,
    val latencyOnly: Boolean, val cacheDirectory: String, val initializationScope: String,
    val initializationMillis: Long?, val totalTrialMillis: Long, val conversationCreationMillis: Long?, val firstTokenMillis: Long?,
    val generationMillis: Long?, val nativeMessageChunks: Int, val response: String, val memorySamples: List<BackendMemorySample>,
    val sampledPeakPssKb: Int, val sampledPeakNativeHeapBytes: Long, val sampledPeakRssKb: Long, val processHighWaterRssKb: Long,
)
