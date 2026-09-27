package com.tomilov.stylishsat.domain

import java.util.Locale

/** One passage or recording with every question written for it, in pack order. */
data class SourceSection(
    val exam: Exam,
    val skillId: String,
    val sourceId: String,
    val split: ContentSplit,
    val title: String,
    val exercises: List<Exercise>,
) {
    val passage: String? get() = exercises.firstNotNullOfOrNull { it.passage }
    val audioAssetPath: String? get() = exercises.firstNotNullOfOrNull { it.audioAssetPath }
    val listening: Boolean get() = audioAssetPath != null
    val words: Int get() = passage?.let(AnswerChecker::wordCount) ?: 0
}

/** Groups a pack by source. Only sources with a shared passage or recording and at least two questions form a section. */
object Sections {
    /** IELTS Reading allows about 60 minutes for 40 questions. */
    const val READING_SECONDS_PER_QUESTION = 90
    /** Computer-delivered IELTS Listening allows two minutes to check answers after the recording. */
    const val LISTENING_CHECK_SECONDS = 120

    fun of(pack: ContentPack, exam: Exam, split: ContentSplit? = null, skillId: String? = null): List<SourceSection> =
        of(pack.exercises.filter { it.exam == exam && (split == null || it.split == split) && (skillId == null || it.skillId == skillId) })

    fun of(exercises: List<Exercise>): List<SourceSection> = exercises
        .filter { it.passage != null || it.audioAssetPath != null }
        .groupBy { Triple(it.exam, it.skillId, it.sourceId) }
        .filterValues { it.size >= 2 && it.map { item -> item.split }.distinct().size == 1 }
        .map { (key, items) -> SourceSection(key.first, key.second, key.third, items.first().split, title(items), items) }

    fun title(exercises: List<Exercise>): String =
        exercises.firstNotNullOfOrNull { it.sourceTitle } ?: humanize(exercises.first().sourceId)

    /** "reading-v3-avel-coldroom" → "Avel coldroom". */
    fun humanize(sourceId: String): String {
        val words = sourceId.split('-').filter { it.isNotBlank() }.toMutableList()
        if (words.firstOrNull() in setOf("reading", "listening")) words.removeAt(0)
        if (words.size > 1 && (words.first().matches(Regex("v\\d+")) || words.first() in setOf("a", "d", "p"))) words.removeAt(0)
        return words.joinToString(" ").replaceFirstChar { it.titlecase(Locale.ROOT) }.ifBlank { sourceId }
    }

    /** 1-based display numbers in section order. */
    fun numbers(exercises: List<Exercise>): Map<String, Int> = exercises.mapIndexed { index, item -> item.id to index + 1 }.toMap()

    /** How many of the section's questions already have any saved answer, whatever the version. */
    fun answered(section: SourceSection, attempts: List<Attempt>): Int {
        val ids = attempts.asSequence().filter { it.exam == section.exam }.map { it.exerciseId }.toSet()
        return section.exercises.count { it.id in ids }
    }

    /** Exam-condition clock for a reading section; listening follows its recording instead. */
    fun strictSeconds(section: SourceSection): Int? =
        if (section.listening) null else section.exercises.size * READING_SECONDS_PER_QUESTION
}
