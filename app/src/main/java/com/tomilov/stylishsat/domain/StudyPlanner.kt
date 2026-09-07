package com.tomilov.stylishsat.domain

import kotlin.math.abs

/** Scheduling and level decisions stay in code. All dates are supplied as epoch days. */
object StudyPlanner {
    const val CURRENT_PLANNER_VERSION = 2
    val reviewIntervalsDays = listOf(1, 3, 7, 14)

    /** Coverage follows the current pack, so an older, shorter diagnostic cannot complete new skills. */
    fun diagnosticComplete(pack: ContentPack, exam: Exam, attempts: List<Attempt>): Boolean {
        val skills = pack.skills.filter { it.exam == exam }
        if (skills.isEmpty()) return false
        val required = if (exam == Exam.SAT) 2 else 1
        val coverage = attempts.asSequence()
            .filter { it.exam == exam && it.split == ContentSplit.DIAGNOSTIC }
            .groupBy { it.skillId }
        return skills.all { skill ->
            coverage[skill.id].orEmpty().map { it.exerciseId }.distinct().size >= required
        }
    }

    fun diagnostic(pack: ContentPack, exam: Exam, attempts: List<Attempt> = emptyList()): List<Exercise> {
        val available = fresh(pack, exam, ContentSplit.DIAGNOSTIC, attempts)
        return pack.skills.filter { it.exam == exam }.flatMap { skill ->
            available.filter { it.skillId == skill.id }.sortedWith(compareBy({ it.difficulty }, { it.id }))
                .take(if (exam == Exam.SAT) 2 else 1)
        }
    }

    fun assessment(pack: ContentPack, exam: Exam, attempts: List<Attempt>, limit: Int = 8): List<Exercise> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val grouped = fresh(pack, exam, ContentSplit.ASSESSMENT, attempts).groupBy { it.skillId }
        // Round-robin across skills, so one long passage cannot consume the whole check.
        val skills = pack.skills.filter { it.exam == exam }
        return (0 until (grouped.values.maxOfOrNull { it.size } ?: 0)).flatMap { index ->
            skills.mapNotNull { grouped[it.id]?.getOrNull(index) }
        }.take(limit)
    }

    fun isFamiliar(exercise: Exercise, pack: ContentPack, attempts: List<Attempt>): Boolean {
        if (attempts.isEmpty()) return false
        val byId = if (attempts.any { it.familyId == null || it.sourceId == null }) pack.exercises.associateBy { it.id } else emptyMap()
        return attempts.any { attempt ->
            val earlier = byId[attempt.exerciseId]
            attempt.exerciseId == exercise.id ||
                (attempt.familyId ?: earlier?.familyId) == exercise.familyId ||
                (attempt.sourceId ?: earlier?.sourceId) == exercise.sourceId
        }
    }

    /** One history index per selection/replay operation, rather than one pack index per comparison. */
    private class SeenHistory(private val exercises: Map<String, Exercise>) {
        private val ids = hashSetOf<String>()
        private val families = hashSetOf<String>()
        private val sources = hashSetOf<String>()
        private val explicitFamilies = hashSetOf<String>()
        private val explicitSources = hashSetOf<String>()
        fun add(attempt: Attempt) {
            ids += attempt.exerciseId
            val earlier = exercises[attempt.exerciseId]
            (attempt.familyId ?: earlier?.familyId)?.let(families::add)
            (attempt.sourceId ?: earlier?.sourceId)?.let(sources::add)
            attempt.familyId?.let(explicitFamilies::add)
            attempt.sourceId?.let(explicitSources::add)
        }
        fun add(exercise: Exercise) {
            ids += exercise.id
            families += exercise.familyId
            sources += exercise.sourceId
        }
        fun contains(exercise: Exercise) = exercise.id in ids || exercise.familyId in families || exercise.sourceId in sources
        fun containsRemoved(attempt: Attempt) = attempt.exerciseId in ids ||
            attempt.familyId?.let { it in explicitFamilies } == true || attempt.sourceId?.let { it in explicitSources } == true
    }

    private fun seenHistory(pack: ContentPack, attempts: List<Attempt>): SeenHistory {
        val exercises = if (attempts.any { it.familyId == null || it.sourceId == null }) pack.exercises.associateBy { it.id } else emptyMap()
        return SeenHistory(exercises).also { seen -> attempts.forEach(seen::add) }
    }

    internal fun openResponse(exercise: Exercise) =
        exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING

    /** Task 1 in this corpus has a supplied chart; Task 2 does not. This is selection,
     * not an assessment of the learner's open-response proficiency. */
    internal fun writingFormat(exercise: Exercise): Int = if (exercise.chart != null) 0 else 1

    fun confidentForIndependentPractice(state: SkillState?): Boolean = state != null &&
        state.difficulty > 1 && state.independentCount >= 4 && state.accuracy >= .75f &&
        state.independence >= .75f && state.consecutiveErrors == 0 &&
        (state.totalExpectedSeconds == 0L || state.totalElapsedSeconds <= state.totalExpectedSeconds * 1.4)

    fun practiceChecks(pack: ContentPack, exercise: Exercise, attempts: List<Attempt>): List<LocalizedText> =
        PracticeHistory(pack, attempts).reviewChecks(exercise)

    internal class PracticeHistory(
        pack: ContentPack,
        attempts: List<Attempt>,
        planned: List<PlannedActivity> = emptyList(),
        private val byId: Map<String, Exercise> = pack.exercises.associateBy { it.id },
    ) {
        private val observed = SeenHistory(byId).also { seen -> attempts.forEach(seen::add) }
        private val scheduled = SeenHistory(emptyMap())
        private val attemptedIds = attempts.map { it.exerciseId }.toSet()
        private val scheduledIds = mutableSetOf<String>()
        private val countedWritingWork = mutableSetOf<String>()
        private val formatUses = mutableMapOf<Pair<String, Int>, Int>()
        private data class ReviewNeed(val errorType: String, val exercise: Exercise?) {
            fun matches(candidate: Exercise): Boolean = when (errorType) {
                "WORD_LIMIT" -> candidate.wordLimit != null
                "NUMERIC_FORMAT" -> candidate.type == ExerciseType.NUMERIC
                "KEY_MISMATCH" -> exercise?.let { source ->
                    source.typicalErrors.any { it in candidate.typicalErrors }
                } == true
                else -> false
            }
        }
        private val reviewNeeds = mutableMapOf<String, ReviewNeed>()

        init {
            val snapshots = planned.mapNotNull { activity -> activity.exerciseSnapshot?.let { activity.workId to it } }.toMap()
            val reviewSeen = SeenHistory(byId)
            attempts.distinctBy { it.id }.sortedWith(compareBy({ it.timestampEpochMillis }, { it.id })).forEach { attempt ->
                val exercise = snapshots[attempt.workId]?.takeIf { it.id == attempt.exerciseId && it.version == attempt.exerciseVersion }
                    ?: byId[attempt.exerciseId]
                if (exercise != null) countWriting(exercise, attempt.workId?.let { "work:$it" } ?: "attempt:${attempt.id}")
                val errorExercise = exercise?.takeIf { it.version == attempt.exerciseVersion }
                if (attempt.correct == false && attempt.errorType in setOf("WORD_LIMIT", "NUMERIC_FORMAT", "KEY_MISMATCH")) {
                    reviewNeeds[attempt.skillId] = ReviewNeed(requireNotNull(attempt.errorType), errorExercise)
                } else if (attempt.correct == true && attempt.independent && errorExercise != null && !reviewSeen.contains(errorExercise) &&
                    reviewNeeds[attempt.skillId]?.matches(errorExercise) == true) {
                    reviewNeeds.remove(attempt.skillId)
                }
                reviewSeen.add(attempt)
            }
            // A lesson and all response slices share one work ID. Reviews do not create new work.
            planned.filter { it.kind != ActivityKind.REVIEW }.forEach { activity ->
                (activity.exerciseSnapshot ?: byId[activity.exerciseId])?.let { notePlanned(activity.workId, it) }
            }
        }

        private fun countWriting(exercise: Exercise, identity: String) {
            if (exercise.type != ExerciseType.WRITING || !countedWritingWork.add(identity)) return
            val key = exercise.skillId to writingFormat(exercise)
            formatUses[key] = (formatUses[key] ?: 0) + 1
        }

        fun notePlanned(workId: String, exercise: Exercise) {
            noteExposure(exercise)
            countWriting(exercise, "work:$workId")
        }

        fun noteExposure(exercise: Exercise) {
            scheduled.add(exercise)
            scheduledIds += exercise.id
        }

        fun familiar(exercise: Exercise, includePlanned: Boolean) =
            observed.contains(exercise) || includePlanned && scheduled.contains(exercise)
        fun used(exercise: Exercise, includePlanned: Boolean) =
            exercise.id in attemptedIds || includePlanned && exercise.id in scheduledIds
        fun formatUse(skillId: String, format: Int) = formatUses[skillId to format] ?: 0
        fun remediationRank(exercise: Exercise): Int = if (reviewNeeds[exercise.skillId]?.matches(exercise) == true) 0 else 1
        fun reviewChecks(exercise: Exercise): List<LocalizedText> = reviewNeeds[exercise.skillId]
            ?.takeIf { it.matches(exercise) }?.let { AnswerChecker.reviewChecks(exercise, it.errorType) }.orEmpty()
    }

    /** Lock the available closed-question level before excluding today's assigned IDs. */
    internal fun practiceAtLevel(candidates: List<Exercise>, level: Int): List<Exercise> {
        val nearest = candidates.filterNot(::openResponse)
            .minWithOrNull(compareBy<Exercise>({ abs(it.difficulty - level) }, { it.difficulty }))?.difficulty
        return candidates.filter { openResponse(it) || it.difficulty == nearest }
    }

    internal fun rankPractice(
        candidates: List<Exercise>, level: Int, history: PracticeHistory,
        includePlanned: Boolean, preferredWritingFormat: Int? = null,
    ): List<Exercise> {
        val hasOpen = candidates.any(::openResponse)
        val base = if (hasOpen || includePlanned) {
            candidates.sortedWith(compareBy<Exercise>(
                { history.familiar(it, includePlanned) }, { history.used(it, includePlanned) },
                { abs(it.difficulty - level) }, { history.remediationRank(it) }, { it.id }))
        } else {
            // Preserve closed manual-batch ordering and its frozen history-reference contract.
            candidates.sortedWith(compareBy<Exercise>({ abs(it.difficulty - level) },
                { history.used(it, false) }, { history.familiar(it, false) }, { history.remediationRank(it) }, { it.id }))
        }
        val writing = base.filter { it.type == ExerciseType.WRITING }
        val formats = writing.map(::writingFormat).distinct()
        if (formats.size < 2) return base
        val preferred = preferredWritingFormat?.takeIf { it in formats }
            ?: formats.minWith(compareBy<Int>({ history.formatUse(writing.first().skillId, it) }, { it }))
        val orderedWriting = (writing.filter { writingFormat(it) == preferred } + writing.filter { writingFormat(it) != preferred }).iterator()
        // Keep other response types in their ranked slots when a synthetic/mixed skill is supplied.
        return base.map { if (it.type == ExerciseType.WRITING) orderedWriting.next() else it }
    }

    fun nextPractice(
        pack: ContentPack,
        exam: Exam,
        states: List<SkillState>,
        attempts: List<Attempt>,
        todayEpochDay: Long,
        limit: Int = 8,
    ): List<Exercise> {
        require(limit >= 0)
        if (limit == 0) return emptyList()
        val stateBySkill = states.associateBy { it.skillId }
        val ranked = rankedSkills(pack, exam, states, todayEpochDay)
        val history = PracticeHistory(pack, attempts)
        val pools = ranked.associate { skill ->
            val difficulty = stateBySkill[skill.id]?.difficulty ?: 1
            val candidates = practiceAtLevel(pack.exercises.filter { it.skillId == skill.id && it.split == ContentSplit.PRACTICE }, difficulty)
            val ordered = if (candidates.none(::openResponse)) rankPractice(candidates, difficulty, history, includePlanned = false) else candidates
            skill.id to ordered.toMutableList()
        }
        val openSkills = pools.filterValues { it.any(::openResponse) }.keys
        val nextWritingFormat = mutableMapOf<String, Int>()
        val result = mutableListOf<Exercise>()
        // Select in the returned round-robin order so unreturned pool items never count as planned exposure.
        while (result.size < limit && pools.values.any { it.isNotEmpty() }) {
            for (skill in ranked) {
                val remaining = pools.getValue(skill.id)
                if (remaining.isEmpty()) continue
                val next = if (skill.id !in openSkills) remaining.first() else rankPractice(remaining,
                    stateBySkill[skill.id]?.difficulty ?: 1, history, includePlanned = true,
                    preferredWritingFormat = nextWritingFormat[skill.id]).first()
                remaining.remove(next)
                result += next
                if (openResponse(next)) history.notePlanned("manual:${skill.id}:${result.size}", next)
                if (next.type == ExerciseType.WRITING) nextWritingFormat[skill.id] = 1 - writingFormat(next)
                if (result.size == limit) break
            }
        }
        return result
    }

    fun updateSkill(state: SkillState, attempt: Attempt, todayEpochDay: Long): SkillState {
        require(state.skillId == attempt.skillId) { "Attempt belongs to another skill" }
        require(attempt.difficulty in 1..3 && attempt.elapsedSeconds >= 0 && attempt.hintsUsed >= 0)
        // Open responses and external/model feedback never update correctness, levels, or intervals.
        val correct = attempt.correct ?: return state
        val levelEvidence = attempt.independent && attempt.difficulty >= state.difficulty
        // The window is the last four responses, including hints/repeats as non-confirming evidence.
        var recent = (state.recentIndependent + (levelEvidence && correct)).takeLast(4)
        var difficulty = state.difficulty
        var errors = if (correct) 0 else state.consecutiveErrors + 1
        if (errors >= 2) {
            difficulty = (difficulty - 1).coerceAtLeast(1)
            errors = 0
            recent = emptyList()
        } else if (levelEvidence && recent.size == 4 && recent.count { it } >= 3) {
            difficulty = (difficulty + 1).coerceAtMost(3)
            // A new level needs its own evidence; the same three answers cannot raise it twice.
            recent = emptyList()
        }
        val successfulDueReview = correct && attempt.independent &&
            state.nextReviewEpochDay?.let { it <= todayEpochDay } == true && state.lastReviewEpochDay != todayEpochDay
        val reviewStep = when {
            !correct -> 0
            successfulDueReview -> (state.reviewStep + 1).coerceAtMost(3)
            else -> state.reviewStep
        }
        val nextReview = when {
            !correct -> todayEpochDay + 1
            state.nextReviewEpochDay == null -> todayEpochDay + 1
            successfulDueReview -> todayEpochDay + reviewIntervalsDays[reviewStep]
            !attempt.independent && state.nextReviewEpochDay <= todayEpochDay -> todayEpochDay + 1
            else -> state.nextReviewEpochDay
        }
        return state.copy(
            difficulty = difficulty,
            attemptsCount = state.attemptsCount + 1,
            correctCount = state.correctCount + if (correct) 1 else 0,
            independentCount = state.independentCount + if (attempt.independent) 1 else 0,
            independentCorrectCount = state.independentCorrectCount + if (attempt.independent && correct) 1 else 0,
            consecutiveErrors = errors,
            recentIndependent = recent,
            reviewStep = reviewStep,
            nextReviewEpochDay = nextReview,
            lastReviewEpochDay = if (successfulDueReview) todayEpochDay else state.lastReviewEpochDay,
            totalElapsedSeconds = state.totalElapsedSeconds + attempt.elapsedSeconds,
            totalExpectedSeconds = state.totalExpectedSeconds + attempt.expectedSeconds.coerceAtLeast(0),
        )
    }

    /** History is sorted and duplicate attempt ids ignored, making process restoration idempotent. */
    fun statesFromAttempts(pack: ContentPack, attempts: List<Attempt>): List<SkillState> {
        val states = pack.skills.associate { it.id to SkillState(it.id) }.toMutableMap()
        val exercises = pack.exercises.associateBy { it.id }
        val seen = SeenHistory(exercises)
        attempts.distinctBy { it.id }.sortedWith(compareBy({ it.timestampEpochMillis }, { it.id })).forEach { attempt ->
            val exercise = exercises[attempt.exerciseId]
            val familiar = exercise?.let(seen::contains) ?: seen.containsRemoved(attempt)
            states[attempt.skillId]?.let { state ->
                states[attempt.skillId] = updateSkill(
                    state, attempt.copy(
                        isRepeat = attempt.isRepeat || familiar,
                        expectedSeconds = attempt.expectedSeconds.takeIf { it > 0 } ?: exercise?.expectedSeconds ?: 0,
                    ),
                    attempt.localDateEpochDay ?: Math.floorDiv(attempt.timestampEpochMillis, 86_400_000L),
                )
            }
            seen.add(attempt)
        }
        return states.values.toList()
    }

    fun course(
        pack: ContentPack,
        exam: Exam,
        states: List<SkillState>,
        attempts: List<Attempt>,
        startEpochDay: Long,
        dailyMinutes: Int = 30,
        completedDays: List<PlanDay> = emptyList(),
    ): StudyPlan {
        return DurationCoursePlanner.create(pack, exam, states, attempts, startEpochDay, dailyMinutes, completedDays)
    }

    fun intensive(
        pack: ContentPack,
        exam: Exam,
        states: List<SkillState>,
        startEpochDay: Long,
        hours: Int,
    ): StudyPlan {
        require(hours in listOf(2, 4, 6)) { "Choose a 2, 4, or 6 hour intensive" }
        val priority = rankedSkills(pack, exam, states, startEpochDay).take(3).map { it.id }
        val minutes = allocate(hours * 60, listOf(35, 90, 20, 50, 35, 10))
        val blocks = PlanBlockType.entries.mapIndexed { index, type ->
            PlanBlock("intensive-${type.name}", type, minutes[index], if (type == PlanBlockType.BREAK) emptyList() else priority)
        }
        return StudyPlan("intensive-${exam.name}-$startEpochDay", exam, PlanMode.INTENSIVE, startEpochDay, hours * 60, priority,
            blocks = blocks, plannerVersion = CURRENT_PLANNER_VERSION)
    }

    /** The supplied budget is time still available; completed blocks keep their original duration. */
    fun recalculateIntensive(plan: StudyPlan, remainingMinutes: Int): StudyPlan {
        require(plan.mode == PlanMode.INTENSIVE && remainingMinutes >= 0)
        val remaining = plan.blocks.filterNot { it.completed }
        require(remaining.isNotEmpty() || remainingMinutes == 0) { "All intensive blocks are already completed" }
        val allocation = allocate(remainingMinutes, remaining.map { it.minutes })
        var index = 0
        val updated = plan.blocks.map { if (it.completed) it else it.copy(minutes = allocation[index++]) }
        return plan.copy(totalMinutes = updated.sumOf { it.minutes }, blocks = updated)
    }

    private fun fresh(pack: ContentPack, exam: Exam, split: ContentSplit, attempts: List<Attempt>): List<Exercise> {
        val seen = seenHistory(pack, attempts)
        return pack.exercises.filter { it.exam == exam && it.split == split && !seen.contains(it) }.sortedBy { it.id }
    }

    internal fun rankedSkills(pack: ContentPack, exam: Exam, states: List<SkillState>, today: Long): List<Skill> {
        val byId = states.associateBy { it.skillId }
        fun rank(state: SkillState?): Int = when {
            state?.nextReviewEpochDay?.let { it <= today } == true -> 0
            state != null && state.attemptsCount > 0 && (
                state.accuracy < .75f || state.independence < .75f || state.difficulty == 1 ||
                    (state.totalExpectedSeconds > 0 && state.totalElapsedSeconds > state.totalExpectedSeconds * 1.4)
                ) -> 1
            state == null || state.attemptsCount == 0 -> 2
            else -> 3
        }
        return pack.skills.filter { it.exam == exam }.sortedWith(compareBy<Skill>(
            { rank(byId[it.id]) },
            { byId[it.id]?.nextReviewEpochDay ?: Long.MAX_VALUE },
            { byId[it.id]?.accuracy ?: 0f },
            { byId[it.id]?.independence ?: 0f },
            { it.id },
        ))
    }

    private fun allocate(total: Int, weights: List<Int>): List<Int> {
        if (weights.isEmpty()) return emptyList()
        val safe = if (weights.sum() == 0) List(weights.size) { 1 } else weights
        val weightTotal = safe.sum().toLong()
        val values = safe.map { (total.toLong() * it / weightTotal).toInt() }.toMutableList()
        val remainders = safe.indices.sortedWith(compareByDescending<Int> { total.toLong() * safe[it] % weightTotal }.thenBy { it })
        repeat(total - values.sum()) { values[remainders[it]]++ }
        return values
    }
}
