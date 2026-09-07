package com.tomilov.stylishsat.speech

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WaveAudioTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun interruptedRecordingIsRecoveredWithoutChangingSamples() {
        val file = temporary.newFile("answer.wav")
        file.writeBytes(WaveAudio.header(0) + byteArrayOf(0, 0, -1, 127, 0, -128))
        WaveAudio.repairHeader(file)
        val samples = WaveAudio.readSamples(file)
        assertEquals(3, samples.size)
        assertEquals(0f, samples[0], 0f)
        assertEquals(32767f / 32768f, samples[1], 0f)
        assertEquals(-1f, samples[2], 0f)
    }
    @Test(expected = IllegalArgumentException::class) fun nonPcmContentIsRejected() {
        val file = temporary.newFile("wrong.wav")
        file.writeBytes(ByteArray(100))
        WaveAudio.readSamples(file)
    }

    @Test fun startupRecoveryRepairsSavedResponsesBeforeReplayButSkipsActiveFiles() {
        val directory = temporary.newFolder("recordings")
        val saved = java.io.File(directory, "speaking-123-saved.wav")
        val active = java.io.File(directory, "speaking-456-active.wav")
        val other = java.io.File(directory, "user-provided.wav")
        val interruptedBytes = WaveAudio.header(0) + byteArrayOf(0, 0, -1, 127)
        listOf(saved, active, other).forEach { it.writeBytes(interruptedBytes) }
        WaveAudio.recoverRecordings(directory, setOf(active))
        assertEquals(2, WaveAudio.readSamples(saved).size)
        assertArrayEquals(interruptedBytes, active.readBytes())
        assertArrayEquals(interruptedBytes, other.readBytes())
        assertArrayEquals(interruptedBytes.copyOfRange(44, 48), saved.readBytes().copyOfRange(44, 48))
    }
}
