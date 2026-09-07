package com.tomilov.stylishsat.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID

data class Recording(val file: File, val durationMillis: Long, val createdAtMillis: Long)
sealed interface RecorderState {
    data object Idle : RecorderState
    data class RecordingAudio(val file: File, val elapsedMillis: Long) : RecorderState
    data class Saved(val recording: Recording) : RecorderState
    data class Failed(val message: String, val preservedFile: File?) : RecorderState
}

class SpeakingRecorder(private val context: Context, private val scope: CoroutineScope) {
    private val directory = File(context.noBackupFilesDir, "recordings").apply { mkdirs() }
    private val mutableState = MutableStateFlow<RecorderState>(RecorderState.Idle)
    val state = mutableState.asStateFlow()
    @Volatile private var capturing = false
    private var recorder: AudioRecord? = null
    private var job: Job? = null
    private var currentFile: File? = null

    init {
        synchronized(recordingFileLock) { WaveAudio.recoverRecordings(directory, activeRecordingFiles) }
    }

    @Synchronized fun start(): File {
        check(!capturing && job?.isCompleted != false) { "A recording is already running" }
        check(context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            "Microphone permission is required. You can still type or edit a transcript."
        }
        val minimum = AudioRecord.getMinBufferSize(WaveAudio.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "The microphone does not support the required audio format" }
        val audio = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(WaveAudio.SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(maxOf(minimum * 2, 8192)).build()
        if (audio.state != AudioRecord.STATE_INITIALIZED) {
            audio.release(); error("The microphone could not be initialized. Type a transcript instead.")
        }
        val file = File(directory, "speaking-${System.currentTimeMillis()}-${UUID.randomUUID()}.wav")
        synchronized(recordingFileLock) { activeRecordingFiles.add(file) }
        try {
            file.writeBytes(WaveAudio.header(0))
            audio.startRecording()
            check(audio.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Microphone recording did not start" }
        } catch (error: Exception) {
            synchronized(recordingFileLock) { activeRecordingFiles.remove(file) }
            audio.release(); throw error
        }
        recorder = audio
        currentFile = file
        capturing = true
        mutableState.value = RecorderState.RecordingAudio(file, 0)
        job = scope.launch(Dispatchers.IO) {
            var failure: String? = null
            try {
                RandomAccessFile(file, "rw").use { output ->
                    output.seek(WaveAudio.HEADER_BYTES.toLong())
                    val bytes = ByteArray(8192)
                    var totalBytes = 0
                    val limit = WaveAudio.SAMPLE_RATE * WaveAudio.MAX_SECONDS * 2
                    while (capturing && totalBytes < limit) {
                        val count = audio.read(bytes, 0, minOf(bytes.size, limit - totalBytes), AudioRecord.READ_BLOCKING)
                        if (count < 0) { if (capturing) error("Microphone read failed ($count)"); break }
                        if (count == 0) continue
                        output.write(bytes, 0, count)
                        totalBytes += count
                        mutableState.value = RecorderState.RecordingAudio(file, totalBytes * 1000L / (WaveAudio.SAMPLE_RATE * 2))
                    }
                    output.fd.sync()
                }
            } catch (error: Exception) {
                failure = error.message ?: "Recording stopped unexpectedly"
            } finally {
                capturing = false
                runCatching { audio.stop() }
                audio.release()
                synchronized(this@SpeakingRecorder) { if (recorder === audio) recorder = null }
                synchronized(recordingFileLock) {
                    runCatching { WaveAudio.repairHeader(file) }.onFailure {
                        failure = "Recorded samples are preserved, but the audio header could not be finalized: ${it.message}"
                    }
                    activeRecordingFiles.remove(file)
                }
                mutableState.value = failure?.let { RecorderState.Failed(it, file) } ?: RecorderState.Saved(metadata(file))
            }
        }
        return file
    }

    suspend fun stop(): Recording? {
        capturing = false
        synchronized(this) { runCatching { recorder?.stop() } }
        job?.join()
        return currentFile?.takeIf { it.length() > WaveAudio.HEADER_BYTES }?.let(::metadata)
    }

    fun recordings(): List<Recording> = directory.listFiles().orEmpty().filter {
        it.extension == "wav" && it.length() > WaveAudio.HEADER_BYTES
    }.onEach { synchronized(recordingFileLock) { if (it !in activeRecordingFiles) WaveAudio.repairHeader(it) } }
        .map(::metadata).sortedByDescending { it.createdAtMillis }

    private fun metadata(file: File) = Recording(file,
        (file.length() - WaveAudio.HEADER_BYTES).coerceAtLeast(0) * 1000L / (WaveAudio.SAMPLE_RATE * 2),
        file.name.split('-').getOrNull(1)?.toLongOrNull() ?: file.lastModified())

    companion object {
        private val recordingFileLock = Any()
        private val activeRecordingFiles = mutableSetOf<File>()
    }
}
