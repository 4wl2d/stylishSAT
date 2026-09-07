package com.tomilov.stylishsat.domain

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/** Unknown JSON fields and unsupported versions fail at the import boundary. */
object ContentPackCodec {
    const val SCHEMA_VERSION = 2
    private val supportedSchemas = 1..SCHEMA_VERSION
    val json = Json { prettyPrint = true; encodeDefaults = true }

    fun decode(raw: String): ContentPack {
        val document = json.parseToJsonElement(raw)
        require(document.jsonObject["schemaVersion"]?.jsonPrimitive?.intOrNull in supportedSchemas) {
            "Content package must declare supported schemaVersion 1 or $SCHEMA_VERSION"
        }
        return json.decodeFromJsonElement<ContentPack>(document).also(::validate)
    }
    fun encode(pack: ContentPack): String = json.encodeToString(pack.also(::validate))

    /** Content identity also follows the actual source, independent of publisher-assigned ids. */
    fun sourceSignatures(exercise: Exercise): List<String> = buildList {
        listOfNotNull(exercise.passage, exercise.sampleAnswer).forEach { passage ->
            val normalized = Normalizer.normalize(passage, Normalizer.Form.NFKC)
                .replace(Regex("[\\s\\p{Z}]+"), " ").trim()
            if (normalized.isNotEmpty()) add("passage:$normalized")
        }
        listOfNotNull(exercise.audioAssetPath, exercise.sampleAudioAssetPath).forEach { path ->
            val normalized = path.split('/').filter { it.isNotEmpty() && it != "." }.joinToString("/")
            if (normalized.isNotEmpty()) add("audio:$normalized")
        }
    }

    fun validate(pack: ContentPack) {
        require(pack.schemaVersion in supportedSchemas) { "Unsupported content schema ${pack.schemaVersion}" }
        require(pack.id.isNotBlank() && pack.version > 0) { "Pack id and positive version are required" }
        require(pack.skills.isNotEmpty() && pack.exercises.isNotEmpty()) { "Content pack is empty" }
        require(pack.skills.map { it.id }.distinct().size == pack.skills.size) { "Duplicate skill ids" }
        require(pack.lessons.map { it.id }.distinct().size == pack.lessons.size) { "Duplicate lesson ids" }
        require(pack.exercises.map { it.id }.distinct().size == pack.exercises.size) { "Duplicate exercise ids" }
        val skills = pack.skills.associateBy { it.id }
        pack.skills.forEach { skill ->
            require(skill.id.isNotBlank() && skill.prerequisites.all { it in skills }) { "Invalid skill ${skill.id}" }
        }
        pack.lessons.forEach { lesson ->
            require(lesson.skillId in skills && lesson.estimatedMinutes > 0) { "Invalid lesson ${lesson.id}" }
        }
        pack.exercises.forEach { item ->
            require(skills[item.skillId]?.exam == item.exam) { "Unknown or mismatched skill for ${item.id}" }
            require(item.id.isNotBlank() && item.familyId.isNotBlank() && item.sourceId.isNotBlank()) { "Missing identity for ${item.id}" }
            require(item.version > 0 && item.difficulty in 1..3 && item.expectedSeconds > 0) { "Invalid exercise metadata for ${item.id}" }
            require(item.prompt.isNotBlank() && item.author.isNotBlank()) { "Missing prompt or authorship for ${item.id}" }
            require(item.wordLimit == null || item.wordLimit > 0) { "Invalid word limit for ${item.id}" }
            require(item.minWords == null || item.minWords > 0) { "Invalid minimum words for ${item.id}" }
            when (item.type) {
                ExerciseType.WRITING, ExerciseType.SPEAKING -> require(item.criteria.isNotEmpty()) { "Missing criteria for ${item.id}" }
                ExerciseType.NUMERIC -> require(item.acceptedAnswers.isNotEmpty() && item.acceptedAnswers.all { AnswerChecker.rational(it) != null }) { "Invalid numeric key for ${item.id}" }
                ExerciseType.MULTIPLE_CHOICE -> require(item.options.size >= 2 && item.acceptedAnswers.isNotEmpty() && item.acceptedAnswers.all { it in item.options }) { "Invalid options or key for ${item.id}" }
                ExerciseType.SHORT_ANSWER -> require(item.acceptedAnswers.isNotEmpty() && item.acceptedAnswers.none { it.isBlank() }) { "Missing key for ${item.id}" }
            }
            if (item.audioAssetPath != null) {
                require(!item.audioAssetPath.startsWith('/') && ".." !in item.audioAssetPath.split('/')) { "Unsafe audio asset path" }
                require(!item.transcript.isNullOrBlank()) { "Missing audio transcript for ${item.id}" }
            }
            item.sampleAudioAssetPath?.let { path ->
                require(pack.schemaVersion >= 2) { "Sample audio requires schema 2" }
                require(item.type == ExerciseType.SPEAKING && !item.sampleAnswer.isNullOrBlank()) { "Sample audio requires a Speaking sample transcript" }
                require(path.startsWith("audio/") && ".." !in path.split('/')) { "Unsafe sample audio path" }
            }
            require(item.transcriptSegments.all { it.startMs >= 0 && it.endMs > it.startMs && it.text.isNotBlank() }) { "Invalid transcript timings for ${item.id}" }
            item.chart?.let { chart ->
                require(chart.labels.isNotEmpty() && chart.series.isNotEmpty()) { "Empty chart for ${item.id}" }
                require(chart.series.all { it.values.size == chart.labels.size && it.values.all(Double::isFinite) }) { "Chart values mismatch for ${item.id}" }
            }
        }
        require(pack.exercises.groupBy { it.familyId }.values.none { items -> items.map { it.split }.distinct().size > 1 }) { "Problem family leaks across content splits" }
        require(pack.exercises.groupBy { it.sourceId }.values.none { items -> items.map { it.split }.distinct().size > 1 }) { "Source text or audio leaks across content splits" }
        val sourceSplits = mutableMapOf<String, ContentSplit>()
        pack.exercises.forEach { exercise ->
            sourceSignatures(exercise).forEach { signature ->
                val previous = sourceSplits.putIfAbsent(signature, exercise.split)
                require(previous == null || previous == exercise.split) { "Identical source text or audio leaks across content splits under different ids" }
            }
        }
    }
}
