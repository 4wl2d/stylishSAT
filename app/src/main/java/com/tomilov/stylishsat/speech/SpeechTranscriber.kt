package com.tomilov.stylishsat.speech

import android.content.Context
import com.tomilov.stylishsat.ai.ModelCatalog
import com.tomilov.stylishsat.ai.ModelDownloadManager
import com.tomilov.stylishsat.ai.RuntimeCapability
import com.tomilov.stylishsat.ai.RuntimeResourceGate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class Transcript(val text: String, val engine: String = "whisper.cpp base.en", val needsUserReview: Boolean = true)
interface SpeechTranscriber { suspend fun transcribe(recording: File): Transcript }

class WhisperSpeechTranscriber(
    private val context: Context,
    private val downloads: ModelDownloadManager,
) : SpeechTranscriber {
    override suspend fun transcribe(recording: File): Transcript = RuntimeResourceGate.exclusive {
        check(RuntimeCapability.inspect(context).supported) {
            "Offline transcription requires an 8 GB+ ARM64 device. Your recording is saved; type or edit its transcript."
        }
        val model = downloads.modelFile(ModelCatalog.whisper)
            ?: error("Download Whisper base.en first. Your recording is saved; you can type the transcript now.")
        RuntimeCapability.requireAvailableMemory(context)
        coroutineScope {
            val cancelled = AtomicBoolean(false)
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally { cancelled.set(true) }
            }
            try {
                withContext(Dispatchers.IO) {
                    val samples = WaveAudio.readSamples(recording)
                    val text = try {
                        WhisperNative.transcribe(model.absolutePath, samples, 4, cancelled)
                    } catch (error: UnsatisfiedLinkError) {
                        error("The offline speech library is unavailable on this device. Recording is preserved; type a transcript.")
                    }
                    Transcript(text.trim())
                }
            } finally { watcher.cancel() }
        }
    }
}

internal object WhisperNative {
    init { System.loadLibrary("stylish_whisper") }
    external fun transcribe(modelPath: String, samples: FloatArray, threads: Int, cancelled: AtomicBoolean): String
}
