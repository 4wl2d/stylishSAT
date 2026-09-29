package com.tomilov.stylishsat.domain

/** Sentence spans used by the highlighter. Presentation only; highlights never reach marking. */
object Passages {
    private val boundary = Regex("(?<=[.!?][\"'”’)]?)\\s+(?=[\"'“‘(]?[A-Z0-9])|\\n+")

    /** Ranges that cover the whole text in order; each range is one sentence plus its trailing whitespace. */
    fun sentences(text: String): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val ranges = mutableListOf<IntRange>()
        var start = 0
        boundary.findAll(text).forEach { match ->
            val end = match.range.last + 1
            if (end > start) ranges += start until end
            start = end
        }
        if (start < text.length) ranges += start until text.length
        return ranges
    }
}
