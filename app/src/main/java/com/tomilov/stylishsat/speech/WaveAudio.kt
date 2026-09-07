package com.tomilov.stylishsat.speech

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The recorder's fixed PCM format: 16 kHz, one channel, signed little-endian 16 bit. */
object WaveAudio {
    const val SAMPLE_RATE = 16000
    const val HEADER_BYTES = 44
    const val MAX_SECONDS = 600

    fun header(dataBytes: Int): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(dataBytes + 36); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(dataBytes)
    }.array()

    /** Repairs the length after process death, preserving every completely recorded sample. */
    fun repairHeader(file: File) {
        if (file.length() < HEADER_BYTES) return
        RandomAccessFile(file, "rw").use { output ->
            val byteCount = ((output.length() - HEADER_BYTES) / 2 * 2).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val previous = ByteArray(HEADER_BYTES).also(output::readFully)
            val repaired = header(byteCount)
            if (!previous.contentEquals(repaired)) { output.seek(0); output.write(repaired); output.fd.sync() }
        }
    }

    /** Called before a recorder is exposed after process restart. Only app-created recordings
     * are candidates; another live recorder's file is excluded by the caller's process-wide lock. */
    fun recoverRecordings(directory: File, activeFiles: Set<File> = emptySet()) {
        directory.listFiles().orEmpty().filter {
            it.name.startsWith("speaking-") && it.extension == "wav" && it !in activeFiles
        }.forEach { runCatching { repairHeader(it) } }
    }

    fun readSamples(file: File): FloatArray {
        return RandomAccessFile(file, "r").use { input ->
            require(file.length() >= HEADER_BYTES + 2L) { "Recording is empty or incomplete" }
            val header = ByteArray(12).also(input::readFully)
            require(String(header, 0, 4) == "RIFF" && String(header, 8, 4) == "WAVE") { "Expected a WAV recording" }
            var validFormat = false
            var dataOffset = -1L
            var dataBytes = 0
            while (input.filePointer + 8 <= input.length()) {
                val chunk = ByteArray(8).also(input::readFully)
                val id = String(chunk, 0, 4)
                val size = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN).getInt(4).toLong() and 0xffffffffL
                val start = input.filePointer
                require(size <= input.length() - start) { "Incomplete WAV chunk" }
                if (id == "fmt ") {
                    require(size >= 16) { "Invalid WAV format" }
                    val format = ByteBuffer.wrap(ByteArray(16).also(input::readFully)).order(ByteOrder.LITTLE_ENDIAN)
                    validFormat = format.getShort(0).toInt() == 1 && format.getShort(2).toInt() == 1 &&
                        format.getInt(4) == SAMPLE_RATE && format.getShort(14).toInt() == 16
                } else if (id == "data") {
                    require(size in 2..(SAMPLE_RATE.toLong() * MAX_SECONDS * 2) && size % 2 == 0L) {
                        "Record between one sample and ten minutes of audio"
                    }
                    dataOffset = start; dataBytes = size.toInt()
                }
                input.seek(start + size + size % 2)
            }
            require(validFormat && dataOffset >= 0) { "Expected a 16 kHz mono PCM WAV recording" }
            input.seek(dataOffset)
            val bytes = ByteArray(dataBytes).also(input::readFully)
            val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
            FloatArray(pcm.remaining()) { pcm.get() / 32768.0f }
        }
    }
}
