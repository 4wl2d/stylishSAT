package com.tomilov.stylishsat.ai

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.AtomicFile
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

sealed interface LocalAccelerationState {
    data class Idle(val eligible: Boolean, val reason: String) : LocalAccelerationState
    data class Preparing(val startedAtUtcMillis: Long) : LocalAccelerationState
    data class Ready(val preparedAtUtcMillis: Long, val preparationMillis: Long,
        val initializationMillis: Long) : LocalAccelerationState
    data class Failed(val message: String) : LocalAccelerationState
}

/** Explicit, narrowly validated GPU preparation. Construction/refresh do only small
 * receipt reads and file stats; they never initialize a model or hash its large file. */
class LocalAcceleration(context: Context, private val downloads: ModelDownloadManager) {
    private val appContext = context.applicationContext
    private val receiptFile = AtomicFile(File(appContext.noBackupFilesDir, "prepared-gpu.json"))
    private val cacheDirectory = File(appContext.noBackupFilesDir, "litert-cache")
    private val preparationMutex = Mutex()
    private val receiptLock = Any()
    private val mutableState = MutableStateFlow<LocalAccelerationState>(idleState())
    val state: StateFlow<LocalAccelerationState> = mutableState.asStateFlow()
    val eligible: Boolean get() = profileMatches()

    init { refresh() }

    /** Lightweight recheck for startup, model installation changes, or returning to Settings. */
    fun refresh() = synchronized(receiptLock) {
        if (mutableState.value is LocalAccelerationState.Preparing) return@synchronized
        val receipt = validReceipt()
        mutableState.value = receipt?.let { LocalAccelerationState.Ready(it.preparedAtUtcMillis,
            it.preparationMillis, it.initializationMillis) } ?: (mutableState.value as? LocalAccelerationState.Failed ?: idleState())
    }

    /** Call from an explicit UI action. Duplicate clicks do not queue repeated preparation. */
    suspend fun prepare(): Unit = withContext(Dispatchers.IO) {
        if (!preparationMutex.tryLock()) return@withContext
        val start = SystemClock.elapsedRealtime()
        val preparationContext = currentCoroutineContext()
        try {
            synchronized(receiptLock) {
                mutableState.value = LocalAccelerationState.Preparing(System.currentTimeMillis())
            }
            RuntimeResourceGate.exclusive {
                check(profileMatches()) { "GPU preparation is available only on the verified CPH2411 Android15 profile. CPU remains available on other supported devices." }
                RuntimeCapability.requireAvailableMemory(appContext)
                val model = downloads.modelFile(ModelCatalog.gemma)
                    ?: error("Install and verify Gemma before preparing GPU acceleration.")
                val before = modelIdentity(model)
                val hashStart = SystemClock.elapsedRealtime()
                check(sha256(model) == ModelCatalog.gemma.sha256) { "The installed Gemma file does not match its pinned checksum. GPU was not enabled." }
                val verificationMillis = SystemClock.elapsedRealtime() - hashStart
                check(before == modelIdentity(model)) { "The model changed during verification. Prepare again after installation finishes." }
                currentCoroutineContext().ensureActive()
                cacheDirectory.mkdirs()
                val engine = Engine(EngineConfig(modelPath = model.absolutePath, backend = Backend.GPU(),
                    maxNumTokens = TutorPromptBuilder.CONTEXT_TOKENS, cacheDir = cacheDirectory.absolutePath))
                val initStart = SystemClock.elapsedRealtime()
                val initMillis: Long
                try {
                    engine.initialize()
                    initMillis = SystemClock.elapsedRealtime() - initStart
                } finally {
                    // Readiness is never committed while a native GPU resource is still owned.
                    if (engine.isInitialized()) engine.close()
                }
                currentCoroutineContext().ensureActive()
                check(before == modelIdentity(model) && downloads.isInstalled(ModelCatalog.gemma)) { "The installed model changed during preparation." }
                // Exact0.16.1 profile: require the persisted GPU program and weight caches.
                // Missing/truncated caches invalidate readiness without silently rebuilding at startup.
                val prefix = "${model.name}_${before.modifiedMillis / 1000}_${before.bytes}_"
                val cacheFiles = cacheDirectory.listFiles().orEmpty().filter {
                    it.isFile && it.name.startsWith(prefix) &&
                        (it.name.endsWith("_mldrift_program_cache.bin") || it.name.endsWith("_mldrift_weight_cache.bin")) && it.length() > 0
                }.map { PreparedGpuCache(it.name, it.length()) }
                check(cacheFiles.any { it.fileName.endsWith("_mldrift_program_cache.bin") } &&
                    cacheFiles.any { it.fileName.endsWith("_mldrift_weight_cache.bin") }) { "GPU initialized but persistent preparation caches are unavailable. CPU remains the fallback backend." }
                val receipt = PreparedGpuReceipt(1, ModelCatalog.gemma.id, ModelCatalog.gemma.sha256,
                    RUNTIME_VERSION, TutorPromptBuilder.CONTEXT_TOKENS, Build.FINGERPRINT,
                    Build.MODEL, Build.VERSION.SDK_INT, before.fileName, before.bytes, before.modifiedMillis,
                    cacheFiles, System.currentTimeMillis(), SystemClock.elapsedRealtime() - start,
                    verificationMillis, initMillis)
                synchronized(receiptLock) {
                    preparationContext.ensureActive()
                    val stream = receiptFile.startWrite()
                    try {
                        stream.write(Json.encodeToString(receipt).toByteArray(Charsets.UTF_8))
                        stream.fd.sync(); receiptFile.finishWrite(stream)
                    } catch (error: Throwable) { receiptFile.failWrite(stream); throw error }
                    mutableState.value = LocalAccelerationState.Ready(receipt.preparedAtUtcMillis,
                        receipt.preparationMillis, receipt.initializationMillis)
                }
            }
        } catch (cancelled: CancellationException) {
            synchronized(receiptLock) { receiptFile.delete(); mutableState.value = idleState() }
            throw cancelled
        } catch (error: Exception) {
            invalidate(error.message ?: "GPU preparation failed. CPU remains the fallback backend.")
        } catch (error: LinkageError) {
            invalidate(error.message ?: "The GPU runtime is unavailable. CPU remains the fallback backend.")
        } finally { preparationMutex.unlock() }
    }

    /** Intended for inference under RuntimeResourceGate. No native work or full-file hashing. */
    fun shouldUseGpu(): Boolean = synchronized(receiptLock) {
        if (mutableState.value !is LocalAccelerationState.Ready) return@synchronized false
        if (validReceipt() != null) true else {
            mutableState.value = idleState(); false
        }
    }

    /** Invalidates only our small readiness receipt, never model files or SDK caches. */
    fun invalidate(reason: String) = synchronized(receiptLock) {
        receiptFile.delete()
        mutableState.value = LocalAccelerationState.Failed(reason)
    }

    private fun validReceipt(): PreparedGpuReceipt? = runCatching {
        if (!profileMatches()) return@runCatching null
        val model = downloads.modelFile(ModelCatalog.gemma) ?: return@runCatching null
        val receipt = receiptFile.openRead().use { input ->
            require(input.channel.size() in 1L..16_384L)
            Json.decodeFromString<PreparedGpuReceipt>(input.readBytes().toString(Charsets.UTF_8))
        }
        if (!receipt.matches(modelIdentity(model), Build.FINGERPRINT, Build.MODEL, Build.VERSION.SDK_INT)) return@runCatching null
        val cachePrefix = "${model.name}_${model.lastModified() / 1000}_${model.length()}_"
        if (!receipt.cacheFiles.any { it.fileName.endsWith("_mldrift_program_cache.bin") } ||
            !receipt.cacheFiles.any { it.fileName.endsWith("_mldrift_weight_cache.bin") } || !receipt.cacheFiles.all { entry ->
                entry.fileName.matches(Regex("[A-Za-z0-9._-]+")) && entry.fileName.startsWith(cachePrefix) && entry.bytes > 0 &&
                    File(cacheDirectory, entry.fileName).let { it.isFile && it.length() >= entry.bytes }
            }) return@runCatching null
        receipt
    }.getOrNull()

    private fun idleState() = if (profileMatches()) LocalAccelerationState.Idle(true, "GPU has not been prepared for this model and device. CPU remains the default backend.")
        else LocalAccelerationState.Idle(false, "This device profile has not been validated for GPU acceleration. CPU remains the default backend.")
    private fun profileMatches() = verifiedProfile(Build.MODEL, Build.VERSION.SDK_INT,
        RuntimeCapability.inspect(appContext).totalMemoryBytes, Build.SUPPORTED_ABIS.toSet(), Build.FINGERPRINT)
    private fun modelIdentity(file: File) = PreparedModelIdentity(file.name, file.length(), file.lastModified())
    private suspend fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val bytes = ByteArray(1024 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(bytes); if (count < 0) break
                digest.update(bytes, 0, count)
            }
        }
        return digest.digest().joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }
    }

    companion object {
        const val RUNTIME_VERSION = "0.16.1"
        const val VERIFIED_FINGERPRINT = "OnePlus/CPH2411/OP5566L1:15/AP3A.240617.008/S.24910f5-1-a97a7:user/release-keys"
        internal fun verifiedProfile(model: String, api: Int, ramBytes: Long, abis: Set<String>, fingerprint: String): Boolean =
            model == "CPH2411" && api == 35 && ramBytes in (10L * 1024 * 1024 * 1024) until (13L * 1024 * 1024 * 1024) &&
                "arm64-v8a" in abis && fingerprint == VERIFIED_FINGERPRINT && TutorPromptBuilder.CONTEXT_TOKENS == 4096
    }
}

internal data class PreparedModelIdentity(val fileName: String, val bytes: Long, val modifiedMillis: Long)
@Serializable internal data class PreparedGpuCache(val fileName: String, val bytes: Long)
@Serializable internal data class PreparedGpuReceipt(
    val schemaVersion: Int, val modelId: String, val modelSha256: String, val runtimeVersion: String,
    val contextTokens: Int, val buildFingerprint: String, val deviceModel: String, val deviceApi: Int,
    val modelFileName: String, val modelBytes: Long, val modelModifiedMillis: Long,
    val cacheFiles: List<PreparedGpuCache>, val preparedAtUtcMillis: Long, val preparationMillis: Long,
    val verificationMillis: Long, val initializationMillis: Long,
) {
    fun matches(identity: PreparedModelIdentity, fingerprint: String, model: String, api: Int) =
        schemaVersion == 1 && modelId == ModelCatalog.gemma.id && modelSha256 == ModelCatalog.gemma.sha256 &&
            runtimeVersion == LocalAcceleration.RUNTIME_VERSION && contextTokens == TutorPromptBuilder.CONTEXT_TOKENS &&
            buildFingerprint == fingerprint && deviceModel == model && deviceApi == api &&
            modelFileName == identity.fileName && modelBytes == identity.bytes && modelModifiedMillis == identity.modifiedMillis &&
            preparedAtUtcMillis > 0 && preparationMillis >= 0 && verificationMillis >= 0 && initializationMillis >= 0
}
