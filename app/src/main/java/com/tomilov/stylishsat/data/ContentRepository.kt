package com.tomilov.stylishsat.data

import android.content.Context
import androidx.room.withTransaction
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.security.MessageDigest
import java.util.Locale

val storageJson = Json { encodeDefaults = true; ignoreUnknownKeys = false; isLenient = false }
private val contextStopWords = setOf("the", "and", "for", "from", "this", "that", "with", "what", "which", "how", "does", "are", "was", "were", "write", "more", "than", "words", "complete", "sentence", "following", "explain", "no", "not", "one", "two", "three", "answer", "question", "text", "passage", "choice", "choose", "best", "can", "should", "must", "has", "have", "will", "where", "when", "each", "these", "those", "это", "как", "что", "для", "или")

@Serializable
data class BundledPackInfo(val schemaVersion: Int, val contentId: String, val contentVersion: Int,
    val contentSchemaVersion: Int, val sha256: String)

fun bundledPackInfo(context: Context): BundledPackInfo = context.assets.open("content/bundle-info.json").bufferedReader().use {
    storageJson.decodeFromString<BundledPackInfo>(it.readText())
}.also { require(it.schemaVersion == 1 && it.contentVersion > 0 && it.sha256.matches(Regex("[a-f0-9]{64}"))) }

class ContentRepository(private val context: Context, private val db: StudyDatabase) {
    private val dao = db.dao()
    @Volatile private var cachedPack: ContentPack? = null
    suspend fun initialize(): ContentPack = withContext(Dispatchers.IO) {
        val info = bundledPackInfo(context)
        val installed = dao.packHeaders()
        if (installed.isEmpty() || info.contentVersion > installed.maxOf { it.version }) {
            val bytes = context.assets.open("content/seed-v1.json").use { it.readBytes() }
            require(bytes.size <= 16 * 1024 * 1024) { "Package exceeds 16 MB" }
            require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) } == info.sha256) { "Bundled content integrity mismatch" }
            val bundled = ContentPackCodec.decode(bytes.toString(Charsets.UTF_8))
            require(bundled.id == info.contentId && bundled.version == info.contentVersion && bundled.schemaVersion == info.contentSchemaVersion) { "Bundled metadata mismatch" }
            installValidated(bundled)
        } else activePack()
    }
    suspend fun activePack(): ContentPack = withContext(Dispatchers.IO) {
        val latest = dao.packHeaders().maxByOrNull { it.version } ?: error("No content package is installed")
        cachedPack?.takeIf { it.id == latest.id && it.version == latest.version }
            ?: storageJson.decodeFromString<ContentPack>(requireNotNull(dao.pack(latest.id, latest.version)).payload).also { cachedPack = it }
    }
    suspend fun allExercises(): List<Exercise> = withContext(Dispatchers.IO) {
        dao.packs().flatMap { storageJson.decodeFromString<ContentPack>(it.payload).exercises }.distinctBy { it.id to it.version }
    }

    suspend fun importPack(raw: String): ContentPack = withContext(Dispatchers.IO) {
        require(raw.toByteArray().size <= 16 * 1024 * 1024) { "Package exceeds 16 MB" }
        val pack = ContentPackCodec.decode(raw)
        installValidated(pack)
    }
    private suspend fun installValidated(pack: ContentPack): ContentPack {
        // A JSON import can reference only bundled media. Media archive import needs a separate verified installer.
        pack.exercises.flatMap { listOfNotNull(it.audioAssetPath, it.sampleAudioAssetPath) }.distinct().forEach { path ->
            require(path.startsWith("audio/") && !path.contains("..")) { "Invalid media path" }
            context.assets.open(path).close()
        }
        db.withTransaction {
            val existing = dao.packs()
            require(existing.all { it.id == pack.id }) { "Use an update of the installed package" }
            require(existing.none { it.version >= pack.version }) { "Package version must increase" }
            val previous = existing.flatMap { storageJson.decodeFromString<ContentPack>(it.payload).exercises }
            val byVersion = previous.associateBy { it.id to it.version }
            val familySplits = previous.associate { it.familyId to it.split }
            val sourceSplits = previous.associate { it.sourceId to it.split }
            val signatureSplits = previous.flatMap { exercise -> ContentPackCodec.sourceSignatures(exercise).map { it to exercise.split } }.toMap()
            pack.exercises.forEach { exercise ->
                byVersion[exercise.id to exercise.version]?.let {
                    require(it == exercise) { "Changed exercise requires a new exercise version: ${exercise.id}" }
                }
                require(familySplits[exercise.familyId]?.let { it == exercise.split } != false) { "Family split cannot change" }
                require(sourceSplits[exercise.sourceId]?.let { it == exercise.split } != false) { "Source split cannot change" }
                require(ContentPackCodec.sourceSignatures(exercise).all { signature -> signatureSplits[signature]?.let { it == exercise.split } != false }) { "Original text or audio cannot move to another split" }
            }
            dao.insertPack(StoredPack(pack.id, pack.version, storageJson.encodeToString(pack)))
            dao.clearSearch()
            dao.addSearch(pack.lessons.map { LessonSearch(it.id, it.skillId, "${it.title.en} ${it.title.ru} ${it.body.en} ${it.body.ru} ${it.workedExample.en} ${it.workedExample.ru}") })
        }
        cachedPack = pack
        return pack
    }
    suspend fun contextFor(skillId: String, query: String): List<Lesson> {
        val pack = activePack()
        val tokens = Regex("[\\p{L}\\p{N}]{2,}").findAll(query).map { it.value.lowercase(Locale.ROOT) }
            .filterNot { it in contextStopWords }.distinct().take(8).map { "\"$it\"" }.toList()
        val match = tokens.joinToString(" OR ")
        val relatedHits = if (tokens.isEmpty()) emptyList() else dao.searchForSkill(skillId, match)
        val otherHits = if (tokens.isEmpty()) emptyList() else dao.search(match)
        val byId = pack.lessons.associateBy { it.id }
        return (relatedHits.mapNotNull(byId::get) + pack.lessons.filter { it.skillId == skillId } + otherHits.mapNotNull(byId::get))
            .distinctBy { it.id }.take(3)
    }
}
