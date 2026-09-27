package com.tomilov.stylishsat.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class StudyRhythmTest {
    private val utc = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 9, 26).toEpochDay()

    private fun attempt(id: String, day: Long?, seconds: Int, millis: Long = 0) =
        Attempt(id, "ex-$id", 1, Exam.SAT, "skill", "a", true, millis, elapsedSeconds = seconds, localDateEpochDay = day)

    @Test fun streakCountsBackFromToday() {
        assertEquals(3, StudyRhythm.streak(setOf(today, today - 1, today - 2, today - 4), today))
    }

    @Test fun streakStaysAliveUntilTodayIsStudied() {
        assertEquals(2, StudyRhythm.streak(setOf(today - 1, today - 2), today))
    }

    @Test fun missedYesterdayBreaksStreak() {
        assertEquals(0, StudyRhythm.streak(setOf(today - 2, today - 3), today))
        assertEquals(0, StudyRhythm.streak(emptySet(), today))
    }

    @Test fun secondsGroupBySavedLocalDateBeforeTimestamp() {
        val lateEvening = LocalDate.of(2026, 9, 25).atTime(23, 30).toInstant(ZoneOffset.UTC).toEpochMilli()
        val byDay = StudyRhythm.secondsByDay(listOf(
            attempt("a", today, 50),
            attempt("b", today, 70),
            attempt("c", null, 30, lateEvening),
            attempt("d", today - 3, 0),
        ), utc)
        assertEquals(mapOf(today to 120, today - 1 to 30, today - 3 to 0), byDay)
    }

    @Test fun minutesRoundUpAndIgnoreNegativeTime() {
        assertEquals(0, StudyRhythm.minutes(0))
        assertEquals(1, StudyRhythm.minutes(1))
        assertEquals(2, StudyRhythm.minutes(61))
        assertEquals(0, StudyRhythm.minutes(-5))
    }
}
