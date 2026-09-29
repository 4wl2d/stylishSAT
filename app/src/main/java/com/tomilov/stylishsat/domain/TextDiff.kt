package com.tomilov.stylishsat.domain

/** Word-level before/after comparison of two drafts. Presentation only: it never scores a response. */
object TextDiff {
    enum class Kind { SAME, ADDED, REMOVED }
    data class Part(val kind: Kind, val text: String)

    /** Words keep attached punctuation; line breaks survive as their own tokens so paragraphs stay visible. */
    fun tokens(text: String): List<String> = Regex("\\n+|[^\\s]+").findAll(text).map { it.value }.toList()

    /** Longest-common-subsequence diff. Inputs above [limit] tokens fall back to one removed and one added block. */
    fun words(before: String, after: String, limit: Int = 4_000): List<Part> {
        val a = tokens(before); val b = tokens(after)
        if (a.size.toLong() * b.size > limit.toLong() * limit) {
            return listOfNotNull(before.takeIf { it.isNotBlank() }?.let { Part(Kind.REMOVED, it) }, after.takeIf { it.isNotBlank() }?.let { Part(Kind.ADDED, it) })
        }
        val lengths = Array(a.size + 1) { IntArray(b.size + 1) }
        for (i in a.indices.reversed()) for (j in b.indices.reversed()) {
            lengths[i][j] = if (a[i] == b[j]) lengths[i + 1][j + 1] + 1 else maxOf(lengths[i + 1][j], lengths[i][j + 1])
        }
        val parts = mutableListOf<Part>()
        fun push(kind: Kind, token: String) {
            val last = parts.lastOrNull()
            if (last != null && last.kind == kind) parts[parts.size - 1] = last.copy(text = join(last.text, token))
            else parts += Part(kind, token)
        }
        var i = 0; var j = 0
        while (i < a.size && j < b.size) {
            when {
                a[i] == b[j] -> { push(Kind.SAME, a[i]); i++; j++ }
                lengths[i + 1][j] >= lengths[i][j + 1] -> push(Kind.REMOVED, a[i++])
                else -> push(Kind.ADDED, b[j++])
            }
        }
        while (i < a.size) push(Kind.REMOVED, a[i++])
        while (j < b.size) push(Kind.ADDED, b[j++])
        return parts
    }

    private fun join(left: String, token: String): String = when {
        token.startsWith("\n") || left.endsWith("\n") -> left + token
        else -> "$left $token"
    }

    /** Counts of words added and removed, for a compact summary line. */
    fun changedWords(parts: List<Part>): Pair<Int, Int> =
        parts.filter { it.kind == Kind.ADDED }.sumOf { AnswerChecker.wordCount(it.text) } to
            parts.filter { it.kind == Kind.REMOVED }.sumOf { AnswerChecker.wordCount(it.text) }
}
