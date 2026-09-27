package com.tomilov.stylishsat.data

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class BackupException(val reason: Reason, cause: Throwable? = null) : Exception(reason.name, cause) {
    enum class Reason { NOT_A_BACKUP, UNSUPPORTED_FORMAT, WRONG_PASSPHRASE_OR_DAMAGED, TRUNCATED, UNSAFE_ENTRY }
}

/**
 * Passphrase-encrypted stream for local backups. No account, server or key escrow: without the passphrase the file cannot be opened.
 *
 * Layout: [MAGIC][u32 header length][header JSON][frames]. A frame is [u8 final][u32 ciphertext length][AES-256-GCM ciphertext + tag].
 * The key comes from PBKDF2-HMAC-SHA256. Each frame's nonce is a random 8-byte prefix plus a 4-byte counter, and its associated data is
 * the header plus the counter and final flag, so reordered, dropped, edited or truncated frames fail authentication.
 */
object BackupCrypto {
    private val MAGIC = "STYLISHSAT-BACKUP\n".toByteArray(Charsets.US_ASCII)
    const val FORMAT = 1
    /** OWASP's current PBKDF2-HMAC-SHA256 recommendation. */
    const val ITERATIONS = 600_000
    const val FRAME_BYTES = 64 * 1024
    private const val TAG_BITS = 128
    private const val MAX_HEADER_BYTES = 4 * 1024

    @Serializable
    data class Header(
        val format: Int = FORMAT,
        val kdf: String = "PBKDF2WithHmacSHA256",
        val iterations: Int = ITERATIONS,
        val salt: String,
        val cipher: String = "AES-256-GCM",
        val noncePrefix: String,
        val frameBytes: Int = FRAME_BYTES,
        val createdAtEpochMillis: Long,
    )

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }

    private fun key(passphrase: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }

    private fun nonce(prefix: ByteArray, counter: Int) = ByteBuffer.allocate(12).put(prefix).putInt(counter).array()
    private fun aad(header: ByteArray, counter: Int, final: Boolean) = ByteBuffer.allocate(header.size + 5).put(header).putInt(counter).put(if (final) 1 else 0).array()

    /** Wraps [out]; closing the returned stream writes the final authenticated frame. [out] is not closed. */
    fun encrypt(out: OutputStream, passphrase: CharArray, createdAt: Long, random: SecureRandom = SecureRandom(), iterations: Int = ITERATIONS): OutputStream {
        val salt = ByteArray(16).also(random::nextBytes)
        val prefix = ByteArray(8).also(random::nextBytes)
        val header = Header(iterations = iterations, salt = Base64.getEncoder().encodeToString(salt), noncePrefix = Base64.getEncoder().encodeToString(prefix),
            createdAtEpochMillis = createdAt)
        val headerBytes = json.encodeToString(header).toByteArray(Charsets.UTF_8)
        val secret = key(passphrase, salt, iterations)
        out.write(MAGIC); out.write(ByteBuffer.allocate(4).putInt(headerBytes.size).array()); out.write(headerBytes)
        return object : FilterOutputStream(out) {
            private val buffer = ByteArray(FRAME_BYTES)
            private var filled = 0
            private var counter = 0
            private var closed = false

            private fun frame(final: Boolean) {
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.ENCRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce(prefix, counter)))
                cipher.updateAAD(aad(headerBytes, counter, final))
                val sealed = cipher.doFinal(buffer, 0, filled)
                out.write(if (final) 1 else 0)
                out.write(ByteBuffer.allocate(4).putInt(sealed.size).array())
                out.write(sealed)
                counter++; filled = 0
                check(counter != Int.MAX_VALUE) { "Backup too large" }
            }

            override fun write(b: Int) { write(byteArrayOf(b.toByte()), 0, 1) }
            override fun write(b: ByteArray, off: Int, len: Int) {
                check(!closed)
                var offset = off; var left = len
                while (left > 0) {
                    if (filled == FRAME_BYTES) frame(false)
                    val take = minOf(left, FRAME_BYTES - filled)
                    System.arraycopy(b, offset, buffer, filled, take)
                    filled += take; offset += take; left -= take
                }
            }
            override fun flush() { out.flush() }
            override fun close() {
                if (closed) return
                closed = true
                frame(true)
                out.flush()
            }
        }
    }

    /** Reads and authenticates frames lazily; the stream fails before returning data from a damaged frame, and at the end if frames are missing. */
    fun decrypt(input: InputStream, passphrase: CharArray): InputStream {
        val data = DataInputStream(input)
        val magic = ByteArray(MAGIC.size)
        try { data.readFully(magic) } catch (_: EOFException) { throw BackupException(BackupException.Reason.NOT_A_BACKUP) }
        if (!magic.contentEquals(MAGIC)) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
        val headerBytes = try {
            val length = data.readInt()
            if (length !in 1..MAX_HEADER_BYTES) throw BackupException(BackupException.Reason.NOT_A_BACKUP)
            ByteArray(length).also(data::readFully)
        } catch (_: EOFException) { throw BackupException(BackupException.Reason.TRUNCATED) }
        val header = try { json.decodeFromString<Header>(headerBytes.toString(Charsets.UTF_8)) }
            catch (error: IllegalArgumentException) { throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT, error) }
        if (header.format != FORMAT || header.kdf != "PBKDF2WithHmacSHA256" || header.cipher != "AES-256-GCM" ||
            header.iterations !in 100_000..10_000_000 || header.frameBytes != FRAME_BYTES) throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT)
        val salt = Base64.getDecoder().decode(header.salt)
        val prefix = Base64.getDecoder().decode(header.noncePrefix)
        if (salt.size != 16 || prefix.size != 8) throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT)
        val secret = key(passphrase, salt, header.iterations)
        return object : InputStream() {
            private var plain = ByteArray(0)
            private var position = 0
            private var counter = 0
            private var finished = false

            private fun next(): Boolean {
                if (finished) return false
                val final = try {
                    when (data.readUnsignedByte()) { 0 -> false; 1 -> true; else -> throw BackupException(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED) }
                } catch (_: EOFException) { throw BackupException(BackupException.Reason.TRUNCATED) }
                val sealed = try {
                    val length = data.readInt()
                    if (length !in TAG_BITS / 8..FRAME_BYTES + TAG_BITS / 8) throw BackupException(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED)
                    ByteArray(length).also(data::readFully)
                } catch (_: EOFException) { throw BackupException(BackupException.Reason.TRUNCATED) }
                plain = try {
                    Cipher.getInstance("AES/GCM/NoPadding").run {
                        init(Cipher.DECRYPT_MODE, secret, GCMParameterSpec(TAG_BITS, nonce(prefix, counter)))
                        updateAAD(aad(headerBytes, counter, final))
                        doFinal(sealed)
                    }
                } catch (error: GeneralSecurityException) { throw BackupException(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED, error) }
                position = 0; counter++
                if (final) {
                    finished = true
                    if (data.read() != -1) throw BackupException(BackupException.Reason.WRONG_PASSPHRASE_OR_DAMAGED)
                }
                return true
            }

            override fun read(): Int {
                val one = ByteArray(1)
                return if (read(one, 0, 1) == -1) -1 else one[0].toInt() and 0xff
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                while (position == plain.size) if (!next()) return -1
                val take = minOf(len, plain.size - position)
                System.arraycopy(plain, position, b, off, take)
                position += take
                return take
            }
            /** Draining to the end authenticates the final frame even when the reader stopped early. */
            fun drain() { while (next()) position = plain.size }
            override fun close() { drain(); input.close() }
        }
    }
}

/** What a backup contains: the private tables and the learner's recordings. Models and bundled content are not copied. */
@Serializable
data class BackupManifest(
    val format: Int = 1,
    val createdAtEpochMillis: Long,
    val appVersion: String,
    val recordingsDirectory: String,
    val records: Int,
    val packs: Int,
    val recordings: Int,
)

@Serializable private data class RecordLine(val key: String, val kind: String, val exam: String, val payload: String)
@Serializable private data class PackLine(val id: String, val version: Int, val payload: String)

data class BackupContents(val manifest: BackupManifest, val records: List<StoredRecord>, val packs: List<StoredPack>, val recordings: Map<String, File>)

/** ZIP layout inside the encrypted stream. Recording names are flat file names; anything else is refused on restore. */
object BackupArchive {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = false }
    private val safeName = Regex("[A-Za-z0-9._-]{1,128}")

    fun write(out: OutputStream, manifest: BackupManifest, records: List<StoredRecord>, packs: List<StoredPack>, recordings: List<File>) {
        val zip = ZipOutputStream(out)
        fun entry(name: String, body: (OutputStream) -> Unit) { zip.putNextEntry(ZipEntry(name)); body(zip); zip.closeEntry() }
        entry("backup.json") { it.write(json.encodeToString(manifest).toByteArray()) }
        entry("records.jsonl") { stream -> records.forEach { stream.write((json.encodeToString(RecordLine(it.key, it.kind, it.exam, it.payload)) + "\n").toByteArray()) } }
        entry("packs.jsonl") { stream -> packs.forEach { stream.write((json.encodeToString(PackLine(it.id, it.version, it.payload)) + "\n").toByteArray()) } }
        recordings.filter { it.isFile && safeName.matches(it.name) }.forEach { file -> entry("recordings/${file.name}") { stream -> file.inputStream().use { it.copyTo(stream) } } }
        zip.finish()
    }

    /** Recordings are streamed into [scratch]; the caller moves them only after the whole stream has authenticated. */
    fun read(input: InputStream, scratch: File): BackupContents {
        scratch.mkdirs()
        var manifest: BackupManifest? = null
        val records = mutableListOf<StoredRecord>()
        val packs = mutableListOf<StoredPack>()
        val recordings = linkedMapOf<String, File>()
        val zip = ZipInputStream(input)
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            when {
                name == "backup.json" -> manifest = json.decodeFromString(zip.readBytes().toString(Charsets.UTF_8))
                name == "records.jsonl" -> zip.bufferedReader().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    json.decodeFromString<RecordLine>(line).let { records += StoredRecord(it.key, it.kind, it.exam, it.payload) } }
                name == "packs.jsonl" -> zip.bufferedReader().lineSequence().filter { it.isNotBlank() }.forEach { line ->
                    json.decodeFromString<PackLine>(line).let { packs += StoredPack(it.id, it.version, it.payload) } }
                name.startsWith("recordings/") && safeName.matches(name.removePrefix("recordings/")) && !entry.isDirectory -> {
                    val target = File(scratch, name.removePrefix("recordings/"))
                    target.outputStream().use { zip.copyTo(it) }
                    recordings[target.name] = target
                }
                else -> throw BackupException(BackupException.Reason.UNSAFE_ENTRY)
            }
        }
        val found = manifest ?: throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT)
        if (found.format != 1 || found.records != records.size || found.packs != packs.size || found.recordings != recordings.size)
            throw BackupException(BackupException.Reason.UNSUPPORTED_FORMAT)
        return BackupContents(found, records, packs, recordings)
    }
}

/**
 * Restore adds what is missing and never overwrites newer local work. The one exception is per-exam state (profile, plan, course
 * progress, open session) on a device that has no answers yet for that exam: there the backup's state replaces the fresh defaults.
 */
object BackupMerge {
    private val examState = setOf("profile", "plan", "course_plan", "course_progress", "session")

    data class Result(val put: List<StoredRecord>, val packs: List<StoredPack>, val added: Int, val replacedFresh: Int, val keptLocal: Int, val unchanged: Int,
                      /** Exams whose per-exam state came from the backup because this device had no answers for them. */
                      val replacedExams: Set<String> = emptySet())

    fun plan(local: List<StoredRecord>, backup: List<StoredRecord>, localPacks: Set<Pair<String, Int>>, backupPacks: List<StoredPack>,
             fromRecordings: String, toRecordings: String): Result {
        val existing = local.associateBy { it.key }
        val answered = local.filter { it.kind == "attempt" }.map { it.exam }.toSet()
        val put = mutableListOf<StoredRecord>()
        var added = 0; var replaced = 0; var kept = 0; var same = 0
        val replacedExams = mutableSetOf<String>()
        fun relocated(record: StoredRecord) = if (fromRecordings == toRecordings) record
            else record.copy(payload = record.payload.replace("\"$fromRecordings/", "\"$toRecordings/"))
        backup.forEach { incoming ->
            val moved = relocated(incoming)
            val current = existing[incoming.key]
            when {
                current == null -> { put += moved; added++ }
                current.payload == moved.payload -> same++
                incoming.kind in examState && incoming.exam !in answered -> { put += moved; replaced++; replacedExams += incoming.exam }
                else -> kept++
            }
        }
        val packs = backupPacks.filter { (it.id to it.version) !in localPacks }.distinctBy { it.id to it.version }
        return Result(put, packs, added, replaced, kept, same, replacedExams)
    }
}
