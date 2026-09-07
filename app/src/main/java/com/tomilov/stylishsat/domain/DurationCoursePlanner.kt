package com.tomilov.stylishsat.domain

import java.util.ArrayDeque

/** Packs real lesson/work durations. Long open responses retain a work identity across days. */
internal object DurationCoursePlanner {
    private const val DAYS = 28
    private const val REVIEW_MINUTES = 2

    private data class Work(
        val id: String,
        val exercise: Exercise,
        val kind: ActivityKind,
        var remaining: Int,
        val lessonId: String? = null,
        var continuation: Boolean = false,
    )

    fun create(
        pack: ContentPack, exam: Exam, states: List<SkillState>, attempts: List<Attempt>,
        start: Long, budget: Int, completed: List<PlanDay>,
    ): StudyPlan {
        require(budget in 5..360)
        require(completed.all { it.dayNumber > 0 } && completed.map { it.dayNumber }.distinct().size == completed.size)
        val id = "course-${exam.name}-$start"
        val frozen = completed.associateBy { it.dayNumber }
        val byId = pack.exercises.associateBy { it.id }
        val byState = states.associateBy { it.skillId }
        val ranked = StudyPlanner.rankedSkills(pack, exam, states, start)
        val lessons = pack.lessons.groupBy { it.skillId }
        val completedWork = attempts.mapNotNull { it.workId }.toSet()
        val taught = mutableSetOf<String>()
        val introduced = mutableSetOf<String>()
        val reviewDue = mutableMapOf<String, Long>()
        val reviewStage = mutableMapOf<String, Int>()
        val queue = ArrayDeque<Work>()
        val restoredWork = mutableSetOf<String>()
        var ordinal = 0
        var cursor = 0
        val completedActivities = completed.flatMap { it.activities }
        val finalCompletedPiece = completed.sortedBy { it.dayNumber }.flatMap { it.activities }.associateBy { it.workId }
        val practiceHistory = StudyPlanner.PracticeHistory(pack, attempts, completedActivities, byId)
        val practicePools = pack.exercises.filter { it.exam == exam && it.split == ContentSplit.PRACTICE }
            .groupBy { it.skillId }.mapValues { (skill, candidates) ->
                StudyPlanner.practiceAtLevel(candidates, byState[skill]?.difficulty ?: 1)
            }
        val coveredCheckSkills = completedActivities.filter { it.kind == ActivityKind.ASSESSMENT && (it.remainingMinutes == 0 || it.workId in completedWork) }.map { it.skillId }.toSet()
        val pendingChecks = completedActivities.groupBy { it.workId }.values.map { it.last() }
            .filter { it.kind == ActivityKind.ASSESSMENT && it.remainingMinutes > 0 && it.workId !in completedWork }.associateBy { it.skillId }
        val remainingSkills = ranked.filter { it.id !in coveredCheckSkills }
        val freshChecks = StudyPlanner.assessment(pack.copy(skills = remainingSkills), exam, attempts, remainingSkills.size).associateBy { it.skillId }
        val checks = remainingSkills.mapNotNull { skill -> pendingChecks[skill.id]?.exerciseSnapshot ?: freshChecks[skill.id] }
        val checkDays = requiredCheckDays(checks, budget, pendingChecks.mapValues { it.value.remainingMinutes }).coerceIn(1, DAYS)
        val startedCheckDay = completed.filter { day -> day.activities.any { it.kind == ActivityKind.ASSESSMENT } }.minOfOrNull { it.dayNumber - 1 }
        val assessmentStart = minOf(DAYS - checkDays, startedCheckDay ?: DAYS)
        val reviewSources = (completedActivities.filter { it.kind == ActivityKind.ASSESSMENT }.mapNotNull { it.exerciseSnapshot } + checks).distinctBy { it.id to it.version }
        var checksQueued = false
        var actualReviewStateApplied = false

        fun source(skillId: String, excludedToday: Set<String>): Exercise? {
            val level = byState[skillId]?.difficulty ?: 1
            val candidates = practicePools[skillId].orEmpty().filter { it.id !in excludedToday }
            return StudyPlanner.rankPractice(candidates, level, practiceHistory, includePlanned = true).firstOrNull()
        }
        fun needsRule(skill: String): Boolean {
            val state = byState[skill]
            return state == null || state.difficulty == 1 || state.accuracy < .75f || state.independence < .75f || state.consecutiveErrors > 0
        }
        fun fitsBeforeChecks(item: Exercise, lesson: Lesson?, day: Int, availableToday: Int): Boolean {
            // Reserve the same due reviews and atomic question boundaries used by allocation.
            // Multiplying free days by budget overstates usable capacity for a pending essay/lesson.
            var lessonLeft = lesson?.estimatedMinutes ?: 0
            var workLeft = workMinutes(item)
            val due = reviewDue.toMutableMap()
            val stages = reviewStage.toMutableMap()
            for (future in day until assessmentStart) {
                var available = if (future == day) availableToday else budget
                var reviewTopics = 0
                if (future > day) {
                    ranked.filter { skill -> skill.id != item.skillId &&
                        (due[skill.id] ?: byState[skill.id]?.nextReviewEpochDay)?.let { it <= start + future } == true
                    }.take(3).forEach { skill ->
                        val reviewItem = source(skill.id, emptySet()) ?: return@forEach
                        val reviewLesson = if (open(reviewItem)) lessons[skill.id].orEmpty().firstOrNull() else null
                        val minutes = if (reviewLesson != null) minOf(3, reviewLesson.estimatedMinutes) else workMinutes(reviewItem)
                        if (minutes <= available) {
                            available -= minutes
                            reviewTopics++
                            val stage = ((stages[skill.id] ?: byState[skill.id]?.reviewStep ?: 0) + 1).coerceAtMost(3)
                            stages[skill.id] = stage
                            due[skill.id] = start + future + StudyPlanner.reviewIntervalsDays[stage]
                        }
                    }
                }
                if (reviewTopics >= 3) continue
                val lessonUsed = minOf(lessonLeft, available)
                lessonLeft -= lessonUsed; available -= lessonUsed
                if (lessonLeft > 0) continue
                if (!open(item) && workLeft <= budget && workLeft > available) continue
                workLeft -= minOf(workLeft, available)
                if (workLeft == 0) return true
            }
            return false
        }
        fun addWork(exercise: Exercise, day: Int, assessment: Boolean = false) {
            val workId = "$id/${exercise.id}/${day + 1}-${ordinal++}"
            if (!assessment && needsRule(exercise.skillId)) {
                val lesson = lessons[exercise.skillId].orEmpty().firstOrNull { it.id !in taught }
                if (lesson != null) queue.addLast(Work(workId, exercise, ActivityKind.LESSON, lesson.estimatedMinutes, lesson.id))
            }
            queue.addLast(Work(workId, exercise, if (assessment) ActivityKind.ASSESSMENT else ActivityKind.PRACTICE, workMinutes(exercise)))
            practiceHistory.notePlanned(workId, exercise)
        }
        fun restorePendingThrough(day: Int) {
            val groups = completed.filter { it.dayNumber <= day }.flatMap { it.activities }.groupBy { it.workId }
            groups.forEach { (workId, pieces) ->
                if (workId in restoredWork || workId in completedWork) return@forEach
                val last = pieces.last()
                val exercise = last.exerciseSnapshot ?: byId[last.exerciseId] ?: return@forEach
                if (last.kind == ActivityKind.REVIEW) {
                    if (last.remainingMinutes > 0) queue.addLast(Work(workId, exercise, ActivityKind.REVIEW,
                        last.remainingMinutes, last.lessonId, true))
                    restoredWork.add(workId)
                    return@forEach
                }
                practiceHistory.notePlanned(workId, exercise)
                if (last.isLesson) {
                    if (last.remainingMinutes > 0) queue.addLast(Work(workId, exercise, ActivityKind.LESSON, last.remainingMinutes, last.lessonId, true))
                    queue.addLast(Work(workId, exercise, ActivityKind.PRACTICE, workMinutes(exercise), continuation = true))
                } else if (last.remainingMinutes > 0) {
                    queue.addLast(Work(workId, exercise, last.kind, last.remainingMinutes, continuation = true))
                }
                restoredWork.add(workId)
            }
        }
        val unresolvedWork = completedActivities.groupBy { it.workId }.values.any { pieces ->
            val last = pieces.last()
            !last.isLesson && last.remainingMinutes > 0 && last.workId !in completedWork ||
                last.kind == ActivityKind.LESSON && last.workId !in completedWork ||
                last.kind == ActivityKind.REVIEW && last.remainingMinutes > 0
        }
        val minimumDays = maxOf(DAYS, (completed.maxOfOrNull { it.dayNumber } ?: 0) + if (unresolvedWork || checks.isNotEmpty()) 1 else 0)
        val result = mutableListOf<PlanDay>()
        var day = 0
        while (day < minimumDays || queue.isNotEmpty()) {
            val plannedDay = run {
            frozen[day + 1]?.let { old ->
                if (old.activities.isEmpty()) {
                    introduced.addAll(old.skillIds)
                    old.exerciseIds.mapNotNull(byId::get).forEach(practiceHistory::noteExposure)
                }
                old.activities.forEach { activity ->
                    if (activity.remainingMinutes == 0 || activity.workId in completedWork) {
                        if (activity.kind == ActivityKind.LESSON) activity.lessonId?.let(taught::add)
                        val review = activity.kind == ActivityKind.REVIEW || activity.workId.contains("/review-")
                        if (review && activity.id == finalCompletedPiece[activity.workId]?.id) {
                            val stage = ((reviewStage[activity.skillId] ?: 0) + 1).coerceAtMost(3)
                            reviewStage[activity.skillId] = stage
                            reviewDue[activity.skillId] = old.epochDay + StudyPlanner.reviewIntervalsDays[stage]
                        } else if (!review && !activity.isLesson && introduced.add(activity.skillId)) {
                            reviewDue[activity.skillId] = old.epochDay + 1
                            reviewStage[activity.skillId] = 0
                        }
                    }
                }
                return@run old
            }
            if (!actualReviewStateApplied) {
                states.forEach { state -> state.nextReviewEpochDay?.let { due ->
                    reviewDue[state.skillId] = due
                    reviewStage[state.skillId] = state.reviewStep
                } }
                actualReviewStateApplied = true
            }
            if (queue.isEmpty()) restorePendingThrough(day)
            val activities = mutableListOf<PlannedActivity>()
            val daySkills = linkedSetOf<String>()
            val dayItems = mutableSetOf<String>()
            var available = budget
            var serial = 0
            fun allocate(work: Work, amount: Int) {
                work.remaining -= amount
                activities += PlannedActivity("$id/day${day + 1}/${serial++}", work.id, work.kind,
                    work.exercise.skillId, work.exercise.id, amount, work.lessonId, work.remaining, work.continuation, work.exercise.version, work.exercise)
                work.continuation = true
                available -= amount
                daySkills += work.exercise.skillId
                if (!work.kind.let { it == ActivityKind.LESSON || it == ActivityKind.REVIEW }) dayItems += work.exercise.id
                if (work.remaining == 0) {
                    if (work.kind == ActivityKind.LESSON) work.lessonId?.let(taught::add)
                    if (work.kind == ActivityKind.PRACTICE) {
                        if (introduced.add(work.exercise.skillId) && work.exercise.skillId !in reviewDue) {
                            reviewDue[work.exercise.skillId] = start + day + 1
                            reviewStage[work.exercise.skillId] = 0
                        }
                    }
                    if (work.kind == ActivityKind.REVIEW || work.id.contains("/review-")) {
                        val stage = ((reviewStage[work.exercise.skillId] ?: byState[work.exercise.skillId]?.reviewStep ?: 0) + 1).coerceAtMost(3)
                        reviewStage[work.exercise.skillId] = stage
                        reviewDue[work.exercise.skillId] = start + day + StudyPlanner.reviewIntervalsDays[stage]
                    }
                }
            }
            if (day < assessmentStart) {
                // Due material comes before unfinished work and new topics. Brief rule reviews
                // do not create a new answer attempt or inflate mastery.
                ranked.filter { skill ->
                    val due = reviewDue[skill.id] ?: byState[skill.id]?.nextReviewEpochDay
                    due != null && due <= start + day && queue.none { it.exercise.skillId == skill.id }
                }.take(3).forEach { skill ->
                    val item = source(skill.id, dayItems) ?: return@forEach
                    val lesson = if (open(item)) lessons[skill.id].orEmpty().firstOrNull() else null
                    val minutes = if (lesson != null) minOf(3, lesson.estimatedMinutes) else workMinutes(item)
                    if (minutes <= available && (daySkills.size < 3 || skill.id in daySkills)) {
                        val kind = if (lesson != null) ActivityKind.REVIEW else ActivityKind.PRACTICE
                        allocate(Work("$id/review-${skill.id}-$day", item, kind, minutes, lesson?.id), minutes)
                        if (lesson == null) practiceHistory.notePlanned("$id/review-${skill.id}-$day", item)
                    }
                }
            } else if (!checksQueued) {
                // Carry real pending work to completion; never discard a saved partial essay.
                checks.forEach { item -> if (queue.none { it.exercise.id == item.id }) addWork(item, day, assessment = true) }
                checksQueued = true
            }
            var considered = 0
            while (available > 0 && considered++ < 100) {
                if (queue.isEmpty()) {
                    if (day >= assessmentStart || ranked.isEmpty()) break
                    val order = ranked.filter { it.id !in introduced } + List(ranked.size) { ranked[(cursor + it) % ranked.size] }
                    val skill = order.distinctBy { it.id }.firstOrNull { candidate ->
                        if ((candidate.id in daySkills && (byState[candidate.id]?.difficulty ?: 1) <= 1) ||
                            (daySkills.size >= 3 && candidate.id !in daySkills)) return@firstOrNull false
                        val item = source(candidate.id, dayItems) ?: return@firstOrNull false
                        val lesson = if (needsRule(candidate.id)) lessons[candidate.id].orEmpty().firstOrNull { it.id !in taught } else null
                        (open(item) || lesson != null || workMinutes(item) <= available) && fitsBeforeChecks(item, lesson, day, available)
                    } ?: break
                    val item = source(skill.id, dayItems) ?: break
                    addWork(item, day)
                    cursor = (ranked.indexOfFirst { it.id == skill.id } + 1) % ranked.size
                }
                val work = queue.peekFirst() ?: break
                if (day < assessmentStart && work.exercise.skillId !in daySkills && daySkills.size >= 3) break
                // Closed questions remain atomic whenever the daily budget can hold one.
                if (!open(work.exercise) && !work.kind.let { it == ActivityKind.LESSON || it == ActivityKind.REVIEW } && work.remaining > available && work.remaining <= budget) break
                allocate(work, minOf(work.remaining, available))
                if (work.remaining == 0) queue.removeFirst() else break
            }
            if (activities.isEmpty() && day >= assessmentStart && queue.isEmpty() && checksQueued && reviewSources.isNotEmpty()) {
                // If the learner finished early, use the remaining course days for reviewing
                // already completed checks rather than requesting another fresh test in the same skills.
                List(minOf(3, reviewSources.size)) { reviewSources[((day - assessmentStart) * 3 + it) % reviewSources.size] }.forEach { item ->
                    if (available > 0) allocate(Work("$id/final-review-${item.id}-$day", item, ActivityKind.REVIEW, minOf(5, available)), minOf(5, available))
                }
            }
            PlanDay(day + 1, start + day, budget, daySkills.toList(), activities.map { it.exerciseId }.distinct(),
                activities.any { it.kind == ActivityKind.REVIEW }, activities, activities.isEmpty())
            }
            result += plannedDay
            day++
        }
        return StudyPlan(id, exam, PlanMode.COURSE, start, result.sumOf { it.minutes }, ranked.map { it.id }, result,
            contentVersion = pack.version, plannerVersion = StudyPlanner.CURRENT_PLANNER_VERSION)
    }

    private fun requiredCheckDays(items: List<Exercise>, budget: Int, remainingBySkill: Map<String, Int>): Int {
        var days = 1
        var available = budget
        for (item in items) {
            var remaining = remainingBySkill[item.skillId] ?: workMinutes(item)
            while (remaining > 0) {
                if (available == 0 || (!open(item) && remaining <= budget && remaining > available)) {
                    days++; available = budget
                }
                val used = minOf(remaining, available)
                remaining -= used; available -= used
            }
        }
        return days
    }

    private fun open(exercise: Exercise) = exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING
    private fun workMinutes(exercise: Exercise) = ((exercise.expectedSeconds + 59) / 60).coerceAtLeast(1) + REVIEW_MINUTES
}
