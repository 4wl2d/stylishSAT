package com.tomilov.stylishsat.domain

import kotlinx.serialization.SerializationException
import org.junit.Assert.*
import org.junit.Test

class StudyCoreTest {
    private fun exercise(
        id: String = "item", skill: String = "skill", split: ContentSplit = ContentSplit.PRACTICE,
        type: ExerciseType = ExerciseType.NUMERIC, answers: List<String> = listOf("1/2"),
        family: String = id, source: String = family,
    ) = Exercise(
        id = id, exam = Exam.SAT, skillId = skill, split = split, familyId = family,
        sourceId = source, type = type, prompt = "Solve the exercise.", acceptedAnswers = answers,
        author = "Test fixture", criteria = if (type in listOf(ExerciseType.WRITING, ExerciseType.SPEAKING)) listOf(LocalizedText("Clarity", "Ясность")) else emptyList(),
    )

    private fun pack(skills: Int = 8): ContentPack {
        val skillList = (1..skills).map { Skill("skill-$it", Exam.SAT, LocalizedText("Skill $it", "Навык $it"), "Math") }
        val items = skillList.flatMap { skill ->
            ContentSplit.entries.flatMap { split -> (1..3).map { index ->
                exercise("${skill.id}-${split.name}-$index", skill.id, split)
            } }
        }
        return ContentPack(id = "fixture", version = 1, title = LocalizedText("Fixture", "Пример"), skills = skillList, lessons = emptyList(), exercises = items)
    }

    private fun attempt(
        id: String, correct: Boolean? = true, hints: Int = 0, repeat: Boolean = false,
        skill: String = "skill", exerciseId: String = id, day: Long = 10,
    ) = Attempt(id, exerciseId, 1, Exam.SAT, skill, "answer", correct, day * 86_400_000L,
        hintsUsed = hints, isRepeat = repeat)

    @Test fun numericAnswersUseExactFractionsAndDecimals() {
        val item = exercise()
        listOf("0.5", ".500", "2/4", "-1/-2", " 1 / 2 ", "+0.50").forEach {
            assertEquals(it, AnswerStatus.CORRECT, AnswerChecker.check(item, it).status)
        }
        listOf("1/0", "NaN", "Infinity", "0.5m", "1/2/3", "1e-1").forEach {
            assertEquals(it, AnswerStatus.INVALID, AnswerChecker.check(item, it).status)
        }
        assertEquals(AnswerStatus.INCORRECT, AnswerChecker.check(item, ".50001").status)
        assertEquals(AnswerStatus.INCORRECT, AnswerChecker.check(exercise(answers = listOf("1/3")), ".333").status)
        assertEquals(AnswerStatus.CORRECT, AnswerChecker.check(exercise(answers = listOf("1/3", ".333")), ".333").status)
    }

    @Test fun shortAnswersRespectExplicitAlternativesAndWordLimits() {
        val item = exercise(type = ExerciseType.SHORT_ANSWER, answers = listOf("city centre", "city center")).copy(exam = Exam.IELTS, wordLimit = 2)
        assertEquals(true, AnswerChecker.check(item, " CITY   CENTER ").correct)
        assertEquals(false, AnswerChecker.check(item, "the city centre").correct)
        assertEquals(false, AnswerChecker.check(item, "city centers").correct)
        assertEquals(1, AnswerChecker.wordCount("well-known"))
        assertEquals(AnswerStatus.INVALID, AnswerChecker.check(item, "  ").status)
    }

    @Test fun writingAndSpeakingNeverProduceAClosedAnswerScore() {
        listOf(ExerciseType.WRITING, ExerciseType.SPEAKING).forEach { type ->
            val result = AnswerChecker.check(exercise(type = type), "An original response")
            assertEquals(AnswerStatus.NEEDS_REVIEW, result.status)
            assertNull(result.correct)
        }
        val state = SkillState("skill", difficulty = 2, nextReviewEpochDay = 22)
        assertEquals(state, StudyPlanner.updateSkill(state, attempt("open", correct = null), 10))
    }

    @Test fun satDiagnosticHasTwoQuestionsPerDomainAndNeverReusesExposure() {
        val pack = pack()
        val selected = StudyPlanner.diagnostic(pack, Exam.SAT)
        assertEquals(16, selected.size)
        assertTrue(selected.groupBy { it.skillId }.values.all { it.size == 2 })
        assertTrue(selected.all { it.split == ContentSplit.DIAGNOSTIC })
        val seen = selected.map { attempt(it.id, skill = it.skillId) }
        val next = StudyPlanner.diagnostic(pack, Exam.SAT, seen)
        assertTrue(next.none { item -> selected.any { it.id == item.id } })
    }

    @Test fun ieltsDiagnosticIncludesAllFourSkills() {
        val base = pack(4)
        val ielts = base.copy(skills = base.skills.map { it.copy(exam = Exam.IELTS) }, exercises = base.exercises.map { it.copy(exam = Exam.IELTS) })
        val selected = StudyPlanner.diagnostic(ielts, Exam.IELTS)
        assertEquals(4, selected.size)
        assertEquals(4, selected.map { it.skillId }.distinct().size)
    }

    @Test fun oldTwoSkillIeltsDiagnosticDoesNotCompleteTheUpdatedFourSkillPack() {
        val skillIds = listOf("ielts_reading", "ielts_listening", "ielts_writing", "ielts_speaking")
        val skills = skillIds.map { Skill(it, Exam.IELTS, LocalizedText(it, it), "IELTS") }
        val items = skillIds.map { skill ->
            exercise("$skill-probe", skill, ContentSplit.DIAGNOSTIC).copy(exam = Exam.IELTS)
        }
        val updated = ContentPack(id = "ielts-pilot", version = 2, title = LocalizedText("Pilot", "Пилот"), skills = skills, lessons = emptyList(), exercises = items)
        val oldPack = updated.copy(version = 1, skills = skills.take(2), exercises = items.take(2))
        val oldAttempts = items.take(2).map { item ->
            attempt("old-${item.id}", skill = item.skillId, exerciseId = item.id).copy(exam = Exam.IELTS, split = ContentSplit.DIAGNOSTIC)
        }
        assertTrue(StudyPlanner.diagnosticComplete(oldPack, Exam.IELTS, oldAttempts))
        assertFalse(StudyPlanner.diagnosticComplete(updated, Exam.IELTS, oldAttempts))
        assertEquals(skillIds.takeLast(2), StudyPlanner.diagnostic(updated, Exam.IELTS, oldAttempts).map { it.skillId })

        // Open probes have no automatic score, but still count toward diagnostic coverage.
        val writing = attempt("writing", correct = null, skill = skillIds[2], exerciseId = items[2].id).copy(exam = Exam.IELTS, split = ContentSplit.DIAGNOSTIC)
        val speaking = attempt("speaking", correct = null, skill = skillIds[3], exerciseId = items[3].id).copy(exam = Exam.IELTS, split = ContentSplit.DIAGNOSTIC)
        assertFalse(StudyPlanner.diagnosticComplete(updated, Exam.IELTS, oldAttempts + writing))
        assertTrue(StudyPlanner.diagnosticComplete(updated, Exam.IELTS, oldAttempts + writing + speaking))
    }

    @Test fun diagnosticCoverageCountsDistinctExercisesAndOnlyTheRequestedExamAndSplit() {
        val content = pack(1)
        val probes = content.exercises.filter { it.split == ContentSplit.DIAGNOSTIC }
        val first = attempt("first", skill = probes[0].skillId, exerciseId = probes[0].id).copy(split = ContentSplit.DIAGNOSTIC)
        val repeated = first.copy(id = "distinct-attempt-same-exercise", exerciseVersion = 2, isRepeat = true)
        val practice = first.copy(id = "practice", exerciseId = probes[1].id, split = ContentSplit.PRACTICE)
        val otherExam = first.copy(id = "other-exam", exerciseId = probes[1].id, exam = Exam.IELTS)
        assertFalse(StudyPlanner.diagnosticComplete(content, Exam.SAT, listOf(first, repeated, practice, otherExam)))
        val second = first.copy(id = "second", exerciseId = probes[1].id)
        assertTrue(StudyPlanner.diagnosticComplete(content, Exam.SAT, listOf(first, repeated, second)))
    }

    @Test fun anExamWithoutSkillsCannotBeMarkedDiagnosed() {
        assertFalse(StudyPlanner.diagnosticComplete(pack(), Exam.IELTS, emptyList()))
        assertFalse(StudyPlanner.diagnosticComplete(pack().copy(skills = emptyList()), Exam.SAT, emptyList()))
    }

    @Test fun masteryNeedsThreeOfFourIndependentAnswersAtEachLevel() {
        var state = SkillState("skill")
        (1..3).forEach { state = StudyPlanner.updateSkill(state, attempt("correct-$it"), 10) }
        assertEquals(1, state.difficulty)
        state = StudyPlanner.updateSkill(state, attempt("fourth", correct = false), 10)
        assertEquals(2, state.difficulty)
        state = StudyPlanner.updateSkill(state, attempt("fifth").copy(difficulty = 2), 10)
        assertEquals(2, state.difficulty)
        assertEquals(listOf(true), state.recentIndependent)
    }

    @Test fun hintedAndRepeatedCorrectAnswersCannotRaiseMastery() {
        var state = SkillState("skill")
        (1..12).forEach { state = StudyPlanner.updateSkill(state, attempt("hint-$it", hints = 1), 10) }
        (1..12).forEach { state = StudyPlanner.updateSkill(state, attempt("repeat-$it", repeat = true), 10) }
        assertEquals(1, state.difficulty)
        assertEquals(0, state.independentCount)
        assertEquals(0, state.reviewStep)
        assertEquals(11L, state.nextReviewEpochDay)
    }

    @Test fun easyAnswersCannotEstablishMasteryOfAHigherLevel() {
        var state = SkillState("skill", difficulty = 2)
        (1..8).forEach { state = StudyPlanner.updateSkill(state, attempt("easy-$it").copy(difficulty = 1), 10) }
        assertEquals(2, state.difficulty)
        assertTrue(state.recentIndependent.none { it })
    }

    @Test fun threeIndependentAnswersMustOccurWithinTheLastFourResponses() {
        var state = SkillState("skill")
        state = StudyPlanner.updateSkill(state, attempt("first"), 10)
        state = StudyPlanner.updateSkill(state, attempt("second"), 10)
        state = StudyPlanner.updateSkill(state, attempt("hint1", hints = 1), 10)
        state = StudyPlanner.updateSkill(state, attempt("hint2", hints = 1), 10)
        state = StudyPlanner.updateSkill(state, attempt("third"), 10)
        state = StudyPlanner.updateSkill(state, attempt("fourth"), 10)
        assertEquals(1, state.difficulty)
        state = StudyPlanner.updateSkill(state, attempt("fifth"), 10)
        assertEquals(2, state.difficulty)
    }

    @Test fun twoErrorsLowerDifficultyAndShortenReviewInterval() {
        val original = SkillState("skill", difficulty = 3, reviewStep = 3)
        val first = StudyPlanner.updateSkill(original, attempt("wrong1", false), 10)
        val second = StudyPlanner.updateSkill(first, attempt("wrong2", false), 11)
        assertEquals(2, second.difficulty)
        assertEquals(12L, second.nextReviewEpochDay)
        assertTrue(second.reviewStep < original.reviewStep)
    }

    @Test fun independentSuccessUsesOneThreeSevenFourteenDayIntervals() {
        var state = SkillState("skill")
        var day = 100L
        listOf(1L, 3L, 7L, 14L, 14L).forEachIndexed { index, interval ->
            state = StudyPlanner.updateSkill(state, attempt("$index"), day)
            assertEquals(day + interval, state.nextReviewEpochDay)
            day = state.nextReviewEpochDay!!
        }
    }

    @Test fun restoredHistoryDeduplicatesAndRecognizesFamilyRepeats() {
        val fixture = pack(1)
        val first = fixture.exercises[0]
        val sibling = fixture.exercises[1].copy(familyId = first.familyId)
        val content = fixture.copy(exercises = listOf(first, sibling))
        val a = attempt("a", skill = first.skillId, exerciseId = first.id)
        val b = attempt("b", skill = first.skillId, exerciseId = sibling.id)
        val state = StudyPlanner.statesFromAttempts(content, listOf(b, a, a)).single()
        assertEquals(2, state.attemptsCount)
        assertEquals(1, state.independentCount)
    }

    @Test fun restoredReviewUsesPersistedLocalDayAndHistoricalExpectedTime() {
        val content = pack(1)
        val exercise = content.exercises.first()
        val recorded = attempt("recorded", skill = exercise.skillId, exerciseId = exercise.id, day = 10).copy(
            localDateEpochDay = 11,
            expectedSeconds = 120,
            recordingPath = "/local/recordings/original.m4a",
        )
        val state = StudyPlanner.statesFromAttempts(content, listOf(recorded)).single()
        assertEquals(12L, state.nextReviewEpochDay)
        assertEquals(120L, state.totalExpectedSeconds)
    }

    @Test fun oldAttemptsWithoutLocalDayUseUtcAndContentDurationAsFallback() {
        val content = pack(1)
        val exercise = content.exercises.first()
        val recorded = attempt("old", skill = exercise.skillId, exerciseId = exercise.id, day = 10)
        val state = StudyPlanner.statesFromAttempts(content, listOf(recorded)).single()
        assertEquals(11L, state.nextReviewEpochDay)
        assertEquals(exercise.expectedSeconds.toLong(), state.totalExpectedSeconds)
    }

    @Test fun practicePrioritizesDueThenWeakThenUnseenAndKeepsAssessmentHidden() {
        val content = pack(3)
        val states = listOf(
            SkillState("skill-1", difficulty = 2, attemptsCount = 4, correctCount = 4, independentCount = 4, nextReviewEpochDay = 9),
            SkillState("skill-2", attemptsCount = 4, correctCount = 1, independentCount = 4, nextReviewEpochDay = 20),
        )
        val result = StudyPlanner.nextPractice(content, Exam.SAT, states, emptyList(), 10, 3)
        assertEquals(listOf("skill-1", "skill-2", "skill-3"), result.map { it.skillId })
        assertTrue(result.all { it.split == ContentSplit.PRACTICE })
    }

    @Test fun twoErrorsKeepPracticeAtTheCurrentLevelDespiteFreshHarderItems() {
        val fixture = pack(1)
        val practice = fixture.exercises.filter { it.split == ContentSplit.PRACTICE }
        val easy = practice.first().copy(difficulty = 1)
        val hard = practice.last().copy(difficulty = 3)
        val content = fixture.copy(exercises = listOf(easy, hard))
        val firstError = attempt("error-1", correct = false, skill = easy.skillId, exerciseId = easy.id)
        val secondError = attempt("error-2", correct = false, skill = easy.skillId, exerciseId = easy.id, repeat = true)
        val lowered = StudyPlanner.updateSkill(
            StudyPlanner.updateSkill(SkillState(easy.skillId, difficulty = 2), firstError, 10), secondError, 10,
        )
        assertEquals(1, lowered.difficulty)
        val history = listOf(firstError, secondError)
        assertEquals(easy.id, StudyPlanner.nextPractice(content, Exam.SAT, listOf(lowered), history, 10, limit = 1).single().id)
        val course = StudyPlanner.course(content, Exam.SAT, listOf(lowered), history, 10)
        assertTrue(course.days.dropLast(1).all { it.exerciseIds == listOf(easy.id) })
        assertFalse(StudyPlanner.updateSkill(lowered, attempt("repeat-correct", skill = easy.skillId, exerciseId = easy.id, repeat = true), 11).mastered)
    }

    @Test fun aLargerPracticeBatchDoesNotAppendHarderClosedQuestionsToFillItsLimit() {
        val fixture = pack(1)
        val practice = fixture.exercises.filter { it.split == ContentSplit.PRACTICE }
        val easy = practice[0].copy(difficulty = 1)
        val harder = practice[1].copy(difficulty = 2)
        val hardest = practice[2].copy(difficulty = 3)
        val content = fixture.copy(exercises = listOf(easy, harder, hardest))
        val seen = listOf(attempt("seen-easy", correct = false, skill = easy.skillId, exerciseId = easy.id))
        val batch = StudyPlanner.nextPractice(content, Exam.SAT, listOf(SkillState(easy.skillId)), seen, 10, limit = 8)
        assertEquals(listOf(easy.id), batch.map { it.id })
        assertTrue(batch.all { it.difficulty == 1 })
    }

    @Test fun openResponsePracticeRemainsAvailableAcrossDifficultyLevels() {
        val fixture = pack(1)
        val practice = fixture.exercises.filter { it.split == ContentSplit.PRACTICE }
        val writing = practice[0].copy(type = ExerciseType.WRITING, difficulty = 1)
        val advancedWriting = practice[1].copy(type = ExerciseType.WRITING, difficulty = 3)
        val speaking = practice[2].copy(type = ExerciseType.SPEAKING, difficulty = 2)
        val content = fixture.copy(exercises = listOf(writing, advancedWriting, speaking))
        val batch = StudyPlanner.nextPractice(content, Exam.SAT, listOf(SkillState(writing.skillId)), emptyList(), 10, limit = 8)
        assertEquals(setOf(writing.id, advancedWriting.id, speaking.id), batch.map { it.id }.toSet())
    }

    @Test fun heldoutSelectionExcludesSeenSourceEvenUnderANewExerciseId() {
        val content = pack(1)
        val seen = content.exercises.first { it.split == ContentSplit.ASSESSMENT }
        val sibling = content.exercises.last().copy(sourceId = seen.sourceId)
        val custom = content.copy(exercises = listOf(seen, sibling))
        assertTrue(StudyPlanner.assessment(custom, Exam.SAT, listOf(attempt("attempt", exerciseId = seen.id))).isEmpty())
    }

    @Test fun courseHas28DatedDaysAndFinalCheckIsHeldOut() {
        val content = pack()
        val plan = StudyPlanner.course(content, Exam.SAT, emptyList(), emptyList(), 200, 30)
        assertEquals(28, plan.days.size)
        assertEquals((200L..227L).toList(), plan.days.map { it.epochDay })
        assertEquals(840, plan.totalMinutes)
        assertTrue(plan.days.last().exerciseIds.all { id -> content.exercises.first { it.id == id }.split == ContentSplit.ASSESSMENT })
        val activities = plan.days.flatMap { it.activities }
        val firstCheck = activities.indexOfFirst { it.kind == ActivityKind.ASSESSMENT }
        assertTrue(firstCheck >= 0)
        assertTrue(activities.drop(firstCheck).all { it.kind == ActivityKind.ASSESSMENT || it.kind == ActivityKind.REVIEW })
    }

    @Test fun refreshingCourseDoesNotConsumeUnseenPracticeInCompletedDays() {
        val content = pack(1)
        val initial = StudyPlanner.course(content, Exam.SAT, emptyList(), emptyList(), 200, 30)
        val firstDay = initial.days.first()
        val firstExercise = content.exercises.first { it.id == firstDay.exerciseIds.single() }
        val history = listOf(attempt("day1", skill = firstExercise.skillId, exerciseId = firstExercise.id, day = 200))
        val refreshed = StudyPlanner.course(content, Exam.SAT, StudyPlanner.statesFromAttempts(content, history), history, 200, 15, listOf(firstDay))
        assertEquals(firstDay, refreshed.days.first())
        assertEquals("skill-1-PRACTICE-2", refreshed.days[1].exerciseIds.single())
        assertEquals(refreshed.days.sumOf { it.minutes }, refreshed.totalMinutes)

        val secondDay = refreshed.days[1]
        val secondExercise = content.exercises.first { it.id == secondDay.exerciseIds.single() }
        val updatedHistory = history + attempt("day2", skill = secondExercise.skillId, exerciseId = secondExercise.id, day = 201)
        val secondRefresh = StudyPlanner.course(content, Exam.SAT, StudyPlanner.statesFromAttempts(content, updatedHistory), updatedHistory, 200, 15, listOf(firstDay, secondDay))
        assertEquals(listOf(firstDay, secondDay), secondRefresh.days.take(2))
        assertEquals("skill-1-PRACTICE-3", secondRefresh.days[2].exerciseIds.single())
    }

    @Test fun intensivePreservesExactBudgetsAndCompletedWorkWhenShortened() {
        val content = pack()
        listOf(2, 4, 6).forEach { hours ->
            val plan = StudyPlanner.intensive(content, Exam.SAT, emptyList(), 200, hours)
            assertEquals(hours * 60, plan.blocks.sumOf { it.minutes })
            assertTrue(plan.prioritySkillIds.size <= 3)
        }
        val plan = StudyPlanner.intensive(content, Exam.SAT, emptyList(), 200, 4)
        assertEquals(listOf(35, 90, 20, 50, 35, 10), plan.blocks.map { it.minutes })
        val running = plan.copy(blocks = plan.blocks.mapIndexed { index, block -> block.copy(completed = index == 0) })
        val shorter = StudyPlanner.recalculateIntensive(running, 47)
        assertEquals(running.blocks.first(), shorter.blocks.first())
        assertEquals(47, shorter.blocks.filterNot { it.completed }.sumOf { it.minutes })
        assertEquals(82, shorter.totalMinutes)
        assertEquals(0, StudyPlanner.recalculateIntensive(running, 0).blocks.filterNot { it.completed }.sumOf { it.minutes })
    }

    @Test fun strictPackDecoderRejectsUnknownFieldsAndVersions() {
        val raw = ContentPackCodec.encode(pack())
        assertEquals(pack(), ContentPackCodec.decode(raw))
        try {
            ContentPackCodec.decode(raw.replaceFirst("{", "{\"unexpected\":true,"))
            fail("Unknown field was accepted")
        } catch (_: SerializationException) { }
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.decode(raw.replace("\"schemaVersion\": 1", "\"schemaVersion\": 999")) }
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.decode(raw.replace("\"schemaVersion\": 1,", "")) }
    }

    @Test fun contentValidatorRejectsFamilyAndSourceLeakage() {
        val content = pack(1)
        val first = content.exercises.first()
        val heldout = content.exercises.last()
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(content.copy(exercises = listOf(first, heldout.copy(familyId = first.familyId))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(content.copy(exercises = listOf(first, heldout.copy(sourceId = first.sourceId))))
        }
    }

    @Test fun normalizedPassageAliasesCannotCrossContentSplits() {
        val content = pack(1)
        val first = content.exercises.first().copy(passage = "A field office logged 12 visits.")
        val heldout = content.exercises.last().copy(passage = "  Ａ\u00a0field\u2003office\nlogged   12 visits.  ")
        assertNotEquals(first.sourceId, heldout.sourceId)
        assertNotEquals(first.familyId, heldout.familyId)
        assertEquals(ContentPackCodec.sourceSignatures(first), ContentPackCodec.sourceSignatures(heldout))
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(content.copy(exercises = listOf(first, heldout)))
        }
    }

    @Test fun audioAliasesCannotCrossContentSplits() {
        val content = pack(1)
        val first = content.exercises.first().copy(audioAssetPath = "audio/shared.wav", transcript = "Original recording text.")
        val heldout = content.exercises.last().copy(audioAssetPath = "audio/./shared.wav", transcript = first.transcript)
        assertNotEquals(first.sourceId, heldout.sourceId)
        assertEquals(ContentPackCodec.sourceSignatures(first), ContentPackCodec.sourceSignatures(heldout))
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(content.copy(exercises = listOf(first, heldout)))
        }
    }

    @Test fun severalQuestionsMayReuseTheSameActualSourceWithinOneSplit() {
        val content = pack(1)
        val first = content.exercises.first().copy(passage = "One original source.", audioAssetPath = "audio/shared.wav", transcript = "One original source.")
        val sibling = content.exercises[1].copy(passage = "One   original source.", audioAssetPath = first.audioAssetPath, transcript = first.transcript)
        assertNotEquals(first.sourceId, sibling.sourceId)
        assertEquals(first.split, sibling.split)
        assertEquals(2, ContentPackCodec.sourceSignatures(first).size)
        ContentPackCodec.validate(content.copy(exercises = listOf(first, sibling)))
    }
}
