package com.tomilov.stylishsat.domain

import org.junit.Assert.*
import org.junit.Test

class ErrorAdaptationTest {
    private val skill = Skill("reading", Exam.IELTS, LocalizedText("Reading", "Чтение"), "Reading")
    private fun item(id: String, wordLimit: Int? = null, typical: List<LocalizedText> = emptyList()) = Exercise(
        id, exam = Exam.IELTS, skillId = skill.id, split = ContentSplit.PRACTICE, familyId = id,
        type = ExerciseType.SHORT_ANSWER, difficulty = 2, prompt = "Name the object for $id.",
        acceptedAnswers = listOf("sealed container"), wordLimit = wordLimit, typicalErrors = typical, author = "unit-test fixture")
    private val plain = item("a-plain")
    private val constrained = item("z-constrained", 2)
    private val previous = item("previous", 2)
    private fun pack(vararg items: Exercise) = ContentPack(id = "fixture", version = 1, title = LocalizedText("Fixture", "Тест"),
        skills = listOf(skill), lessons = emptyList(), exercises = items.toList())
    private fun attempt(exercise: Exercise, id: String, correct: Boolean = false, error: String? = "WORD_LIMIT", time: Long = 1L) =
        Attempt(id, exercise.id, exercise.version, Exam.IELTS, skill.id, "a sealed container", correct, time,
            difficulty = 2, familyId = exercise.familyId, sourceId = exercise.sourceId, errorType = error)
    private val state = SkillState(skill.id, difficulty = 2)

    @Test fun checkerClassifiesObservedConstraintsWithoutInventingReasoningErrors() {
        assertEquals("WORD_LIMIT", AnswerChecker.check(constrained, "a sealed container").errorType)
        assertEquals(false, AnswerChecker.check(constrained, "a sealed container").correct)
        assertEquals("KEY_MISMATCH", AnswerChecker.check(plain, "box").errorType)
        assertNull(AnswerChecker.check(plain, "sealed container").errorType)
        val numeric = plain.copy(type = ExerciseType.NUMERIC, acceptedAnswers = listOf("4"))
        assertEquals("NUMERIC_FORMAT", AnswerChecker.check(numeric, "2+2").errorType)
        assertEquals(AnswerStatus.INVALID, AnswerChecker.check(numeric, "2+2").status)
        assertEquals("EMPTY_ANSWER", AnswerChecker.check(plain, " ").errorType)
        assertTrue(AnswerChecker.reviewChecks(plain, "WORD_LIMIT").isEmpty())
        assertTrue(AnswerChecker.reviewChecks(plain, "UNRECOGNIZED").isEmpty())
    }

    @Test fun wordLimitErrorSelectsMatchingFreshPracticeAndShowsConstraint() {
        val pack = pack(previous, plain, constrained)
        val failed = attempt(previous, "failed")
        val next = StudyPlanner.nextPractice(pack, Exam.IELTS, listOf(state), listOf(failed), 20, 1).single()
        assertEquals(constrained.id, next.id)
        val checks = StudyPlanner.practiceChecks(pack, next, listOf(failed))
        assertEquals(1, checks.size)
        assertTrue(checks.single().en.contains("2-word limit"))
        assertTrue(StudyPlanner.practiceChecks(pack, plain, listOf(failed)).isEmpty())
    }

    @Test fun matchedIndependentSuccessClearsNeedButHintsAndFamiliarSourcesDoNot() {
        val solved = item("solved", 2)
        val pack = pack(previous, plain, constrained, solved)
        val failed = attempt(previous, "failed")
        val success = attempt(solved, "success", true, null, 2)
        assertTrue(StudyPlanner.practiceChecks(pack, constrained, listOf(failed, success)).isEmpty())
        assertFalse(StudyPlanner.practiceChecks(pack, constrained, listOf(failed, success.copy(hintsUsed = 1))).isEmpty())
        assertFalse(StudyPlanner.practiceChecks(pack, constrained, listOf(failed, success.copy(isRepeat = true))).isEmpty())
        val repeat = attempt(previous, "repeat", true, null, 2)
        // Legacy history can omit isRepeat; source identity still prevents a false confirmation.
        assertFalse(StudyPlanner.practiceChecks(pack, constrained, listOf(failed, repeat)).isEmpty())
        val otherFormatSuccess = attempt(plain, "success", true, null, 2)
        assertFalse(StudyPlanner.practiceChecks(pack, constrained, listOf(failed, otherFormatSuccess)).isEmpty())
    }

    @Test fun keyMismatchUsesOnlySharedAuthoredChecksAndDoesNotOverrideFreshness() {
        val check = LocalizedText("Confusing the stored object with its location.", "Путаница между объектом и местом хранения.")
        val failedItem = previous.copy(typicalErrors = listOf(check))
        val targeted = constrained.copy(typicalErrors = listOf(check))
        val pack = pack(failedItem, plain, targeted)
        val failure = attempt(failedItem, "failed", error = "KEY_MISMATCH")
        assertEquals(targeted.id, StudyPlanner.nextPractice(pack, Exam.IELTS, listOf(state), listOf(failure), 20, 1).single().id)
        assertEquals(listOf(check), StudyPlanner.practiceChecks(pack, targeted, listOf(failure)))
        val exposed = attempt(targeted, "exposed", true, null, 0).copy(hintsUsed = 1)
        assertEquals(plain.id, StudyPlanner.nextPractice(pack, Exam.IELTS, listOf(state), listOf(exposed, failure), 20, 1).single().id)
        assertTrue(StudyPlanner.practiceChecks(pack, targeted, listOf(failure.copy(exerciseVersion = 99))).isEmpty())
    }

    @Test fun confidenceNeedsIndependentCorrectAndTimelyWorkButNotAnUnclearedPromotionWindow() {
        val confident = SkillState(skill.id, difficulty = 2, attemptsCount = 4, correctCount = 4,
            independentCount = 4, independentCorrectCount = 4, recentIndependent = emptyList(), totalElapsedSeconds = 40, totalExpectedSeconds = 40)
        assertTrue(StudyPlanner.confidentForIndependentPractice(confident))
        assertFalse(StudyPlanner.confidentForIndependentPractice(null))
        assertFalse(StudyPlanner.confidentForIndependentPractice(confident.copy(independentCount = 2)))
        assertFalse(StudyPlanner.confidentForIndependentPractice(confident.copy(correctCount = 2)))
        assertFalse(StudyPlanner.confidentForIndependentPractice(confident.copy(consecutiveErrors = 1)))
        assertFalse(StudyPlanner.confidentForIndependentPractice(confident.copy(totalElapsedSeconds = 80)))
    }
}
