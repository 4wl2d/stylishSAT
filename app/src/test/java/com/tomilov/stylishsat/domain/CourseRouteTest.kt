package com.tomilov.stylishsat.domain

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class CourseRouteTest {
    private val pack: ContentPack by lazy {
        ContentPackCodec.decode(listOf(File("src/main/assets/content/seed-v1.json"), File("app/src/main/assets/content/seed-v1.json")).first { it.isFile }.readText())
    }
    private val start = 20_000L

    private fun plan(exam: Exam, daysAhead: Long?, target: String = "", known: String = "", today: Long = start, completed: List<PlanDay> = emptyList(), minutes: Int = 30) =
        StudyPlanner.course(pack, exam, emptyList(), emptyList(), start, minutes, completed,
            ExamProfile(exam, target = target, knownResult = known, examDateEpochDay = daysAhead?.let { today + it }, dailyMinutes = minutes), today)

    private fun practiceSkills(plan: StudyPlan, days: IntRange) = plan.days.filter { it.dayNumber in days }.flatMap { it.activities }
        .filter { it.kind == ActivityKind.PRACTICE || it.kind == ActivityKind.LESSON }.map { it.skillId }.toSet()

    @Test fun twoWeeksAndThreeMonthsGetDifferentRoutes() {
        val sprint = plan(Exam.SAT, 14, target = "1400", known = "1250")
        val long = plan(Exam.SAT, 90, target = "1400", known = "1250")
        assertEquals(14, sprint.days.size); assertEquals(90, long.days.size)
        assertEquals(RoutePace.SPRINT, sprint.route!!.pace); assertEquals(RoutePace.LONG, long.route!!.pace)
        // Two weeks: no foundation block, new work only in the three weakest skills, one full sitting midway.
        assertEquals(listOf(RoutePhaseKind.BUILD, RoutePhaseKind.EXAM_PRACTICE), sprint.route!!.phases.map { it.kind })
        assertEquals(3, sprint.route!!.focusSkillIds.size)
        val assessmentStart = sprint.days.first { day -> day.activities.any { it.kind == ActivityKind.ASSESSMENT } }.dayNumber
        assertTrue(practiceSkills(sprint, 1 until assessmentStart).all { it in sprint.route!!.focusSkillIds })
        assertEquals(listOf(7), sprint.route!!.sittingDays)
        // Three months: rules first for every topic, then mixed practice, then harder practice and a sitting every two weeks.
        assertEquals(listOf(RoutePhaseKind.FOUNDATION, RoutePhaseKind.BUILD, RoutePhaseKind.EXAM_PRACTICE), long.route!!.phases.map { it.kind })
        assertTrue(long.route!!.focusSkillIds.isEmpty())
        assertTrue(practiceSkills(long, 1..90).size == 8)
        assertTrue(long.route!!.sittingDays.size >= 4 && long.route!!.sittingDays.zipWithNext().all { (a, b) -> b - a <= 14 })
        assertTrue(long.route!!.sittingDays.last() in 80..87)
        val lessons = { p: StudyPlan, range: IntRange -> p.days.filter { it.dayNumber in range }.flatMap { it.activities }.count { it.kind == ActivityKind.LESSON } }
        assertTrue(lessons(long, 1..14) > lessons(sprint, 1..14))
        listOf(sprint, long).forEach { p -> assertTrue(p.days.all { day -> day.activities.sumOf { it.minutes } <= 30 }) }
    }

    @Test fun closeToTheExamPracticeMovesUpALevel() {
        val long = plan(Exam.SAT, 90)
        fun level(kind: RoutePhaseKind): Double {
            val phase = long.route!!.phases.first { it.kind == kind }
            return long.days.filter { it.dayNumber in phase.firstDay..phase.lastDay }.flatMap { it.activities }
                .filter { it.kind == ActivityKind.PRACTICE }.mapNotNull { it.exerciseSnapshot?.difficulty }.average()
        }
        assertTrue("${level(RoutePhaseKind.FOUNDATION)} vs ${level(RoutePhaseKind.EXAM_PRACTICE)}", level(RoutePhaseKind.EXAM_PRACTICE) > level(RoutePhaseKind.FOUNDATION))
    }

    @Test fun goalAndKnownResultShapeTheRoute() {
        val small = plan(Exam.IELTS, 40, target = "6.5", known = "6").route!!
        val large = plan(Exam.IELTS, 40, target = "7.5", known = "5.5").route!!
        val met = plan(Exam.IELTS, 40, target = "6.5", known = "7").route!!
        fun foundation(route: CourseRoute) = route.phases.firstOrNull { it.kind == RoutePhaseKind.FOUNDATION }?.let { it.lastDay - it.firstDay + 1 } ?: 0
        assertTrue(foundation(large) > foundation(small))
        // Already at the goal: no rule-first block, more exam practice and a sitting every week.
        assertEquals(0, foundation(met)); assertTrue(met.targetMet)
        assertTrue(met.sittingDays.size > small.sittingDays.size)
        // A two-week IELTS route narrows to two skills unless the goal is already reached.
        assertEquals(2, plan(Exam.IELTS, 14, target = "7", known = "6").route!!.focusSkillIds.size)
        assertTrue(plan(Exam.IELTS, 14, target = "6", known = "7").route!!.focusSkillIds.isEmpty())
    }

    @Test fun withoutAFutureDateTheCourseKeepsItsOriginal28Days() {
        val none = plan(Exam.SAT, null)
        assertEquals(28, none.days.size); assertEquals(RoutePace.OPEN, none.route!!.pace)
        assertEquals(StudyPlanner.course(pack, Exam.SAT, emptyList(), emptyList(), start, 30).days, none.days)
        val passed = plan(Exam.SAT, -3)
        assertEquals(28, passed.days.size); assertTrue(passed.route!!.datePassed)
        // An exam beyond the horizon: the plan covers the next 120 days in its rules-first phase, with no final checks yet.
        val far = plan(Exam.SAT, 200)
        assertEquals(CourseRoutes.MAX_DAYS, far.days.size); assertTrue(far.route!!.capped)
        assertTrue(far.days.none { day -> day.activities.any { it.kind == ActivityKind.ASSESSMENT } })
        assertEquals(RoutePhaseKind.BUILD, far.route!!.phases.last().kind)
        assertTrue(far.route!!.sittingDays.all { it <= CourseRoutes.MAX_DAYS })
    }

    @Test fun completedDaysStayAndTheRestEndsAtTheExam() {
        val first = plan(Exam.SAT, 30)
        val done = first.days.take(5)
        // Ten calendar days later with five days done: those five stay exactly, and twenty more run to the exam.
        val later = plan(Exam.SAT, 20, today = start + 10, completed = done)
        assertEquals(25, later.days.size)
        assertEquals(done, later.days.take(5))
        assertEquals(start + 10, later.route!!.computedOnEpochDay)
    }

    @Test fun resultsAreReadOnlyOnTheirExamScale() {
        assertEquals(1350.0, CourseRoutes.result(Exam.SAT, "1350")!!, 0.0)
        assertEquals(1500.0, CourseRoutes.result(Exam.SAT, "about 1500 total")!!, 0.0)
        assertNull(CourseRoutes.result(Exam.SAT, "1355")); assertNull(CourseRoutes.result(Exam.SAT, "7"))
        assertEquals(6.5, CourseRoutes.result(Exam.IELTS, "6,5")!!, 0.0)
        assertNull(CourseRoutes.result(Exam.IELTS, "6.3")); assertNull(CourseRoutes.result(Exam.IELTS, "1350")); assertNull(CourseRoutes.result(Exam.IELTS, ""))
    }
}
