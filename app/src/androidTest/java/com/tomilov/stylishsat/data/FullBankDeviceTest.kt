package com.tomilov.stylishsat.data

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaPlayer
import android.os.SystemClock
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.StudyDraft
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FullBankDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Test fun multiMegabyteUnicodePayloadsSurviveCursorBoundariesAndObservableReads(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            // Supplementary Unicode characters exercise SQLite code-point offsets across every slice.
            val text = "Essay Абзац 🧭 paragraph\n".repeat(130_000)
            assertTrue(text.toByteArray().size > 2 * 1024 * 1024)
            val record = StoredRecord("large-private-draft", "draft", "IELTS", text)
            db.dao().put(record)
            assertEquals(record, db.dao().records().single())
            assertEquals(record, db.dao().observeRecords().first().single())
        } finally { db.close() }
    }

    @Test fun actualPilotUpdateRetainsEveryVersionAndPrivateDraft(): Unit = runBlocking {
        val oldRaw = instrumentation.context.assets.open("seed-v2.json").bufferedReader().use { it.readText() }
        val old = ContentPackCodec.decode(oldRaw)
        val db = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            val repository = ContentRepository(context, db)
            repository.importPack(oldRaw)
            val previousFullRaw = instrumentation.context.assets.open("full-bank-v3.json").bufferedReader().use { it.readText() }
            val previousFull = repository.importPack(previousFullRaw)
            val exercise = old.exercises.first { it.type == ExerciseType.WRITING }
            val draft = StudyDraft(exercise.exam, exercise.id, exercise.version,
                "Preserved essay with English and русский текст.\nSecond paragraph.", workId = "pilot-test-work", elapsedSeconds = 321)
            db.dao().put(StoredRecord("draft:${draft.key}", "draft", exercise.exam.name, storageJson.encodeToString(draft)))
            val current = repository.initialize()
            assertEquals(4, current.version)
            assertEquals(2, current.schemaVersion)
            assertEquals(810, current.exercises.size)
            assertEquals(48, current.lessons.size)
            assertEquals(listOf(2, 3, 4), db.dao().packs().map { it.version }.sorted())
            assertTrue(repository.allExercises().containsAll(old.exercises))
            assertTrue(repository.allExercises().containsAll(previousFull.exercises))
            assertEquals(draft, storageJson.decodeFromString<StudyDraft>(db.dao().records().single().payload))
            assertTrue(repository.contextFor("ielts_writing", "ОБЗОР evidence").isNotEmpty())
            assertEquals(current, repository.initialize())
            assertEquals(3, db.dao().packs().size)
        } finally { db.close() }
    }

    @Test fun everyBundledAudioPreparesAndCompactAudioFullyDecodes() {
        val pack = context.assets.open("content/seed-v1.json").bufferedReader().use { ContentPackCodec.decode(it.readText()) }
        val paths = pack.exercises.flatMap { listOfNotNull(it.audioAssetPath, it.sampleAudioAssetPath) }.distinct()
        assertEquals(30, paths.size)
        val manifest = instrumentation.context.assets.open("compact-audio-manifest.json").bufferedReader().use { JSONObject(it.readText()).getJSONObject("assets") }
        val expectedDurations = manifest.keys().asSequence().associate { key ->
            val item = manifest.getJSONObject(key)
            item.getString("compactAssetPath") to item.getJSONObject("source").getLong("durationMs")
        }
        paths.forEach { path ->
            val player = MediaPlayer()
            try {
                context.assets.openFd(path).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                player.prepare()
                assertTrue(path, player.duration > 1_000)
                expectedDurations[path]?.let { expected -> assertTrue("$path duration ${player.duration} vs $expected", kotlin.math.abs(player.duration - expected) < 100) }
            } finally { player.release() }
            expectedDurations[path]?.let { expected -> decodeEntireOpus(path, expected) }
        }
    }

    private fun decodeEntireOpus(path: String, durationMs: Long) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.assets.openFd(path).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            assertEquals("audio/opus", format.getString(MediaFormat.KEY_MIME))
            val decoder = MediaCodec.createDecoderByType("audio/opus"); codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var inputEnded = false
            var outputEnded = false
            var outputBytes = 0L
            var sampleRate = 48_000
            var channels = 1
            val info = MediaCodec.BufferInfo()
            val deadline = SystemClock.elapsedRealtime() + 90_000
            while (!outputEnded && SystemClock.elapsedRealtime() < deadline) {
                if (!inputEnded) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEnded = true
                        } else {
                            decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index >= 0) {
                    outputBytes += info.size
                    outputEnded = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(index, false)
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    sampleRate = decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                }
            }
            assertTrue("$path did not reach decoder EOS", outputEnded)
            val decodedMs = outputBytes * 1_000 / (sampleRate * channels * 2)
            assertTrue("$path decoded $decodedMs ms, expected $durationMs", kotlin.math.abs(decodedMs - durationMs) < 100)
            decoder.stop()
        } finally { codec?.release(); extractor.release() }
    }
}
