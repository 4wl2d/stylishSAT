package com.tomilov.stylishsat.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.tomilov.stylishsat.speech.RecordingPlayback
import com.tomilov.stylishsat.speech.SpeakingRecorder
import com.tomilov.stylishsat.speech.WhisperSpeechTranscriber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** One lock across all facade instances; native ASR and LLM allocations never overlap. */
object RuntimeResourceGate {
    private val mutex = Mutex()
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }
}

data class RuntimeCapability(
    val supported: Boolean,
    val totalMemoryBytes: Long,
    val availableMemoryBytes: Long,
    val reason: String,
) {
    companion object {
        private const val GIB = 1024L * 1024 * 1024
        // Android reports usable memory after firmware reservations. 7 GiB is the floor
        // for the marketed 8 GB device class; runtime availability is checked separately.
        private const val EIGHT_GB_CLASS_FLOOR = 7L * GIB
        fun inspect(context: Context): RuntimeCapability {
            val manager = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            val supported = info.totalMem >= EIGHT_GB_CLASS_FLOOR && Build.SUPPORTED_ABIS.contains("arm64-v8a")
            return RuntimeCapability(supported, info.totalMem, info.availMem, if (supported)
                "Eligible 8 GB+ ARM64 device. Local runtime performance must be checked on this device."
                else "Local AI requires an 8 GB+ ARM64 Android device. Lessons, recording and self-check remain available.")
        }

        fun requireAvailableMemory(context: Context) {
            val manager = context.getSystemService(ActivityManager::class.java)
            val info = ActivityManager.MemoryInfo().also(manager::getMemoryInfo)
            check(!info.lowMemory && info.availMem >= 3L * GIB) {
                "Not enough free RAM for local AI. Close other apps, or use the prepared explanation / ChatGPT."
            }
        }
    }
}

class RuntimeServices private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val downloads = ModelDownloadManager(appContext, scope)
    val capability get() = RuntimeCapability.inspect(appContext)
    val acceleration = LocalAcceleration(appContext, downloads)
    val tutorEngine: TutorEngine = LiteRtTutorEngine(appContext, downloads, acceleration = acceleration)
    val recorder = SpeakingRecorder(appContext, scope)
    val speechTranscriber = WhisperSpeechTranscriber(appContext, downloads)
    val playback = RecordingPlayback()

    companion object {
        @Volatile private var instance: RuntimeServices? = null

        /** One owner for app-private model files, downloads and recording state per process. */
        fun get(context: Context): RuntimeServices = instance ?: synchronized(this) {
            instance ?: RuntimeServices(context.applicationContext).also { instance = it }
        }
    }
}
