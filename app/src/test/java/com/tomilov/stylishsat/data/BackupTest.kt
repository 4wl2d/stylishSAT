package com.tomilov.stylishsat.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class BackupTest {
    // Fewer iterations keep the suite fast; the app always uses BackupCrypto.ITERATIONS.
    private val iterations = 100_000

    private fun seal(plain: ByteArray, passphrase: String = "correct horse battery"): ByteArray = ByteArrayOutputStream().also { out ->
        BackupCrypto.encrypt(out, passphrase.toCharArray(), 1_000, SecureRandom(), iterations).use { it.write(plain) }
    }.toByteArray()

    private fun open(sealed: ByteArray, passphrase: String = "correct horse battery"): ByteArray =
        BackupCrypto.decrypt(ByteArrayInputStream(sealed), passphrase.toCharArray()).use { it.readBytes() }

    private fun reason(block: () -> Unit): BackupException.Reason = try { block(); fail("expected failure"); error("unreachable") } catch (e: BackupException) { e.reason }

    private fun archiveWithRecording(name: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        val manifest = BackupManifest(createdAtEpochMillis = 5, appVersion = "test", recordingsDirectory = "/old/recordings",
            records = 0, packs = 0, recordings = 1)
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json")); zip.write(Json.encodeToString(manifest).toByteArray()); zip.closeEntry()
            zip.putNextEntry(ZipEntry(name)); zip.write(bytes); zip.closeEntry()
        }
    }.toByteArray()

    @Test fun roundTripsAcrossFrameBoundariesAndEmptyInput() {
        val random = java.util.Random(7)
        listOf(0, 1, BackupCrypto.FRAME_BYTES - 1, BackupCrypto.FRAME_BYTES, BackupCrypto.FRAME_BYTES * 3 + 17).forEach { size ->
            val plain = ByteArray(size).also(random::nextBytes)
            assertArrayEquals("size $size", plain, open(seal(plain)))
        }
    }

    @Test fun theFileHoldsNoReadableLearnerText() {
        val plain = "My essay about urban gardens. Моё эссе.".repeat(50).toByteArray()
        val sealed = seal(plain)
        assertFalse(String(sealed, Charsets.ISO_8859_1).contains("urban gardens"))
        assertFalse(sealed.toList().windowed(plain.size.coerceAtMost(32)).any { it == plain.take(32) })
    }

    @Test fun wrongPassphraseTamperingTruncationAndForeignFilesAreRefused() {
        val plain = ByteArray(BackupCrypto.FRAME_BYTES * 2 + 5) { (it % 251).toByte() }
        val sealed = seal(plain)
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED, reason { open(sealed, "wrong passphrase!!") })
        val flipped = sealed.copyOf().also { it[it.size - 40] = (it[it.size - 40].toInt() xor 1).toByte() }
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED, reason { open(flipped) })
        // Dropping the final frame must not look like a shorter, valid backup.
        val finalFrame = 1 + 4 + (5 + 16)
        assertEquals(BackupException.Reason.TRUNCATED, reason { open(sealed.copyOf(sealed.size - finalFrame)) })
        assertEquals(BackupException.Reason.TRUNCATED, reason { open(sealed.copyOf(sealed.size - 3)) })
        assertEquals(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED, reason { open(sealed + byteArrayOf(0)) })
        assertEquals(BackupException.Reason.NOT_A_BACKUP, reason { open("PK\u0003\u0004 zip".toByteArray()) })
    }

    @Test fun archiveCarriesRecordsPacksAndRecordingsAndRefusesUnsafeNames() {
        val dir = Files.createTempDirectory("backup").toFile()
        try {
            val recording = File(dir, "speaking-1-a.wav").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val records = listOf(StoredRecord("attempt:1", "attempt", "IELTS", "{\"recordingPath\":\"/old/recordings/speaking-1-a.wav\",\"text\":\"line\\nbreak\"}"))
            val packs = listOf(StoredPack("pack", 3, "{\"big\":true}"))
            val manifest = BackupManifest(createdAtEpochMillis = 5, appVersion = "test", recordingsDirectory = "/old/recordings", records = 1, packs = 1, recordings = 1)
            val sealed = ByteArrayOutputStream().also { out ->
                BackupCrypto.encrypt(out, "passphrase-123".toCharArray(), 5, SecureRandom(), iterations).use { BackupArchive.write(it, manifest, records, packs, listOf(recording)) }
            }.toByteArray()
            val restored = BackupCrypto.decrypt(ByteArrayInputStream(sealed), "passphrase-123".toCharArray()).use { BackupArchive.read(it, File(dir, "scratch")) }
            assertEquals(manifest, restored.manifest); assertEquals(records, restored.records); assertEquals(packs, restored.packs)
            assertArrayEquals(byteArrayOf(1, 2, 3), restored.recordings.getValue("speaking-1-a.wav").readBytes())

            val evil = ByteArrayOutputStream().also { out ->
                java.util.zip.ZipOutputStream(out).use { zip -> zip.putNextEntry(java.util.zip.ZipEntry("recordings/../../escape.wav")); zip.write(1); zip.closeEntry() }
            }.toByteArray()
            assertEquals(BackupException.Reason.UNSAFE_ENTRY, reason { BackupArchive.read(ByteArrayInputStream(evil), File(dir, "scratch2")) })
            assertFalse(File(dir.parentFile, "escape.wav").exists())
        } finally { dir.deleteRecursively() }
    }

    @Test fun archiveRefusesTraversalAndDotEntriesWithoutChangingOutsideFiles() {
        val dir = Files.createTempDirectory("backup-unsafe").toFile()
        try {
            val extraction = File(dir, "extract").apply { mkdirs() }
            val original = byteArrayOf(7, 8, 9)
            val outside = File(dir, "outside.wav").apply { writeBytes(original) }
            val parent = File(extraction, "outside.wav").apply { writeBytes(original) }
            val names = listOf("../outside.wav", "../../outside.wav", outside.absolutePath, "..\\outside.wav",
                "%2e%2e%2foutside.wav", "nested/outside.wav", "a".repeat(129), ".", "..")
            names.forEachIndexed { index, name ->
                val archive = archiveWithRecording("recordings/$name", byteArrayOf(1, 2, 3))
                assertEquals(name, BackupException.Reason.UNSAFE_ENTRY, reason {
                    BackupArchive.read(ByteArrayInputStream(archive), File(extraction, "scratch-$index"))
                })
                assertArrayEquals(original, outside.readBytes())
                assertArrayEquals(original, parent.readBytes())
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun archiveRefusesRecordingSymlinkIntoSiblingDirectory() {
        val dir = Files.createTempDirectory("backup-symlink").toFile()
        try {
            for (alreadyExists in listOf(true, false)) {
                val scratch = File(dir, "scratch-$alreadyExists").apply { mkdirs() }
                val sibling = File(dir, "scratch-$alreadyExists-other").apply { mkdirs() }
                val original = byteArrayOf(7, 8, 9)
                val outside = File(sibling, "speaking-1.wav")
                if (alreadyExists) outside.writeBytes(original)
                Files.createSymbolicLink(File(scratch, "speaking-1.wav").toPath(), outside.toPath())
                val archive = archiveWithRecording("recordings/speaking-1.wav", byteArrayOf(1, 2, 3))
                val failure = runCatching { BackupArchive.read(ByteArrayInputStream(archive), scratch) }.exceptionOrNull()
                if (alreadyExists) assertArrayEquals("An unsafe entry must not overwrite the outside file", original, outside.readBytes())
                else assertFalse("An unsafe entry must not create an outside file", outside.exists())
                assertEquals(BackupException.Reason.UNSAFE_ENTRY, (failure as? BackupException)?.reason)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun archiveRefusesRecordingAliasToScratchDirectory() {
        val dir = Files.createTempDirectory("backup-self-alias").toFile()
        try {
            val scratch = File(dir, "scratch").apply { mkdirs() }
            Files.createSymbolicLink(File(scratch, "self.wav").toPath(), scratch.toPath())
            val archive = archiveWithRecording("recordings/self.wav", byteArrayOf(1, 2, 3))
            assertEquals(BackupException.Reason.UNSAFE_ENTRY, reason { BackupArchive.read(ByteArrayInputStream(archive), scratch) })
        } finally { dir.deleteRecursively() }
    }

    @Test fun archiveRestoresValidNamesThroughAliasedScratchDirectory() {
        val dir = Files.createTempDirectory("backup-root-alias").toFile()
        try {
            val actual = File(dir, "actual").apply { mkdirs() }
            val scratch = File(dir, "scratch")
            Files.createSymbolicLink(scratch.toPath(), actual.toPath())
            val name = "speaking..-1_a.wav"
            val bytes = byteArrayOf(1, 2, 3)
            val restored = BackupArchive.read(ByteArrayInputStream(archiveWithRecording("recordings/$name", bytes)), scratch)
            assertEquals(setOf(name), restored.recordings.keys)
            assertArrayEquals(bytes, restored.recordings.getValue(name).readBytes())
            assertEquals(File(actual, name).canonicalFile, restored.recordings.getValue(name).canonicalFile)
        } finally { dir.deleteRecursively() }
    }

    @Test fun archivePreservesEntryNameForAliasInsideScratch() {
        val dir = Files.createTempDirectory("backup-child-alias").toFile()
        try {
            val scratch = File(dir, "scratch").apply { mkdirs() }
            val actual = File(scratch, "actual.wav").apply { writeBytes(byteArrayOf(7, 8, 9)) }
            Files.createSymbolicLink(File(scratch, "alias.wav").toPath(), actual.toPath())
            val bytes = byteArrayOf(1, 2, 3)
            val restored = BackupArchive.read(ByteArrayInputStream(archiveWithRecording("recordings/alias.wav", bytes)), scratch)
            assertEquals(setOf("alias.wav"), restored.recordings.keys)
            assertArrayEquals(bytes, restored.recordings.getValue("alias.wav").readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun restoreAddsMissingWorkKeepsLocalWorkAndFillsAFreshExam() {
        val local = listOf(
            StoredRecord("attempt:a", "attempt", "SAT", "{\"id\":\"a\"}"),
            StoredRecord("draft:SAT:x", "draft", "SAT", "{\"text\":\"newer local\"}"),
            StoredRecord("profile:SAT", "profile", "SAT", "{\"local\":1}"),
            StoredRecord("profile:IELTS", "profile", "IELTS", "{\"default\":1}"),
        )
        val backup = listOf(
            StoredRecord("attempt:a", "attempt", "SAT", "{\"id\":\"a\"}"),
            StoredRecord("attempt:b", "attempt", "IELTS", "{\"recordingPath\":\"/old/rec/speaking-1.wav\"}"),
            StoredRecord("draft:SAT:x", "draft", "SAT", "{\"text\":\"older backup\"}"),
            StoredRecord("profile:SAT", "profile", "SAT", "{\"backup\":1}"),
            StoredRecord("profile:IELTS", "profile", "IELTS", "{\"examDate\":1}"),
        )
        val result = BackupMerge.plan(local, backup, setOf("pack" to 7), listOf(StoredPack("pack", 7, "x"), StoredPack("pack", 3, "y")), "/old/rec", "/new/rec")
        assertEquals(1, result.added); assertEquals(1, result.replacedFresh); assertEquals(2, result.keptLocal); assertEquals(1, result.unchanged)
        val put = result.put.associateBy { it.key }
        assertEquals("{\"recordingPath\":\"/new/rec/speaking-1.wav\"}", put.getValue("attempt:b").payload)
        // SAT already has answers here, so its profile and the newer draft stay; IELTS had none, so its profile comes back.
        assertNull(put["profile:SAT"]); assertNull(put["draft:SAT:x"])
        assertEquals("{\"examDate\":1}", put.getValue("profile:IELTS").payload)
        assertEquals(listOf(3), result.packs.map { it.version })
        assertEquals(setOf("IELTS"), result.replacedExams)
    }
}
