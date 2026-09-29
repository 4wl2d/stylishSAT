package com.tomilov.stylishsat.domain

import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlinx.serialization.Serializable

/** How much time is left decides the shape of the route; without a future exam date the course keeps its original 28 days. */
@Serializable enum class RoutePace { OPEN, SPRINT, STEADY, LONG }

/**
 * FOUNDATION teaches the rule before each new topic; BUILD is the ordinary mix; EXAM_PRACTICE moves practice one level harder.
 * Final skill checks are still placed by the planner in the last days.
 */
@Serializable enum class RoutePhaseKind { FOUNDATION, BUILD, EXAM_PRACTICE }

@Serializable data class RoutePhase(val kind: RoutePhaseKind, val firstDay: Int, val lastDay: Int)

/**
 * The course shape derived from the exam date, goal and known result. Scores are only compared with each other to size the gap;
 * nothing here predicts a result. Sittings are suggested days for an uncalibrated paper in the Exam tab, outside the daily minutes.
 */
@Serializable
data class CourseRoute(
    val days: Int,
    val pace: RoutePace,
    val computedOnEpochDay: Long,
    val examDateEpochDay: Long? = null,
    val daysLeft: Int? = null,
    val datePassed: Boolean = false,
    val capped: Boolean = false,
    val target: Double? = null,
    val known: Double? = null,
    val focusSkillIds: List<String> = emptyList(),
    val phases: List<RoutePhase> = emptyList(),
    val sittingDays: List<Int> = emptyList(),
) {
    val gap: Double? get() = if (target != null && known != null) target - known else null
    val targetMet: Boolean get() = gap?.let { it <= 0 } == true
    fun phaseOn(dayNumber: Int): RoutePhaseKind? = phases.firstOrNull { dayNumber in it.firstDay..it.lastDay }?.kind
}

object CourseRoutes {
    const val DEFAULT_DAYS = 28
    /**
     * About four months are planned day by day (a plan stores each day's exercises, so this bounds its size). A later exam keeps
     * its phases timed to the real date; days beyond the horizon, including the final checks, appear as days are completed.
     */
    const val MAX_DAYS = 120
    const val SPRINT_MAX_DAYS = 21
    const val STEADY_MAX_DAYS = 56

    /** Reads a SAT total (400–1600) or an IELTS band (1–9 in half bands) from free text such as "1350" or "6,5". */
    fun result(exam: Exam, text: String): Double? {
        val number = Regex("\\d+(?:[.,]\\d+)?").find(text)?.value?.replace(',', '.')?.toDoubleOrNull() ?: return null
        return when (exam) {
            Exam.SAT -> number.takeIf { it in 400.0..1600.0 && it % 10.0 == 0.0 }
            Exam.IELTS -> number.takeIf { it in 1.0..9.0 && (it * 2) % 1.0 == 0.0 }
        }
    }

    /** A plain threshold, not a calibrated estimate: at this gap the route spends longer on rules before mixed practice. */
    private fun largeGap(exam: Exam, gap: Double) = gap >= if (exam == Exam.SAT) 150.0 else 1.0

    /**
     * [completedThrough] days are already done and frozen. The rest of the route runs from [today] to the day before the exam,
     * so the same learner gets a different route as the date approaches or moves.
     */
    fun of(exam: Exam, profile: ExamProfile?, start: Long, today: Long, completedThrough: Int, ranked: List<Skill>): CourseRoute {
        val target = profile?.target?.let { result(exam, it) }
        val known = profile?.knownResult?.let { result(exam, it) }
        val date = profile?.examDateEpochDay
        val left = date?.let { (it - today).toInt() }
        if (date == null || left == null || left < 1) {
            return CourseRoute(DEFAULT_DAYS, RoutePace.OPEN, today, date, left, datePassed = date != null, target = target, known = known)
        }
        val first = completedThrough + 1
        val days = completedThrough + minOf(left, MAX_DAYS)
        // Phases are measured against the real distance to the exam, then cut at the planning horizon.
        val window = left
        val end = completedThrough + left
        val pace = when {
            left <= SPRINT_MAX_DAYS -> RoutePace.SPRINT
            left <= STEADY_MAX_DAYS -> RoutePace.STEADY
            else -> RoutePace.LONG
        }
        val gap = if (target != null && known != null) target - known else null
        val met = gap != null && gap <= 0
        val large = gap != null && largeGap(exam, gap)
        val foundationShare = when {
            met || pace == RoutePace.SPRINT -> 0.0
            pace == RoutePace.STEADY -> if (large) 0.3 else 0.2
            else -> if (large) 0.4 else 0.3
        }
        val examShare = if (met || pace == RoutePace.SPRINT) 0.5 else 0.3
        val foundation = (window * foundationShare).roundToInt()
        val harder = (window * examShare).roundToInt().coerceAtMost(window - foundation)
        val phases = listOf(
            RoutePhase(RoutePhaseKind.FOUNDATION, first, first + foundation - 1),
            RoutePhase(RoutePhaseKind.BUILD, first + foundation, end - harder),
            RoutePhase(RoutePhaseKind.EXAM_PRACTICE, end - harder + 1, end),
        ).map { it.copy(lastDay = minOf(it.lastDay, days)) }.filter { it.lastDay >= it.firstDay }
        // Two weeks cannot rebuild every skill: unless the goal is already reached, new work narrows to the weakest skills.
        val focus = if (pace == RoutePace.SPRINT && !met) ranked.take(if (exam == Exam.SAT) 3 else 2).map { it.id } else emptyList()
        return CourseRoute(days, pace, today, date, left, capped = left > MAX_DAYS, target = target, known = known,
            focusSkillIds = focus, phases = phases, sittingDays = sittings(pace, met, first, foundation, end).filter { it <= days })
    }

    private fun sittings(pace: RoutePace, met: Boolean, first: Int, foundation: Int, days: Int): List<Int> {
        val window = days - first + 1
        if (pace == RoutePace.SPRINT) return if (window >= 6) listOf(first - 1 + ceil(window / 2.0).toInt()) else emptyList()
        val interval = if (met) 7 else 14
        val result = generateSequence(first + foundation + 6) { it + interval }.takeWhile { it <= days - 3 }.toMutableList()
        // The last full sitting falls in the final ten days, with a few days left to review it.
        if (result.isEmpty() || result.last() < days - 10) result += days - 3
        return result.filter { it >= first }.distinct()
    }
}
