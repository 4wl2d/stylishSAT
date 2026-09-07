package com.tomilov.stylishsat.domain

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** Open-task coverage is a scheduling decision; model feedback is never level evidence. */
class PracticeCoverageTest {
    private val chart = ChartData("Visits", "Year", "Visits", "thousands", listOf("2020", "2025"),
        listOf(ChartSeries("Library", listOf(20.0, 30.0))))

    private fun item(id: String, type: ExerciseType, difficulty: Int = 1, task1: Boolean = false,
        source: String = id, split: ContentSplit = ContentSplit.PRACTICE) = Exercise(
        id = id, exam = Exam.IELTS, skillId = "skill", difficulty = difficulty, split = split,
        familyId = id, sourceId = source, type = type, prompt = "Original task $id", author = "test",
        acceptedAnswers = if (type == ExerciseType.SHORT_ANSWER) listOf("answer") else emptyList(),
        expectedSeconds = if (type == ExerciseType.WRITING) 1200 else 60, chart = if (task1) chart else null,
    )

    private fun pack(items: List<Exercise>) = ContentPack(id = "coverage", version = 7,
        title = LocalizedText("Coverage", "Охват"),
        skills = listOf(Skill("skill", Exam.IELTS, LocalizedText("Skill", "Навык"), "IELTS")),
        lessons = listOf(Lesson("rule", "skill", LocalizedText("Rule", "Правило"),
            LocalizedText("Body", "Текст"), LocalizedText("Example", "Пример"), 2)), exercises = items)

    private fun attempt(exercise: Exercise, id: String = "attempt-${exercise.id}", day: Long = 100,
        workId: String? = null) = Attempt(id, exercise.id, exercise.version, exercise.exam, exercise.skillId,
        "A completed response.", if (StudyPlanner.openResponse(exercise)) null else true, day * 86_400_000L,
        elapsedSeconds = exercise.expectedSeconds, difficulty = exercise.difficulty, split = exercise.split,
        familyId = exercise.familyId, sourceId = exercise.sourceId, expectedSeconds = exercise.expectedSeconds,
        localDateEpochDay = day, workId = workId)

    private fun activity(exercise: Exercise, work: String, kind: ActivityKind, part: Int,
        remaining: Int = 0) = PlannedActivity("$work-$part", work, kind, exercise.skillId, exercise.id, 2,
        lessonId = if (kind == ActivityKind.LESSON) "rule" else null, remainingMinutes = remaining,
        exerciseVersion = exercise.version, exerciseSnapshot = exercise)

    @Test fun writingBatchStartsWithLessUsedFormatThenAlternatesDespiteFixedLevel() {
        val items = (1..4).flatMap { number -> listOf(
            item("chart-$number", ExerciseType.WRITING, 1, task1 = true),
            item("essay-$number", ExerciseType.WRITING, 2),
        ) }
        val content = pack(items)
        val history = listOf(attempt(items[0]), attempt(items[2]))
        val selected = StudyPlanner.nextPractice(content, Exam.IELTS, listOf(SkillState("skill")), history, 101, 6)
        assertEquals(listOf(false, true, false, true, false, true), selected.map { it.chart != null })
        assertEquals(6, selected.map { it.id }.distinct().size)
        assertEquals("fresh chart within its format", "chart-3", selected[1].id)
        assertEquals("selection does not promote open-response difficulty", SkillState("skill"),
            StudyPlanner.statesFromAttempts(content, history + selected.map { attempt(it) }).single())
    }

    @Test fun speakingUsesFreshHigherLevelBeforeKnownSourceWithoutPromotingSkill() {
        val seen = item("a-seen", ExerciseType.SPEAKING, 1, source = "shared")
        val sibling = item("b-unseen-question-known-source", ExerciseType.SPEAKING, 1, source = "shared")
        val fresh = item("z-fresh", ExerciseType.SPEAKING, 3)
        val check = item("check", ExerciseType.SPEAKING, split = ContentSplit.ASSESSMENT)
        val content = pack(listOf(seen, sibling, fresh, check))
        val history = listOf(attempt(seen))
        assertEquals(fresh, StudyPlanner.nextPractice(content, Exam.IELTS, emptyList(), history, 101, 1).single())
        val course = StudyPlanner.course(content, Exam.IELTS, emptyList(), history, 101, 15)
        assertEquals(fresh.id, course.days.flatMap { it.activities }.first { it.kind == ActivityKind.PRACTICE }.exerciseId)
        assertEquals(SkillState("skill"), StudyPlanner.statesFromAttempts(content, history + attempt(fresh)).single())
    }

    @Test fun onlyReturnedManualWorkCountsAsPlannedExposureAcrossSkills() {
        val content = pack(listOf(
            item("a-first", ExerciseType.SPEAKING).copy(skillId = "a"),
            item("a-unreturned", ExerciseType.SPEAKING, source = "shared").copy(skillId = "a"),
            item("b-first", ExerciseType.SPEAKING, source = "shared").copy(skillId = "b"),
            item("b-second", ExerciseType.SPEAKING).copy(skillId = "b"),
        )).copy(skills = listOf("a", "b").map { Skill(it, Exam.IELTS, LocalizedText(it, it), "IELTS") })
        assertEquals(listOf("a-first", "b-first"),
            StudyPlanner.nextPractice(content, Exam.IELTS, emptyList(), emptyList(), 100, 2).map { it.id })
    }

    @Test fun coursePrefersNewClosedSourceWithinLockedLevelAndKeepsFamiliarityEvidence() {
        val seen = item("a-seen", ExerciseType.SHORT_ANSWER, source = "shared")
        val sibling = item("b-unseen-question-known-source", ExerciseType.SHORT_ANSWER, source = "shared")
        val fresh = item("z-fresh-source", ExerciseType.SHORT_ANSWER)
        val harder = item("c-harder-fresh", ExerciseType.SHORT_ANSWER, 2)
        val check = item("check", ExerciseType.SHORT_ANSWER, split = ContentSplit.ASSESSMENT)
        val content = pack(listOf(seen, sibling, fresh, harder, check))
        val history = listOf(attempt(seen))
        val course = StudyPlanner.course(content, Exam.IELTS, listOf(SkillState("skill")), history, 101, 15)
        val practice = course.days.flatMap { it.activities }.filter { it.kind == ActivityKind.PRACTICE }
        assertEquals(fresh.id, practice.first().exerciseId)
        assertTrue(practice.all { it.exerciseSnapshot!!.difficulty == 1 })
        assertTrue(StudyPlanner.isFamiliar(sibling, content, history))
        val replay = StudyPlanner.statesFromAttempts(content, history + attempt(sibling, day = 101)).single()
        assertEquals("a new question from the same passage is not independent evidence", 1, replay.independentCount)
        assertEquals(1, replay.difficulty)
    }

    @Test fun writingFormatCountsCompletedAndPendingWorkOnceUsingPinnedSnapshots() {
        val task1 = item("chart", ExerciseType.WRITING, task1 = true)
        val task2 = item("essay", ExerciseType.WRITING, 2)
        // The updated bank changes an old ID's format. The pinned task remains Task 1.
        val content = pack(listOf(task1.copy(version = 2, chart = null), task2))
        val pieces = listOf(
            activity(task1, "chart-work", ActivityKind.LESSON, 0),
            activity(task1, "chart-work", ActivityKind.PRACTICE, 1, remaining = 12),
            activity(task1, "chart-work", ActivityKind.PRACTICE, 2),
            activity(task2, "essay-work", ActivityKind.LESSON, 0),
            activity(task2, "essay-work", ActivityKind.PRACTICE, 1, remaining = 12),
            activity(task1, "review", ActivityKind.REVIEW, 0),
        )
        val history = StudyPlanner.PracticeHistory(content,
            listOf(attempt(task1, workId = "chart-work")), pieces)
        assertEquals(1, history.formatUse("skill", 0))
        assertEquals(1, history.formatUse("skill", 1))
        val pending = pieces.last { it.workId == "essay-work" }
        val day = PlanDay(1, 100, 15, listOf("skill"), activities = pieces.filter { it.kind != ActivityKind.REVIEW })
        val rebuilt = StudyPlanner.course(content, Exam.IELTS, emptyList(),
            listOf(attempt(task1, workId = "chart-work")), 100, 15, listOf(day))
        assertEquals(day, rebuilt.days.first())
        val continuation = rebuilt.days.drop(1).flatMap { it.activities }.filter { it.workId == pending.workId }
        assertEquals(12, continuation.sumOf { it.minutes })
        assertTrue(continuation.all { it.exerciseSnapshot == task2 && it.continuation })
    }

    private fun bundled(): ContentPack = ContentPackCodec.decode(listOf(
        File("src/main/assets/content/seed-v1.json"), File("app/src/main/assets/content/seed-v1.json"),
    ).first { it.isFile }.readText())

    @Test fun realBankManualWritingBatchIncludesBothFormatsAndStartsWithLessUsedOne() {
        val full = bundled()
        val writing = full.exercises.filter { it.exam == Exam.IELTS && it.type == ExerciseType.WRITING }
        val content = full.copy(exercises = writing)
        val history = writing.filter { it.split == ContentSplit.PRACTICE && it.chart != null }.take(2).map { attempt(it) }
        val selected = StudyPlanner.nextPractice(content, Exam.IELTS, emptyList(), history, 101, 6)
        assertEquals(listOf(false, true, false, true, false, true), selected.map { it.chart != null })
        assertEquals(6, selected.map { it.id }.distinct().size)
        assertTrue(selected.none { StudyPlanner.isFamiliar(it, content, history) })
    }

    @Test fun realBankFifteenMinuteCourseCompletesBothWritingFormatsAfterDailyRebuilds() {
        val content = bundled()
        val start = 100L
        val completed = mutableListOf<PlanDay>()
        val history = mutableListOf<Attempt>()
        var course = StudyPlanner.course(content, Exam.IELTS, emptyList(), history, start, 15)
        val initialWriting = course.days.flatMap { it.activities }.filter { it.kind == ActivityKind.PRACTICE }
            .mapNotNull { it.exerciseSnapshot }.filter { it.type == ExerciseType.WRITING }
        assertEquals(setOf(false, true), initialWriting.map { it.chart != null }.toSet())
        for (number in 1..28) {
            assertEquals("unchanged budget at day $number", 28, course.days.size)
            val day = course.days[number - 1]
            assertTrue(day.activities.sumOf { it.minutes } <= 15)
            completed += day
            day.activities.filter { !it.isLesson && it.remainingMinutes == 0 }.forEach { activity ->
                if (history.none { it.workId == activity.workId }) history += attempt(requireNotNull(activity.exerciseSnapshot),
                    id = "attempt-${activity.workId}", day = day.epochDay, workId = activity.workId)
            }
            course = StudyPlanner.course(content, Exam.IELTS, StudyPlanner.statesFromAttempts(content, history),
                history, start, 15, completed)
            assertEquals(completed, course.days.take(number))
        }
        assertEquals(28, course.days.size)
        val practice = completed.flatMap { it.activities }.filter { it.kind == ActivityKind.PRACTICE && it.remainingMinutes == 0 }
            .map { requireNotNull(it.exerciseSnapshot) }
        assertEquals(setOf(false, true), practice.filter { it.type == ExerciseType.WRITING }.map { it.chart != null }.toSet())
        val openSkillIds = content.exercises.filter(StudyPlanner::openResponse).map { it.skillId }.toSet()
        StudyPlanner.statesFromAttempts(content, history).filter { it.skillId in openSkillIds }.forEach {
            assertEquals("open feedback must not change ${it.skillId}", SkillState(it.skillId), it)
        }
        assertEquals(content.skills.filter { it.exam == Exam.IELTS }.map { it.id }.toSet(),
            history.filter { it.split == ContentSplit.ASSESSMENT }.map { it.skillId }.toSet())
        assertEquals(4, history.count { it.split == ContentSplit.ASSESSMENT })
    }

    @Test(timeout = 15_000) fun actualSatBankReplansEveryCompletedDayAcrossAttemptIdSeeds() {
        val content = bundled()
        repeat(4) { seed ->
            val random = java.util.Random(seed.toLong())
            val completed = mutableListOf<PlanDay>()
            val history = mutableListOf<Attempt>()
            var course = StudyPlanner.course(content, Exam.SAT, emptyList(), history, 20_727, 15)
            for (number in 1..28) {
                assertEquals("SAT seed=$seed before day=$number", 28, course.days.size)
                val day = course.days[number - 1]
                var sequence = 0L
                day.activities.filter { !it.isLesson && it.remainingMinutes == 0 }.forEach { activity ->
                    if (history.none { it.workId == activity.workId }) {
                        val item = requireNotNull(activity.exerciseSnapshot)
                        history += attempt(item, id = java.util.UUID(random.nextLong(), random.nextLong()).toString(),
                            day = day.epochDay, workId = activity.workId).copy(
                            timestampEpochMillis = day.epochDay * 86_400_000L + 32_400_000L + ++sequence * 1_000,
                            elapsedSeconds = 2, isRepeat = StudyPlanner.isFamiliar(item, content, history))
                    }
                }
                completed += day
                System.err.println("SAT_REPLAN_BEGIN seed=$seed day=$number attempts=${history.size} last=${day.activities.lastOrNull()?.workId}")
                course = StudyPlanner.course(content, Exam.SAT, StudyPlanner.statesFromAttempts(content, history),
                    history, 20_727, 15, completed)
                System.err.println("SAT_REPLAN_END seed=$seed day=$number days=${course.days.size}")
                assertEquals(completed, course.days.take(number))
            }
            assertEquals(28, course.days.size)
            assertEquals(8, history.count { it.split == ContentSplit.ASSESSMENT })
        }
    }

    @Test fun plannerVersionDistinguishesLegacyPlansFromNewCourseAndIntensiveAlgorithms() {
        val content = pack(listOf(item("question", ExerciseType.SHORT_ANSWER)))
        val legacy = StudyPlan("legacy", Exam.IELTS, PlanMode.COURSE, 100, 420, listOf("skill"))
        assertEquals(0, legacy.plannerVersion)
        assertEquals(StudyPlanner.CURRENT_PLANNER_VERSION,
            StudyPlanner.course(content, Exam.IELTS, emptyList(), emptyList(), 100, 15).plannerVersion)
        assertEquals(StudyPlanner.CURRENT_PLANNER_VERSION,
            StudyPlanner.intensive(content, Exam.IELTS, emptyList(), 100, 4).plannerVersion)
    }
}
