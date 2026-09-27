package com.tomilov.stylishsat.domain

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class WritingRevisionTest {
    private fun bundled(): ContentPack = ContentPackCodec.decode(listOf(
        File("src/main/assets/content/seed-v1.json"), File("app/src/main/assets/content/seed-v1.json"),
    ).first { it.isFile }.readText())

    private fun task(version: Int = 1, prompt: String = "Discuss both views and give your own opinion.", checks: List<TaskCheck> = emptyList()) = Exercise(
        id = "essay", version = version, exam = Exam.IELTS, skillId = "ielts_writing", split = ContentSplit.PRACTICE, familyId = "essay",
        type = ExerciseType.WRITING, prompt = prompt, author = "Test fixture", minWords = 250,
        criteria = listOf(LocalizedText("Task response", "Задание")), taskChecklist = checks,
    )

    private val checks = listOf(
        TaskCheck("view-a", CheckKind.VIEW, LocalizedText("Do you explain view A?", "Объясняете ли вы мнение A?")),
        TaskCheck("view-b", CheckKind.VIEW, LocalizedText("Do you explain view B?", "Объясняете ли вы мнение B?")),
        TaskCheck("position", CheckKind.POSITION, LocalizedText("Do you state your position?", "Указываете ли вы свою позицию?")),
    )

    @Test fun wordDiffKeepsUnchangedWordsAndParagraphBreaks() {
        val parts = TextDiff.words("Cities should fund buses.\n\nThey are cheap.", "Cities should fund safe buses.\n\nThey are cheap and clean.")
        assertEquals(listOf(
            TextDiff.Part(TextDiff.Kind.SAME, "Cities should fund"), TextDiff.Part(TextDiff.Kind.ADDED, "safe"),
            TextDiff.Part(TextDiff.Kind.SAME, "buses.\n\nThey are"), TextDiff.Part(TextDiff.Kind.REMOVED, "cheap."),
            TextDiff.Part(TextDiff.Kind.ADDED, "cheap and clean."),
        ), parts)
        assertEquals(4 to 1, TextDiff.changedWords(parts))
        assertEquals(listOf(TextDiff.Part(TextDiff.Kind.SAME, "same text")), TextDiff.words("same text", "same text"))
        assertEquals(listOf(TextDiff.Part(TextDiff.Kind.ADDED, "new")), TextDiff.words("", "new"))
    }

    @Test fun oversizedInputsFallBackToWholeBlocks() {
        val long = List(50) { "w$it" }.joinToString(" ")
        val parts = TextDiff.words(long, long + " end", limit = 10)
        assertEquals(listOf(TextDiff.Kind.REMOVED, TextDiff.Kind.ADDED), parts.map { it.kind })
    }

    @Test fun anOlderAnswerBorrowsTheChecklistOnlyForTheSameTask() {
        val answered = task(version = 1)
        val newer = task(version = 2, checks = checks)
        assertEquals(checks, Revisions.checklist(answered, listOf(newer)))
        assertEquals(emptyList<TaskCheck>(), Revisions.checklist(answered, listOf(newer.copy(prompt = "A different question."))))
        assertEquals(checks.take(1), Revisions.checklist(answered.copy(taskChecklist = checks.take(1)), listOf(newer)))
    }

    @Test fun focusListsOnlyMissingOrPartialChecksAndCountsAreNotAScore() {
        val marks = mapOf("view-a" to CheckMark.YES, "view-b" to CheckMark.PARTLY)
        assertEquals(listOf("view-b"), Revisions.focus(checks, marks).map { it.id })
        assertEquals(Revisions.Counts(yes = 1, partly = 1, no = 0, open = 1), Revisions.counts(checks, marks))
    }

    @Test fun theSubmittedAnswerIsVersionOneOfItsOwnThread() {
        val attempt = Attempt("a1", "essay", 2, Exam.IELTS, "ielts_writing", "My essay.", null, 5_000, elapsedSeconds = 900, workId = "work-1")
        val original = Revisions.original(attempt)
        assertEquals("work-1#1", original.id)
        assertEquals(1, original.number)
        assertTrue(original.saved)
        val later = original.copy(number = 2, text = "My better essay.", saved = false)
        val thread = listOf(later, original)
        assertEquals(listOf(1, 2), Revisions.thread(thread, "work-1").map { it.number })
        assertEquals(later, Revisions.draft(thread, "work-1"))
        assertEquals(listOf(original), Revisions.saved(thread, "work-1"))
        assertEquals("attempt:a2#1", Revisions.original(attempt.copy(id = "a2", workId = null)).id)
    }

    @Test fun checklistsAreOnlyForWritingTasksInSchemaThree() {
        val closed = Exercise(id = "q", exam = Exam.IELTS, skillId = "ielts_writing", split = ContentSplit.PRACTICE, familyId = "q",
            type = ExerciseType.SHORT_ANSWER, prompt = "Write one word.", acceptedAnswers = listOf("x"), author = "Test", taskChecklist = checks)
        val pack = ContentPack(schemaVersion = 3, id = "f", version = 1, title = LocalizedText("F", "F"),
            skills = listOf(Skill("ielts_writing", Exam.IELTS, LocalizedText("W", "W"), "Writing")), lessons = emptyList(), exercises = listOf(task(checks = checks)))
        ContentPackCodec.validate(pack)
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.validate(pack.copy(schemaVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.validate(pack.copy(exercises = listOf(closed))) }
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.validate(pack.copy(exercises = listOf(task(checks = checks + checks.first())))) }
    }

    @Test fun everyBundledWritingTaskChecksTheTaskItselfNotABand() {
        val writing = bundled().exercises.filter { it.type == ExerciseType.WRITING }
        assertEquals(31, writing.size)
        writing.forEach { task ->
            val kinds = task.taskChecklist.map { it.kind }.toSet()
            assertTrue(task.id, task.taskChecklist.size in 4..6)
            if (task.chart != null || task.figure != null) assertTrue(task.id, kinds.containsAll(listOf(CheckKind.OVERVIEW, CheckKind.COMPARISON, CheckKind.DATA, CheckKind.ACCURACY)))
            else assertTrue(task.id, CheckKind.SUPPORT in kinds && (CheckKind.POSITION in kinds || CheckKind.TASK_PART in kinds))
            if (task.prompt.contains("Discuss both", ignoreCase = true)) {
                assertTrue(task.id, task.taskChecklist.count { it.kind == CheckKind.VIEW } >= 2 && CheckKind.POSITION in kinds)
            }
            assertTrue(task.id, task.taskChecklist.none { Regex("\\b(band|score)\\b", RegexOption.IGNORE_CASE).containsMatchIn(it.text.en) })
        }
    }
}
