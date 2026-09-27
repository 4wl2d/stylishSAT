package com.tomilov.stylishsat.domain

import org.junit.Assert.*
import org.junit.Test

class MistakeNotebookTest {
    private fun item(id: String, family: String, skill: String = "sat_algebra", split: ContentSplit = ContentSplit.PRACTICE, difficulty: Int = 2,
        format: QuestionFormat? = null, slip: String? = null) = Exercise(
        id = id, exam = Exam.SAT, skillId = skill, difficulty = difficulty, split = split, familyId = family, sourceId = "$family-$id",
        type = ExerciseType.NUMERIC, prompt = "Solve.", acceptedAnswers = listOf("1"), author = "Test fixture", format = format,
        typicalErrors = listOfNotNull(slip?.let { LocalizedText(it, it) }),
    )

    private fun pack(vararg items: Exercise) = ContentPack(id = "f", version = 1, title = LocalizedText("F", "F"),
        skills = listOf(Skill("sat_algebra", Exam.SAT, LocalizedText("Algebra", "Алгебра"), "Math"), Skill("sat_data", Exam.SAT, LocalizedText("Data", "Данные"), "Math")),
        lessons = emptyList(), exercises = items.toList())

    private fun wrong(exercise: Exercise, id: String = "w-${exercise.id}", at: Long = 1_000, answer: String = "7") =
        Attempt(id, exercise.id, exercise.version, Exam.SAT, exercise.skillId, answer, false, at, familyId = exercise.familyId, sourceId = exercise.sourceId, errorType = "KEY_MISMATCH")

    @Test fun notebookListsWrongAndSkippedAnswersNewestFirstOncePerVersion() {
        val a = item("a", "fa"); val b = item("b", "fb")
        val attempts = listOf(wrong(a, "1", 100), wrong(a, "2", 300), wrong(b, "3", 200).copy(correct = null, errorType = "SKIPPED"),
            wrong(b, "4", 400).copy(correct = true, errorType = null), wrong(a, "5", 500).copy(exam = Exam.IELTS))
        assertEquals(listOf("2", "3"), Notebook.mistakes(attempts, Exam.SAT).map { it.id })
    }

    @Test fun aFreshSiblingFromTheSameFamilyComesFirst() {
        val missed = item("a1", "linear", difficulty = 2)
        val sibling = item("a2", "linear", difficulty = 3)
        val closer = item("a3", "linear", difficulty = 2)
        val other = item("b1", "quadratic", difficulty = 2)
        val pack = pack(missed, sibling, closer, other)
        val attempts = listOf(wrong(missed))
        assertEquals(Notebook.FreshItem(closer, fromFamily = true), Notebook.freshItem(pack, missed, attempts))
        // Already queued or answered siblings are skipped.
        assertEquals(sibling, Notebook.freshItem(pack, missed, attempts, alreadyQueued = setOf("a3"))?.exercise)
        assertEquals(sibling, Notebook.freshItem(pack, missed, attempts + wrong(closer, at = 2_000))?.exercise)
    }

    @Test fun withoutASiblingItFallsBackToAnUnseenPracticeItemWithTheSameSlip() {
        val missed = item("a1", "linear", slip = "Sign error")
        val unrelated = item("b1", "quadratic")
        val sameSlip = item("c1", "rates", slip = "Sign error")
        val otherSkill = item("d1", "tables", skill = "sat_data", slip = "Sign error")
        val assessment = item("e1", "checks", split = ContentSplit.ASSESSMENT, slip = "Sign error")
        val pack = pack(missed, unrelated, sameSlip, otherSkill, assessment)
        val fresh = Notebook.freshItem(pack, missed, listOf(wrong(missed)))
        assertEquals(Notebook.FreshItem(sameSlip, fromFamily = false), fresh)
        assertNull(Notebook.freshItem(pack(missed), missed, listOf(wrong(missed))))
    }

    @Test fun followUpsLeaveTheQueueOnceAnsweredAfterQueueing() {
        val entry = NotebookEntry("w", Exam.SAT, "a1", 1, queuedExerciseId = "a2", queuedExerciseVersion = 1, queuedAt = 1_000)
        val earlier = Attempt("x", "a2", 1, Exam.SAT, "sat_algebra", "1", true, 500)
        assertEquals(listOf(entry), Notebook.pending(listOf(entry), listOf(earlier), Exam.SAT))
        assertEquals(emptyList<NotebookEntry>(), Notebook.pending(listOf(entry), listOf(earlier.copy(id = "y", timestampEpochMillis = 1_500)), Exam.SAT))
        assertEquals(emptyList<NotebookEntry>(), Notebook.pending(listOf(entry), emptyList(), Exam.IELTS))
    }
}
