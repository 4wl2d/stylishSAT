package com.tomilov.stylishsat.ai

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val modelId: String, val downloadedBytes: Long, val totalBytes: Long) : DownloadState
    data class Verifying(val modelId: String) : DownloadState
    data class Installed(val modelId: String) : DownloadState
    data class Paused(val modelId: String) : DownloadState
    data class Failed(val modelId: String, val message: String) : DownloadState
}

/** Files stay private and excluded from backup. A .part file is never usable as a model. */
class ModelDownloadManager(context: Context, private val scope: CoroutineScope) {
    private val directory = File(context.noBackupFilesDir, "models").apply { mkdirs() }
    private val mutableState = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val state: StateFlow<DownloadState> = mutableState.asStateFlow()
    private val installMutex = Mutex()
    private var job: Job? = null
    @Volatile private var activeConnection: HttpURLConnection? = null

    fun modelFile(spec: ModelSpec): File? = File(directory, spec.fileName).takeIf {
        it.isFile && it.length() == spec.sizeBytes &&
            runCatching { receipt(spec).readText() == spec.sha256 }.getOrDefault(false)
    }

    fun isInstalled(spec: ModelSpec): Boolean = modelFile(spec) != null

    @Synchronized fun start(spec: ModelSpec) {
        val previous = job
        val settled = mutableState.value is DownloadState.Installed || mutableState.value is DownloadState.Failed
        if (previous?.isActive == true && !settled) return
        job = scope.launch(Dispatchers.IO) {
            try {
                // StateFlow can deliver Installed/Paused before the previous coroutine's
                // final stack unwinds. Wait for its cleanup before starting another transfer.
                previous?.join()
                installMutex.withLock { downloadAndInstall(spec) }
                mutableState.value = DownloadState.Installed(spec.id)
            } catch (cancelled: CancellationException) {
                mutableState.value = DownloadState.Paused(spec.id)
                throw cancelled
            } catch (error: Exception) {
                mutableState.value = if (!currentCoroutineContext().isActiveCompat()) DownloadState.Paused(spec.id)
                else DownloadState.Failed(spec.id, error.message ?: "Download failed. Resume to retry.")
            }
        }
    }

    fun cancel() {
        job?.cancel()
        activeConnection?.disconnect()
    }

    /** For explicitly selected local files and reproducible device QA; always verifies full bytes. */
    suspend fun installLocal(spec: ModelSpec, source: File) = withContext(Dispatchers.IO) {
        installMutex.withLock {
            val part = File(directory, "${spec.fileName}.part")
            require(source.canonicalFile != part.canonicalFile) { "Choose a source outside the installer staging file" }
            if (directory.usableSpace < spec.sizeBytes + SPACE_RESERVE) throw IOException("Not enough free storage")
            source.inputStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var count = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        count += n
                        if (count > spec.sizeBytes) throw IOException("Model exceeds its expected size")
                        output.write(buffer, 0, n)
                    }
                    output.fd.sync()
                }
            }
            verifyAndPublish(spec, part)
            mutableState.value = DownloadState.Installed(spec.id)
        }
    }

    private suspend fun downloadAndInstall(spec: ModelSpec) {
        if (isInstalled(spec)) return
        val installed = File(directory, spec.fileName)
        if (installed.isFile && installed.length() == spec.sizeBytes && !receipt(spec).exists()) {
            // Recover an interrupted atomic install without downloading another multi-GB copy.
            verifyAndPublish(spec, installed)
            return
        }
        val part = File(directory, "${spec.fileName}.part")
        var offset = part.length()
        if (offset > spec.sizeBytes) {
            RandomAccessFile(part, "rw").use { it.setLength(0) }
            offset = 0
        }
        if (offset == spec.sizeBytes) {
            verifyAndPublish(spec, part)
            return
        }
        if (directory.usableSpace < spec.sizeBytes - offset + SPACE_RESERVE) {
            throw IOException("Not enough free storage for ${spec.name}; free space and resume.")
        }
        mutableState.value = DownloadState.Downloading(spec.id, offset, spec.sizeBytes)
        val connection = openConnection(spec.url, offset)
        activeConnection = connection
        try {
            val code = connection.responseCode
            if (code == 200) offset = 0 // Server ignored Range: safely replace, never append a full response.
            else if (code == 206) validateRange(connection.getHeaderField("Content-Range"), offset, spec.sizeBytes)
            else throw IOException("Model server returned HTTP $code. Resume to retry.")
            val responseBytes = connection.contentLengthLong
            if (responseBytes >= 0 && responseBytes != spec.sizeBytes - offset) {
                throw IOException("Unexpected model response size")
            }
            RandomAccessFile(part, "rw").use { output ->
                if (offset == 0L) output.setLength(0)
                output.seek(offset)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var lastUpdate = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (offset + read > spec.sizeBytes) throw IOException("Model download exceeded expected size")
                        output.write(buffer, 0, read)
                        offset += read
                        val now = System.nanoTime()
                        if (now - lastUpdate > 200_000_000L) {
                            mutableState.value = DownloadState.Downloading(spec.id, offset, spec.sizeBytes)
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
            activeConnection = null
        }
        verifyAndPublish(spec, part)
    }

    private suspend fun verifyAndPublish(spec: ModelSpec, part: File) {
        mutableState.value = DownloadState.Verifying(spec.id)
        if (part.length() != spec.sizeBytes) throw IOException("Download interrupted. Resume to finish.")
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (actual != spec.sha256) {
            // Only our replaceable download staging file is discarded, never a prior installed model.
            if (part.name == "${spec.fileName}.part") part.delete()
            throw IOException("SHA-256 mismatch. The download was rejected; download again.")
        }
        currentCoroutineContext().ensureActive()
        val target = File(directory, spec.fileName)
        if (part.canonicalFile != target.canonicalFile) {
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
        val receiptPart = File(directory, "${spec.fileName}.sha256.part")
        receiptPart.outputStream().use { it.write(spec.sha256.toByteArray()); it.fd.sync() }
        Files.move(receiptPart.toPath(), receipt(spec).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun receipt(spec: ModelSpec) = File(directory, "${spec.fileName}.sha256")

    private fun openConnection(source: String, offset: Long): HttpURLConnection {
        var url = URL(source)
        repeat(6) {
            require(url.protocol == "https") { "Only HTTPS model downloads are allowed" }
            val connection = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = false
                setRequestProperty("Accept-Encoding", "identity")
                if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
            }
            activeConnection = connection
            val code = connection.responseCode
            if (code !in listOf(301, 302, 303, 307, 308)) return connection
            val next = connection.getHeaderField("Location") ?: throw IOException("Missing redirect location")
            connection.disconnect()
            url = URL(url, next)
        }
        throw IOException("Too many model server redirects")
    }

    companion object {
        private const val BUFFER_SIZE = 256 * 1024
        private const val SPACE_RESERVE = 256L * 1024 * 1024
        internal fun validateRange(header: String?, offset: Long, expectedSize: Long) {
            val match = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(header.orEmpty())
                ?: throw IOException("Invalid resume response")
            val (start, end, total) = match.destructured
            if (start.toLong() != offset || total.toLong() != expectedSize || end.toLong() != expectedSize - 1) {
                throw IOException("Resume range does not match the expected model")
            }
        }
    }
}

private fun kotlin.coroutines.CoroutineContext.isActiveCompat() = this[Job]?.isActive != false
