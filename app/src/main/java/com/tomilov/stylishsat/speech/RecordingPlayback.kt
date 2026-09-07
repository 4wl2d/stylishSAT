package com.tomilov.stylishsat.speech

import android.media.AudioAttributes
import android.media.MediaPlayer
import java.io.File

class RecordingPlayback {
    private var player: MediaPlayer? = null

    fun play(file: File, onComplete: () -> Unit = {}, onError: (String) -> Unit = {}) {
        stop()
        val next = MediaPlayer()
        player = next
        try {
            next.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            next.setDataSource(file.absolutePath)
            next.setOnPreparedListener {
                if (player === it) runCatching { it.start() }.onFailure { error ->
                    stop(); onError(error.message ?: "Audio playback failed")
                }
            }
            next.setOnCompletionListener { if (player === it) { stop(); onComplete() } }
            next.setOnErrorListener { failed, _, _ ->
                if (player === failed) { stop(); onError("Audio playback failed") }
                true
            }
            next.prepareAsync()
        } catch (error: Exception) {
            stop(); onError(error.message ?: "Audio playback failed")
        }
    }

    fun stop() { player?.release(); player = null }
}
