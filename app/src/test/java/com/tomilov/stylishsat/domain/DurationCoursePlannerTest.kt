package com.tomilov.stylishsat.domain

import org.junit.Assert.*
import org.junit.Test

class DurationCoursePlannerTest {
    private fun item(id: String, skill: String, split: ContentSplit, seconds: Int, type: ExerciseType) = Exercise(
        id = id, exam = Exam.IELTS, skillId = skill, split = split, familyId = id, type = type,
        prompt = "Original task $id", acceptedAnswers = if (type == ExerciseType.SHORT_ANSWER) listOf("answer") else emptyList(),
        expectedSeconds = seconds, author = "test", criteria = if (type == ExerciseType.WRITING) listOf(LocalizedText("Criterion", "Критерий")) else emptyList(),
    )
    private fun content(): ContentPack {
        val skills = listOf("reading", "listening", "writing", "speaking").map { Skill(it, Exam.IELTS, LocalizedText(it, it), it) }
        val lessons = skills.map { Lesson("lesson-${it.id}", it.id, LocalizedText("Rule", "Правило"), LocalizedText("Body", "Текст"), LocalizedText("Example", "Пример")) }
        val items = skills.flatMap { skill ->
            val type = if (skill.id == "writing") ExerciseType.WRITING else ExerciseType.SHORT_ANSWER
            val seconds = if (type == ExerciseType.WRITING) 2400 else 90
            (1..6).map { item("${skill.id}-p$it", skill.id, ContentSplit.PRACTICE, seconds, type) } +
                (1..3).map { item("${skill.id}-a$it", skill.id, ContentSplit.ASSESSMENT, seconds, type) }
        }
        return ContentPack(id = "duration", version = 1, title = LocalizedText("Pack", "Пакет"), skills = skills, lessons = lessons, exercises = items)
    }
    @Test fun severalFreshAnswersInOneDayCannotSkipTheFirstReview() {
        var state = SkillState("reading")
        repeat(12) { index ->
            state = StudyPlanner.updateSkill(state, Attempt("a$index", "q$index", 1, Exam.IELTS, "reading", "answer", true, index.toLong(), difficulty = 1), 100)
        }
        assertEquals(101L, state.nextReviewEpochDay)
        assertEquals(0, state.reviewStep)
        state = StudyPlanner.updateSkill(state, Attempt("review", "fresh", 1, Exam.IELTS, "reading", "answer", true, 20), 101)
        assertEquals(104L, state.nextReviewEpochDay)
        repeat(5) { index -> state = StudyPlanner.updateSkill(state, Attempt("same-$index", "new-$index", 1, Exam.IELTS, "reading", "answer", true, 30L + index), 101) }
        assertEquals(104L, state.nextReviewEpochDay)
        assertEquals(1, state.reviewStep)
    }
    @Test fun anErrorRestartsTheShortReviewSequence() {
        val original = SkillState("reading", reviewStep = 3, nextReviewEpochDay = 150, lastReviewEpochDay = 130)
        val failed = StudyPlanner.updateSkill(original, Attempt("bad", "q", 1, Exam.IELTS, "reading", "wrong", false, 0), 140)
        assertEquals(141L, failed.nextReviewEpochDay)
        assertEquals(0, failed.reviewStep)
        val recovered = StudyPlanner.updateSkill(failed, Attempt("good", "q2", 1, Exam.IELTS, "reading", "answer", true, 1), 141)
        assertEquals(144L, recovered.nextReviewEpochDay)
    }
    @Test fun everyDayRespectsTheActualMinuteBudget() {
        val pack = content()
        listOf(5, 15, 30, 60).forEach { budget ->
            val plan = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, budget)
            assertEquals(28, plan.days.size)
            assertEquals((200L..227L).toList(), plan.days.map { it.epochDay })
            assertTrue(plan.days.all { day -> day.activities.sumOf { it.minutes } <= budget && day.activities.all { it.minutes > 0 } })
            assertTrue(plan.days.filter { day -> day.activities.none { it.kind == ActivityKind.ASSESSMENT } }
                .all { day -> day.skillIds.size <= 3 })
            assertTrue(plan.days.flatMap { it.activities }.all { it.exerciseId in pack.exercises.map { ex -> ex.id } })
        }
    }
    @Test fun longWritingWorkIsSplitAndEveryScheduledGroupCompletes() {
        val pack = content()
        val plan = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 15)
        val work = plan.days.flatMap { it.activities }.filter { it.kind == ActivityKind.PRACTICE && it.skillId == "writing" }
        assertTrue(work.isNotEmpty())
        assertTrue(work.groupBy { it.workId }.values.any { it.size > 1 })
        work.groupBy { it.workId }.values.forEach { pieces ->
            assertEquals(42, pieces.sumOf { it.minutes })
            assertEquals(0, pieces.last().remainingMinutes)
            assertTrue(pieces.drop(1).all { it.continuation })
        }
        val checks = plan.days.flatMap { it.activities }.filter { it.kind == ActivityKind.ASSESSMENT }
        assertEquals(4, checks.map { it.exerciseId }.distinct().size)
        assertTrue(checks.groupBy { it.workId }.values.all { it.last().remainingMinutes == 0 })
    }
    @Test fun partialWorkRetainsItsIdentityWhenTheCourseIsRebuilt() {
        val pack = content().let { pack -> pack.copy(skills = pack.skills.filter { it.id == "writing" }, lessons = pack.lessons.filter { it.skillId == "writing" }, exercises = pack.exercises.filter { it.skillId == "writing" }) }
        val original = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 15)
        val first = original.days.first()
        val partial = first.activities.last()
        assertTrue(partial.remainingMinutes > 0)
        val rebuilt = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 15, listOf(first))
        assertEquals(first, rebuilt.days.first())
        val continuation = rebuilt.days[1].activities.first()
        assertEquals(partial.workId, continuation.workId)
        assertEquals(partial.exerciseId, continuation.exerciseId)
        assertTrue(continuation.continuation)
    }

    /** Several fresh checks per skill are essential: a single-check fixture hides regeneration bugs. */
    private fun satContent(): ContentPack {
        val skills = (1..8).map { Skill("sat-$it", Exam.SAT, LocalizedText("Skill $it", "Навык $it"), "SAT") }
        val exercises = skills.flatMap { skill ->
            (1..12).map { index ->
                item("${skill.id}-p${index.toString().padStart(2, '0')}", skill.id, ContentSplit.PRACTICE, 90, ExerciseType.SHORT_ANSWER).copy(exam = Exam.SAT)
            } + (1..3).map { index ->
                item("${skill.id}-a$index", skill.id, ContentSplit.ASSESSMENT, 90, ExerciseType.SHORT_ANSWER).copy(exam = Exam.SAT)
            }
        }
        return ContentPack(id = "sat-duration-fixture", version = 1, title = LocalizedText("SAT fixture", "Тест SAT"),
            skills = skills, lessons = skills.map { Lesson("${it.id}-lesson", it.id, LocalizedText("Rule", "Правило"),
                LocalizedText("Body", "Текст"), LocalizedText("Example", "Пример"), 2) }, exercises = exercises)
    }

    private fun writingOnly(seconds: Int = 2400, lessonMinutes: Int = 8): ContentPack = content().let { pack ->
        pack.copy(skills = pack.skills.filter { it.id == "writing" },
            lessons = pack.lessons.filter { it.skillId == "writing" }.map { it.copy(estimatedMinutes = lessonMinutes) },
            exercises = pack.exercises.filter { it.skillId == "writing" }.map { it.copy(expectedSeconds = seconds) })
    }

    private fun submitted(activity: PlannedActivity, day: Long, sequence: Int = 0): Attempt {
        val exercise = requireNotNull(activity.exerciseSnapshot)
        val open = exercise.type == ExerciseType.WRITING || exercise.type == ExerciseType.SPEAKING
        return Attempt(id = "attempt-${activity.workId}", exerciseId = exercise.id, exerciseVersion = exercise.version,
            exam = exercise.exam, skillId = exercise.skillId, answer = if (open) "An original completed response." else "answer",
            correct = if (open) null else true, timestampEpochMillis = day * 86_400_000L + sequence,
            elapsedSeconds = exercise.expectedSeconds, difficulty = exercise.difficulty, split = exercise.split,
            expectedSeconds = exercise.expectedSeconds, familyId = exercise.familyId, sourceId = exercise.sourceId,
            localDateEpochDay = day, workId = activity.workId)
    }

    private fun recordFinishedWork(day: PlanDay, attempts: MutableList<Attempt>) {
        day.activities.filter { !it.isLesson && it.remainingMinutes == 0 }.forEachIndexed { index, activity ->
            if (attempts.none { it.workId == activity.workId }) attempts += submitted(activity, day.epochDay, index)
        }
    }

    @Test fun finalChecksCoverAllEightSkillsForBothAtomicAndLargeDailyBudgets() {
        val pack = satContent()
        listOf(5, 6, 7, 15, 30, 60, 360).forEach { budget ->
            val plan = StudyPlanner.course(pack, Exam.SAT, emptyList(), emptyList(), 200, budget)
            assertEquals("the initial feasible route must keep its 28-day horizon at budget $budget", 28, plan.days.size)
            val checks = plan.days.flatMap { it.activities }.filter { it.kind == ActivityKind.ASSESSMENT }
            assertEquals("coverage at $budget minutes", pack.skills.map { it.id }.toSet(), checks.map { it.skillId }.toSet())
            assertEquals("one final work per skill at $budget minutes", 8, checks.map { it.workId }.distinct().size)
            assertEquals(32, checks.sumOf { it.minutes })
            assertTrue("closed checks remain atomic", checks.all { it.minutes == 4 && it.remainingMinutes == 0 })
            assertTrue(plan.days.all { it.activities.sumOf { activity -> activity.minutes } <= budget })
            if (budget >= 60) assertEquals(8, plan.days.last().activities.count { it.kind == ActivityKind.ASSESSMENT })
        }
    }

    @Test fun sequentialDailyRebuildChecksEachSkillOnceInsteadOfRestartingTheFirstSkills() {
        val pack = satContent()
        listOf(5, 15, 30, 60).forEach { budget ->
            val completed = mutableListOf<PlanDay>()
            val attempts = mutableListOf<Attempt>()
            var plan = StudyPlanner.course(pack, Exam.SAT, emptyList(), attempts, 200, budget)
            assertEquals(28, plan.days.size)
            (1..28).forEach { number ->
                val day = plan.days[number - 1]
                assertFalse("day $number at budget $budget is reachable", day.contentExhausted)
                assertTrue(day.activities.sumOf { it.minutes } <= budget)
                completed += day
                recordFinishedWork(day, attempts)
                plan = StudyPlanner.course(pack, Exam.SAT, StudyPlanner.statesFromAttempts(pack, attempts), attempts,
                    200, budget, completed)
                assertEquals("completed days are frozen", completed, plan.days.take(number))
                assertEquals("unchanged budget and completed work must not spill after day $number at budget $budget", 28, plan.days.size)
            }
            val checks = attempts.filter { it.split == ContentSplit.ASSESSMENT }
            assertEquals(pack.skills.map { it.id }.toSet(), checks.map { it.skillId }.toSet())
            assertEquals("completed coverage must not request another fresh check for a covered skill", 8, checks.size)
            assertTrue(checks.groupBy { it.skillId }.values.all { it.size == 1 })
        }
    }

    @Test fun completedOpenResponseReviewsRetainOneThreeSevenFourteenDaySpacingAfterEveryRebuild() {
        val pack = writingOnly(seconds = 60, lessonMinutes = 1)
        val completed = mutableListOf<PlanDay>()
        val attempts = mutableListOf<Attempt>()
        var plan = StudyPlanner.course(pack, Exam.IELTS, emptyList(), attempts, 200, 5)
        (1..27).forEach { number ->
            val day = plan.days[number - 1]
            completed += day
            recordFinishedWork(day, attempts)
            plan = StudyPlanner.course(pack, Exam.IELTS, StudyPlanner.statesFromAttempts(pack, attempts), attempts,
                200, 5, completed)
        }
        val reviews = completed.filter { day -> day.activities.any { it.kind == ActivityKind.REVIEW && "/review-" in it.workId } }
        assertEquals(listOf(201L, 204L, 211L, 225L), reviews.map { it.epochDay })
        assertTrue("open responses do not acquire automatic correctness", attempts.all { it.correct == null })
    }

    @Test fun stoppedReviewCarriesAsReviewOnlyAndAdvancesItsIntervalOnCompletionOnce() {
        val pack = writingOnly(seconds = 60, lessonMinutes = 1)
        val attempts = mutableListOf<Attempt>()
        val initial = StudyPlanner.course(pack, Exam.IELTS, emptyList(), attempts, 200, 5)
        val first = initial.days.first()
        recordFinishedWork(first, attempts)
        val dueDay = initial.days[1]
        val review = dueDay.activities.first { it.kind == ActivityKind.REVIEW }
        val stopped = dueDay.copy(activities = listOf(review.copy(remainingMinutes = review.minutes + review.remainingMinutes)),
            skillIds = listOf(review.skillId), exerciseIds = listOf(review.exerciseId))
        val completed = mutableListOf(first, stopped)
        var plan = StudyPlanner.course(pack, Exam.IELTS, StudyPlanner.statesFromAttempts(pack, attempts), attempts,
            200, 5, completed)
        val carried = plan.days.drop(2).flatMap { it.activities }.filter { it.workId == review.workId }
        assertEquals(1, carried.size)
        assertEquals(ActivityKind.REVIEW, carried.single().kind)
        assertEquals(review.minutes, carried.single().minutes)
        assertEquals(review.lessonId, carried.single().lessonId)
        assertEquals(review.exerciseSnapshot, carried.single().exerciseSnapshot)
        assertEquals(0, carried.single().remainingMinutes)
        assertTrue(carried.single().continuation)
        assertEquals(completed, plan.days.take(2))
        (3..28).forEach { number ->
            val day = plan.days[number - 1]
            completed += day
            recordFinishedWork(day, attempts)
            plan = StudyPlanner.course(pack, Exam.IELTS, StudyPlanner.statesFromAttempts(pack, attempts), attempts,
                200, 5, completed)
        }
        val actualReviews = completed.filter { day -> day.activities.any {
            it.kind == ActivityKind.REVIEW && "/review-" in it.workId && it.remainingMinutes == 0
        } }
        assertEquals("three, seven, fourteen days from actual carried-review completion",
            listOf(202L, 205L, 212L, 226L), actualReviews.map { it.epochDay })
        assertTrue("a carried review never becomes a response attempt", attempts.none { it.workId == review.workId })
    }

    @Test fun pendingWorkKeepsItsOriginalPromptVersionAndRemainingDurationAfterContentUpdate() {
        val oldPack = writingOnly()
        val original = StudyPlanner.course(oldPack, Exam.IELTS, emptyList(), emptyList(), 200, 15)
        val first = original.days.first()
        val partial = first.activities.last()
        assertTrue(partial.remainingMinutes > 0)
        val oldExercise = requireNotNull(partial.exerciseSnapshot)
        val updatedPack = oldPack.copy(version = 2, exercises = oldPack.exercises.map {
            if (it.id == partial.exerciseId) it.copy(version = 2, prompt = "A different revised prompt", expectedSeconds = 60) else it
        })
        val rebuilt = StudyPlanner.course(updatedPack, Exam.IELTS, emptyList(), emptyList(), 200, 15, listOf(first))
        val continuation = rebuilt.days.drop(1).flatMap { it.activities }.filter { it.workId == partial.workId }
        assertTrue(continuation.isNotEmpty())
        assertEquals(partial.remainingMinutes, continuation.sumOf { it.minutes })
        assertEquals(0, continuation.last().remainingMinutes)
        assertTrue(continuation.all { it.continuation && it.exerciseVersion == oldExercise.version && it.exerciseSnapshot == oldExercise })
    }

    @Test fun reducingTheBudgetPreservesPendingWritingAndStillCompletesTheFinalCheck() {
        val pack = writingOnly()
        val first = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 15).days.first()
        val partial = first.activities.last()
        val rebuilt = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 5, listOf(first))
        val future = rebuilt.days.drop(1).flatMap { it.activities }
        val continued = future.filter { it.workId == partial.workId }
        assertEquals(first, rebuilt.days.first())
        assertEquals(partial.remainingMinutes, continued.sumOf { it.minutes })
        assertEquals(0, continued.last().remainingMinutes)
        assertTrue(rebuilt.days.drop(1).all { it.activities.sumOf { activity -> activity.minutes } <= 5 })
        val checks = future.filter { it.kind == ActivityKind.ASSESSMENT }
        assertEquals(1, checks.map { it.workId }.distinct().size)
        assertEquals(42, checks.sumOf { it.minutes })
        assertEquals(0, checks.last().remainingMinutes)
    }

    @Test fun latePendingWritingSharesAFeasibleRemainingBudgetWithEveryFinalCheck() {
        val pack = content()
        val writing = pack.exercises.first { it.skillId == "writing" && it.split == ContentSplit.PRACTICE }
        val oldWork = "course-IELTS-200/late-writing"
        // Older completed days may have no activity detail. Day 23 used a larger allowance;
        // its remaining 17 minutes plus the 54 check minutes fit in the final five 15-minute days.
        val completed = (1..22).map { number -> PlanDay(number, 199L + number, 60, emptyList()) } +
            PlanDay(23, 222, 60, listOf("writing"), listOf(writing.id), activities = listOf(
                PlannedActivity("late-piece", oldWork, ActivityKind.PRACTICE, "writing", writing.id, 25,
                    remainingMinutes = 17, exerciseVersion = writing.version, exerciseSnapshot = writing)))
        val rebuilt = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 15, completed)
        val future = rebuilt.days.drop(23).flatMap { it.activities }
        assertEquals(17, future.filter { it.workId == oldWork }.sumOf { it.minutes })
        assertEquals(0, future.last { it.workId == oldWork }.remainingMinutes)
        val checks = future.filter { it.kind == ActivityKind.ASSESSMENT }
        assertEquals(pack.skills.map { it.id }.toSet(), checks.map { it.skillId }.toSet())
        assertEquals(54, checks.sumOf { it.minutes })
        assertTrue(checks.groupBy { it.workId }.values.all { it.last().remainingMinutes == 0 })
        assertTrue("the old essay crosses into the check window without being discarded", rebuilt.days.any { day ->
            day.activities.any { it.workId == oldWork } && day.activities.any { it.kind == ActivityKind.ASSESSMENT }
        })
        assertTrue(rebuilt.days.drop(23).all { it.activities.sumOf { activity -> activity.minutes } <= 15 })
    }

    @Test fun earlySubmissionEndsPendingWorkAndStartsItsFirstReviewOnTheNextDay() {
        val pack = writingOnly()
        val first = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 15).days.first()
        val partial = first.activities.last()
        assertTrue(partial.remainingMinutes > 0)
        val attempt = submitted(partial, first.epochDay)
        val rebuilt = StudyPlanner.course(pack, Exam.IELTS, StudyPlanner.statesFromAttempts(pack, listOf(attempt)),
            listOf(attempt), 200, 15, listOf(first))
        assertTrue(rebuilt.days.drop(1).flatMap { it.activities }.none { it.workId == partial.workId })
        assertTrue("a submitted open response is completed even when its scheduled piece had a remainder",
            rebuilt.days[1].activities.any { it.kind == ActivityKind.REVIEW && it.skillId == "writing" })
    }

    @Test fun anInfeasibleLateBudgetCutExtendsTheCourseWithoutLosingWorkOrRestartingItsCheck() {
        val pack = writingOnly()
        val writing = pack.exercises.first { it.split == ContentSplit.PRACTICE }
        val workId = "course-IELTS-200/pending-at-original-end"
        val completed = (1..27).map { number -> PlanDay(number, 199L + number, 60, emptyList()) } +
            PlanDay(28, 227, 60, listOf("writing"), listOf(writing.id), activities = listOf(
                PlannedActivity("last-original-piece", workId, ActivityKind.PRACTICE, "writing", writing.id, 25,
                    remainingMinutes = 17, exerciseVersion = writing.version, exerciseSnapshot = writing)))
        val extended = StudyPlanner.course(pack, Exam.IELTS, emptyList(), emptyList(), 200, 5, completed)
        assertEquals(completed, extended.days.take(28))
        assertEquals("17 pending + 42 assessment minutes require twelve extra five-minute days", 40, extended.days.size)
        val future = extended.days.drop(28).flatMap { it.activities }
        assertEquals(59, future.sumOf { it.minutes })
        assertEquals(17, future.filter { it.workId == workId }.sumOf { it.minutes })
        assertTrue(extended.days.drop(28).all { it.activities.sumOf { activity -> activity.minutes } <= 5 })
        val check = future.filter { it.kind == ActivityKind.ASSESSMENT }
        assertEquals(1, check.map { it.workId }.distinct().size)
        assertEquals(42, check.sumOf { it.minutes })
        assertEquals(0, check.last().remainingMinutes)

        // Finish the old practice and the first slice of its check, then restart/replan again.
        val history = extended.days.take(32)
        val attempts = mutableListOf<Attempt>()
        history.forEach { recordFinishedWork(it, attempts) }
        assertEquals(listOf(workId), attempts.map { it.workId })
        val partialCheck = history.last().activities.last()
        assertEquals(ActivityKind.ASSESSMENT, partialCheck.kind)
        assertEquals(39, partialCheck.remainingMinutes)
        val rebuilt = StudyPlanner.course(pack, Exam.IELTS, StudyPlanner.statesFromAttempts(pack, attempts), attempts,
            200, 5, history)
        assertEquals(history, rebuilt.days.take(32))
        assertEquals(40, rebuilt.days.size)
        val remainingCheck = rebuilt.days.drop(32).flatMap { it.activities }.filter { it.kind == ActivityKind.ASSESSMENT }
        assertTrue(remainingCheck.all { it.workId == partialCheck.workId && it.continuation })
        assertEquals(39, remainingCheck.sumOf { it.minutes })
        assertEquals(0, remainingCheck.last().remainingMinutes)
        assertTrue(rebuilt.days.drop(32).flatMap { it.activities }.none { it.workId == workId })
    }
}
