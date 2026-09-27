package com.tomilov.stylishsat.domain

import java.io.File
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

/** Full bank contract plus independently recomputed legacy Math answers. New-item review is file-backed. */
class ContentBankTest {
    private fun content(): ContentPack {
        val file = listOf(
            File("src/main/assets/content/seed-v1.json"),
            File("app/src/main/assets/content/seed-v1.json"),
        ).firstOrNull { it.isFile } ?: error("Bundled seed package is missing")
        return ContentPackCodec.decode(file.readText())
    }

    @Test fun bundledPackageDecodesStrictlyAndItsMediaSegmentsStayWithinFiles() {
        val pack = content()
        // Package5 keeps the 810 published exercises first and adds 67 long-passage questions and 7 Task 1 visuals.
        assertEquals(884, pack.exercises.size)
        assertEquals(810, pack.exercises.take(810).count { it.format == null && it.group == null && it.figure == null })
        assertEquals(48, pack.lessons.size)
        assertEquals(288, pack.exercises.count { it.exam == Exam.SAT })
        assertEquals(6, pack.exercises.count { it.sampleAudioAssetPath != null })
        assertEquals(3, pack.schemaVersion)
        assertEquals(8, pack.skills.count { it.exam == Exam.SAT })
        assertEquals(4, pack.skills.count { it.exam == Exam.IELTS })
        assertEquals(16, StudyPlanner.diagnostic(pack, Exam.SAT).size)
        assertEquals(4, StudyPlanner.diagnostic(pack, Exam.IELTS).size)
        val clips = pack.exercises.filter { it.audioAssetPath != null }
        assertTrue(clips.isNotEmpty())
        clips.forEach { exercise ->
            assertTrue(exercise.id, exercise.transcriptSegments.isNotEmpty())
            assertEquals(exercise.id, exercise.transcript, exercise.transcriptSegments.joinToString(" ") { it.text })
        }
    }

    @Test fun fullLengthPassagesCoverTheMissingFormatsAndStayReservedForSittings() {
        val pack = content()
        val long = Sections.of(pack, Exam.IELTS, skillId = "ielts_reading").filter { it.exercises.size >= StudyPlanner.PAPER_SOURCE_MINIMUM }
        assertEquals(5, long.size)
        assertTrue(long.all { it.words in 700..900 && it.exercises.size in 13..14 && it.exercises.all { item -> item.format != null } })
        assertEquals(40, long.filter { it.split == ContentSplit.ASSESSMENT }.sumOf { it.exercises.size })
        val formats = long.flatMap { section -> section.exercises.mapNotNull { it.format } }.toSet()
        listOf(QuestionFormat.TRUE_FALSE_NOT_GIVEN, QuestionFormat.YES_NO_NOT_GIVEN, QuestionFormat.MATCHING_HEADINGS, QuestionFormat.MATCHING_INFORMATION,
            QuestionFormat.MATCHING_FEATURES, QuestionFormat.SUMMARY_COMPLETION, QuestionFormat.DIAGRAM_LABEL, QuestionFormat.TABLE_COMPLETION)
            .forEach { assertTrue(it.name, it in formats) }
        // Every gap and diagram label points at a question that exists in the same group.
        long.flatMap { it.exercises }.mapNotNull { it.group }.distinct().forEach { group ->
            val refs = ContentPackCodec.gapIds(group.text.orEmpty()) + group.figure?.panels.orEmpty().flatMap { panel -> panel.nodes.flatMap { ContentPackCodec.gapIds(it.label) } }
            assertTrue(group.id, refs.all { ref -> pack.exercises.any { it.id == ref && it.group == group } })
        }
        // A single-item timed check never exposes a reserved full-length passage.
        val reserved = StudyPlanner.paperSources(pack)
        assertEquals(long.filter { it.split == ContentSplit.ASSESSMENT }.map { it.sourceId }.toSet(), reserved)
        assertTrue(StudyPlanner.assessment(pack, Exam.IELTS, emptyList(), limit = 200).none { it.sourceId in reserved })
        val visuals = pack.exercises.filter { it.type == ExerciseType.WRITING }.mapNotNull { it.chart?.kind?.name ?: it.figure?.kind?.name }
        listOf("LINE", "PIE", "TABLE", "PROCESS", "MAP").forEach { assertTrue(it, it in visuals) }
    }

    @Test fun all24SatMathKeysAgreeWithIndependentSolutions() {
        val expectedNumbers = mapOf(
            "sat-algebra-d1" to ((23 + 7) / 5).toString(),
            "sat-algebra-d2" to ((11 + 4) / 3).toString(),
            "sat-algebra-p3" to (7 - ((-5 - 7) / (4 - -2)) * -2).toString(),
            "sat-algebra-a1" to (2 * (9 / 3)).toString(),
            "sat-advanced-d1" to ((9 + sqrt(81.0 - 4 * 20)) / 2).toString(),
            "sat-advanced-d2" to "8",
            "sat-advanced-p1" to (80 * 2 * 2 * 2).toString(),
            "sat-advanced-p3" to ((1 + sqrt(1.0 + 4 * 6)) / 2).toString(),
            "sat-advanced-a1" to (6 * 6 / 4).toString(),
            "sat-data-d1" to (80 * 85 / 100).toString(),
            "sat-data-d2" to ((6 + 8 + 9 + 11 + 16) / 5).toString(),
            "sat-data-p1" to ((36 / 4) * 7).toString(),
            "sat-data-p2" to "9/12",
            "sat-data-a1" to ((7 + 4) - 7).toString(),
            "sat-geometry-d1" to ((10 / 2) * (10 / 2)).toString(),
            "sat-geometry-d2" to sqrt(9.0 * 9 + 12.0 * 12).toString(),
            "sat-geometry-p1" to (3 * 3 * 5).toString(),
            "sat-geometry-p2" to (24 * 10 * 10 / (4 * 4)).toString(),
            "sat-geometry-p3" to "${sqrt(13.0 * 13 - 5.0 * 5).toInt()}/13",
            "sat-geometry-a1" to ((180 - 10 + 6) / (3 + 5)).toString(),
        )
        val expectedOptions = mapOf(
            "sat-algebra-p1" to "4h + 9",
            "sat-algebra-p2" to "x ≤ 4",
            "sat-advanced-p2" to "x + 5",
            "sat-data-p3" to "Students were randomly assigned to schedules.",
        )
        val math = content().exercises.filter { it.id in expectedNumbers || it.id in expectedOptions }
        assertEquals(24, math.size)
        assertEquals(math.map { it.id }.toSet(), expectedNumbers.keys + expectedOptions.keys)
        math.forEach { exercise ->
            val number = expectedNumbers[exercise.id]
            if (number != null) {
                exercise.acceptedAnswers.forEach { key ->
                    assertEquals(exercise.id, AnswerChecker.rational(number), AnswerChecker.rational(key))
                }
                assertEquals(exercise.id, true, AnswerChecker.check(exercise, number).correct)
            } else {
                assertEquals(exercise.id, listOf(expectedOptions.getValue(exercise.id)), exercise.acceptedAnswers)
            }
        }
    }
}
