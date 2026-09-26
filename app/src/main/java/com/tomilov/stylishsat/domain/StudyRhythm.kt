package com.tomilov.stylishsat.domain

import java.time.Instant
import java.time.ZoneId

/** Calendar views of saved attempts. Presentation only: never an input to grading, mastery or scheduling. */
object StudyRhythm {
    fun day(attempt: Attempt, zone: ZoneId): Long = attempt.localDateEpochDay
        ?: Instant.ofEpochMilli(attempt.timestampEpochMillis).atZone(zone).toLocalDate().toEpochDay()

    /** Saved answer time per local day; a day with any saved attempt is present even at zero seconds. */
    fun secondsByDay(attempts: List<Attempt>, zone: ZoneId): Map<Long, Int> =
        attempts.groupBy { day(it, zone) }.mapValues { (_, items) -> items.sumOf { it.elapsedSeconds.coerceAtLeast(0) } }

    /** Consecutive studied days ending today, or ending yesterday while today is still open. */
    fun streak(studiedDays: Set<Long>, today: Long): Int {
        var cursor = if (today in studiedDays) today else today - 1
        var count = 0
        while (cursor in studiedDays) { count++; cursor-- }
        return count
    }

    /** Rounded up so any saved work shows as at least one minute. */
    fun minutes(seconds: Int): Int = (seconds.coerceAtLeast(0) + 59) / 60
}
