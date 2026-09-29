package com.tomilov.stylishsat.domain

import kotlin.math.abs

/**
 * Uncalibrated exam papers assembled from unseen items. Lengths and clocks follow the published test formats;
 * results are raw counts and time only. No scaled SAT score or IELTS band is derived anywhere.
 */
object ExamPapers {
    /** Digital SAT: two Reading and Writing modules of 27 questions in 32 minutes, two Math modules of 22 in 35. */
    enum class SatSection(val blueprint: List<Pair<String, Int>>, val seconds: Int) {
        // Reading and Writing questions are grouped by content domain in this order.
        RW(listOf("sat_craft" to 7, "sat_information" to 7, "sat_conventions" to 7, "sat_expression" to 6), 32 * 60),
        MATH(listOf("sat_algebra" to 8, "sat_advanced" to 8, "sat_data" to 3, "sat_geometry" to 3), 35 * 60);
        val questions: Int get() = blueprint.sumOf { it.second }
    }

    const val SAT_BREAK_SECONDS = 10 * 60
    /** A first module at or above this share of correct answers leads to the harder second module. Practice rule only. */
    const val HIGHER_ROUTE_SHARE = 0.6
    const val IELTS_READING_QUESTIONS = 40
    const val IELTS_READING_SECONDS = 60 * 60
    const val IELTS_LISTENING_RECORDINGS = 4
    /** Paper-based Listening allows ten minutes to transfer and check answers after the last recording. */
    const val IELTS_LISTENING_TRANSFER_SECONDS = 10 * 60
    const val IELTS_TASK1_SECONDS = 20 * 60
    const val IELTS_TASK2_SECONDS = 40 * 60

    fun satStages(sections: List<SatSection>): List<String> = sections.flatMap { listOf("${it.name}1", "${it.name}2") }

    fun section(stage: String): SatSection = SatSection.valueOf(stage.dropLast(1))

    fun route(outcomes: List<PaperScoring.Outcome>): ModuleRoute {
        val raw = PaperScoring.raw(outcomes)
        return if (raw.closed > 0 && raw.correct >= raw.closed * HIGHER_ROUTE_SHARE) ModuleRoute.HIGHER else ModuleRoute.LOWER
    }

    /** Target share of difficulty levels 1, 2 and 3 in a module. */
    internal fun mix(route: ModuleRoute?): Map<Int, Int> = when (route) {
        null -> mapOf(1 to 3, 2 to 4, 3 to 3)
        ModuleRoute.HIGHER -> mapOf(1 to 0, 2 to 4, 3 to 6)
        ModuleRoute.LOWER -> mapOf(1 to 6, 2 to 4, 3 to 0)
    }

    /** Largest-remainder split of [total] across weighted levels. */
    internal fun split(total: Int, weights: Map<Int, Int>): Map<Int, Int> {
        val sum = weights.values.sum().coerceAtLeast(1)
        val base = weights.mapValues { (_, weight) -> total * weight / sum }.toMutableMap()
        weights.keys.sortedWith(compareByDescending<Int> { total * weights.getValue(it) % sum }.thenBy { it })
            .take(total - base.values.sum()).forEach { base[it] = base.getValue(it) + 1 }
        return base
    }

    private fun fresh(pack: ContentPack, attempts: List<Attempt>, used: Collection<Exercise>): (Exercise) -> Boolean {
        val usedIds = used.map { it.id }.toSet(); val usedFamilies = used.map { it.familyId }.toSet(); val usedSources = used.map { it.sourceId }.toSet()
        return { item -> item.id !in usedIds && item.familyId !in usedFamilies && item.sourceId !in usedSources && !StudyPlanner.isFamiliar(item, pack, attempts) }
    }

    /** One SAT module from unseen assessment items first, then unseen practice items, at the route's difficulty mix. */
    fun satModule(pack: ContentPack, attempts: List<Attempt>, stage: String, route: ModuleRoute?, used: Collection<Exercise> = emptyList()): PaperPart {
        val section = section(stage)
        val isFresh = fresh(pack, attempts, used)
        val weights = mix(route)
        val chosen = section.blueprint.flatMap { (skill, count) ->
            val pool = pack.exercises.filter { it.exam == Exam.SAT && it.skillId == skill && it.split != ContentSplit.DIAGNOSTIC &&
                !StudyPlanner.openResponse(it) && isFresh(it) }
                .sortedWith(compareBy<Exercise>({ it.split != ContentSplit.ASSESSMENT }, { it.id })).toMutableList()
            val picked = mutableListOf<Exercise>()
            val families = mutableSetOf<String>()
            val preferred = weights.maxBy { it.value }.key
            fun take(level: Int?, limit: Int) {
                var remaining = limit
                // Stable sort keeps assessment items ahead of practice items at the same distance.
                val candidates = pool.filter { level == null || it.difficulty == level }.sortedBy { abs(it.difficulty - (level ?: preferred)) }
                for (item in candidates) {
                    if (remaining == 0) break
                    if (item.familyId in families) continue
                    picked += item; families += item.familyId; pool.remove(item); remaining--
                }
            }
            split(count, weights).toSortedMap().forEach { (level, wanted) -> take(level, wanted) }
            // Fill any gap from the nearest remaining level.
            if (picked.size < count) take(null, count - picked.size)
            picked
        }
        val ordered = when (section) {
            SatSection.RW -> chosen.sortedWith(compareBy({ item -> section.blueprint.indexOfFirst { it.first == item.skillId } }, { it.difficulty }, { it.id }))
            SatSection.MATH -> chosen.sortedWith(compareBy({ it.difficulty }, { item -> section.blueprint.indexOfFirst { it.first == item.skillId } }, { it.id }))
        }
        val number = stage.last()
        return PaperPart(
            id = stage, title = (if (section == SatSection.RW) "Reading and Writing" else "Math") + " · Module $number", exercises = ordered,
            timeLimitSeconds = section.seconds, oneAtATime = true, stage = stage, route = route,
            shortfall = (section.questions - ordered.size).coerceAtLeast(0),
            breakAfterSeconds = if (stage == "RW2") SAT_BREAK_SECONDS else 0,
        )
    }

    private fun freshSections(pack: ContentPack, attempts: List<Attempt>, skillId: String): List<SourceSection> =
        Sections.of(pack, Exam.IELTS, skillId = skillId).filter { section ->
            section.split != ContentSplit.DIAGNOSTIC && section.exercises.none { StudyPlanner.isFamiliar(it, pack, attempts) }
        }

    /** Three full-length passages when they are unseen; otherwise unseen shorter passages until 40 questions. */
    fun ieltsReading(pack: ContentPack, attempts: List<Attempt>): PaperPart? {
        val candidates = freshSections(pack, attempts, "ielts_reading").sortedWith(compareBy<SourceSection>(
            { it.exercises.size < StudyPlanner.PAPER_SOURCE_MINIMUM }, { it.split != ContentSplit.ASSESSMENT },
            { it.exercises.map { item -> item.difficulty }.average() }, { it.sourceId }))
        val chosen = mutableListOf<SourceSection>()
        for (section in candidates) {
            if (chosen.sumOf { it.exercises.size } >= IELTS_READING_QUESTIONS || chosen.size == 4) break
            chosen += section
        }
        if (chosen.isEmpty()) return null
        val exercises = chosen.flatMap { it.exercises }
        return PaperPart("READING", "Academic Reading", exercises, timeLimitSeconds = IELTS_READING_SECONDS, stage = "READING",
            shortfall = (IELTS_READING_QUESTIONS - exercises.size).coerceAtLeast(0))
    }

    /** Four unseen recordings, each played once, then ten minutes to transfer and check. */
    fun ieltsListening(pack: ContentPack, attempts: List<Attempt>): PaperPart? {
        val fresh = freshSections(pack, attempts, "ielts_listening").filter { it.listening }
        // Reserved assessment recordings first; any gap is filled with conversations until the paper has two, as in IELTS.
        val chosen = fresh.filter { it.split == ContentSplit.ASSESSMENT }.sortedBy { it.sourceId }.take(IELTS_LISTENING_RECORDINGS).toMutableList()
        while (chosen.size < IELTS_LISTENING_RECORDINGS) {
            val needConversation = chosen.count { it.speakers > 1 } < 2
            chosen += fresh.filter { it !in chosen }.minWithOrNull(compareBy<SourceSection>({ needConversation && it.speakers < 2 }, { it.sourceId })) ?: break
        }
        if (chosen.isEmpty()) return null
        // IELTS order: everyday conversation, everyday monologue, academic discussion, lecture.
        chosen.sortWith(compareBy<SourceSection>({ section -> section.exercises.maxOf { it.difficulty } >= 3 }, { it.speakers < 2 }, { it.sourceId }))
        return PaperPart("LISTENING", "Listening", chosen.flatMap { it.exercises }, transferSeconds = IELTS_LISTENING_TRANSFER_SECONDS, stage = "LISTENING",
            shortfall = (IELTS_LISTENING_RECORDINGS - chosen.size).coerceAtLeast(0) * 10)
    }

    /** Task 1 (20 minutes) and Task 2 (40 minutes). Unseen full-length tasks first, otherwise the one answered longest ago. */
    fun ieltsWriting(pack: ContentPack, attempts: List<Attempt>): List<PaperPart> {
        val last = attempts.filter { it.exam == Exam.IELTS }.groupBy { it.exerciseId }.mapValues { (_, list) -> list.maxOf { it.timestampEpochMillis } }
        fun pick(task1: Boolean): Exercise? = pack.exercises.filter { it.exam == Exam.IELTS && it.type == ExerciseType.WRITING &&
            it.split != ContentSplit.DIAGNOSTIC && (it.chart != null || it.figure != null) == task1 }
            .minWithOrNull(compareBy<Exercise>({ StudyPlanner.isFamiliar(it, pack, attempts) }, { last[it.id] ?: 0L }, { it.split != ContentSplit.ASSESSMENT }, { it.id }))
        return listOfNotNull(
            pick(true)?.let { PaperPart("TASK1", "Writing Task 1", listOf(it), timeLimitSeconds = IELTS_TASK1_SECONDS, stage = "TASK1") },
            pick(false)?.let { PaperPart("TASK2", "Writing Task 2", listOf(it), timeLimitSeconds = IELTS_TASK2_SECONDS, stage = "TASK2") },
        )
    }
}
