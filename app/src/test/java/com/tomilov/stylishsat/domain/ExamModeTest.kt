package com.tomilov.stylishsat.domain

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class ExamModeTest {
    private val pack: ContentPack by lazy {
        ContentPackCodec.decode(listOf(File("src/main/assets/content/seed-v1.json"), File("app/src/main/assets/content/seed-v1.json")).first { it.isFile }.readText())
    }

    private fun answerAll(part: PaperPart, correct: (Exercise) -> Boolean): Map<String, String> = part.exercises.associate { exercise ->
        exercise.versionKey to if (correct(exercise)) exercise.acceptedAnswers.first() else (exercise.options.firstOrNull { it !in exercise.acceptedAnswers } ?: "999999")
    }

    private fun sit(part: PaperPart, answers: Map<String, String>, history: List<Attempt> = emptyList(), runId: String = "exam"): List<Attempt> {
        val run = PaperRun(runId, Exam.SAT, PaperKind.SAT, true, listOf(part), answers = answers, elapsedSeconds = mapOf(part.id to 1_800), startedAt = 0)
        var counter = 0
        return PaperScoring.attempts(run, part, PaperScoring.score(part, answers), pack, history, 1_000, 20) { "$runId-${counter++}" }
    }

    @Test fun satModulesFollowTheDigitalSatLengthsClocksAndDomainOrder() {
        val rw = ExamPapers.satModule(pack, emptyList(), "RW1", null)
        assertEquals(27, rw.exercises.size); assertEquals(32 * 60, rw.timeLimitSeconds); assertEquals(0, rw.shortfall)
        assertEquals(listOf("sat_craft" to 7, "sat_information" to 7, "sat_conventions" to 7, "sat_expression" to 6),
            rw.exercises.map { it.skillId }.fold(mutableListOf<Pair<String, Int>>()) { acc, skill ->
                if (acc.lastOrNull()?.first == skill) acc[acc.size - 1] = skill to acc.last().second + 1 else acc += skill to 1; acc })
        val math = ExamPapers.satModule(pack, emptyList(), "MATH1", null)
        assertEquals(22, math.exercises.size); assertEquals(35 * 60, math.timeLimitSeconds)
        assertEquals(math.exercises.map { it.difficulty }.sorted(), math.exercises.map { it.difficulty })
        listOf(rw, math).forEach { module ->
            assertTrue(module.oneAtATime)
            assertTrue(module.exercises.none { it.split == ContentSplit.DIAGNOSTIC || StudyPlanner.openResponse(it) })
            assertEquals(module.exercises.size, module.exercises.map { it.familyId }.distinct().size)
        }
        assertEquals(ExamPapers.SAT_BREAK_SECONDS, ExamPapers.satModule(pack, emptyList(), "RW2", ModuleRoute.LOWER).breakAfterSeconds)
    }

    @Test fun theSecondModuleIsHarderOrEasierFromTheFirstModulesRawResultAndNeverRepeatsIt() {
        val first = ExamPapers.satModule(pack, emptyList(), "MATH1", null)
        val strong = PaperScoring.score(first, answerAll(first) { true })
        val weak = PaperScoring.score(first, answerAll(first) { it.difficulty == 3 })
        assertEquals(ModuleRoute.HIGHER, ExamPapers.route(strong))
        assertEquals(ModuleRoute.LOWER, ExamPapers.route(weak))
        // Exactly at the practice threshold routes higher; one fewer routes lower.
        val needed = kotlin.math.ceil(first.exercises.size * ExamPapers.HIGHER_ROUTE_SHARE).toInt()
        assertEquals(ModuleRoute.HIGHER, ExamPapers.route(PaperScoring.score(first, answerAll(first) { first.exercises.indexOf(it) < needed })))
        assertEquals(ModuleRoute.LOWER, ExamPapers.route(PaperScoring.score(first, answerAll(first) { first.exercises.indexOf(it) < needed - 1 })))
        val history = sit(first, answerAll(first) { true })
        val higher = ExamPapers.satModule(pack, history, "MATH2", ModuleRoute.HIGHER, first.exercises)
        val lower = ExamPapers.satModule(pack, history, "MATH2", ModuleRoute.LOWER, first.exercises)
        assertTrue(higher.exercises.map { it.difficulty }.average() > lower.exercises.map { it.difficulty }.average())
        assertTrue(higher.exercises.none { it.difficulty == 1 }); assertTrue(lower.exercises.none { it.difficulty == 3 })
        val firstIds = first.exercises.map { it.id }.toSet()
        assertTrue(higher.exercises.none { it.id in firstIds } && lower.exercises.none { it.id in firstIds })
        assertEquals(ModuleRoute.HIGHER, higher.route)
    }

    @Test fun examAnswersAreIndependentEvenOvertimeAndBlanksAreNotEvidence() {
        val module = ExamPapers.satModule(pack, emptyList(), "RW1", null)
        val answers = answerAll(module) { true } - module.exercises.first().versionKey
        val run = PaperRun("x", Exam.SAT, PaperKind.SAT, true, listOf(module), answers = answers, elapsedSeconds = mapOf("RW1" to 2_400),
            overtimeParts = listOf("RW1"), startedAt = 0)
        var counter = 0
        val attempts = PaperScoring.attempts(run, module, PaperScoring.score(module, answers), pack, emptyList(), 1_000, 20) { "a${counter++}" }
        assertEquals(26, attempts.count { it.independent && it.correct == true })
        assertTrue(attempts.all { it.continuedWithoutTimeLimit && !it.isRepeat })
        assertEquals("SKIPPED", attempts.first().errorType); assertNull(attempts.first().correct)
        assertEquals(2_400, attempts.sumOf { it.elapsedSeconds })
    }

    @Test fun modulesShrinkHonestlyWhenFreshItemsRunOut() {
        val seen = pack.exercises.filter { it.skillId == "sat_geometry" }.mapIndexed { index, item ->
            Attempt("g$index", item.id, item.version, Exam.SAT, item.skillId, "1", true, 10L + index, familyId = item.familyId, sourceId = item.sourceId)
        }
        val module = ExamPapers.satModule(pack, seen, "MATH1", null)
        assertEquals(19, module.exercises.size); assertEquals(3, module.shortfall)
        assertTrue(module.exercises.none { it.skillId == "sat_geometry" })
    }

    @Test fun ieltsReadingUsesTheThreeHeldBackPassagesForFortyQuestionsInSixtyMinutes() {
        val reading = requireNotNull(ExamPapers.ieltsReading(pack, emptyList()))
        assertEquals(40, reading.exercises.size); assertEquals(3, reading.sourceIds.size); assertEquals(60 * 60, reading.timeLimitSeconds)
        assertEquals(StudyPlanner.paperSources(pack), reading.sourceIds.toSet())
        // Once one of them is seen, unseen passages fill the paper instead; it never mixes in a seen one.
        val seenItem = reading.exercises.first()
        val seen = listOf(Attempt("r", seenItem.id, seenItem.version, Exam.IELTS, seenItem.skillId, "x", false, 5, familyId = seenItem.familyId, sourceId = seenItem.sourceId))
        val next = requireNotNull(ExamPapers.ieltsReading(pack, seen))
        assertTrue(seenItem.sourceId !in next.sourceIds)
        assertTrue(next.exercises.size >= 40)
    }

    @Test fun ieltsListeningPlaysFourUnseenRecordingsThenGivesTenMinutesToTransfer() {
        val listening = requireNotNull(ExamPapers.ieltsListening(pack, emptyList()))
        assertEquals(4, listening.audioPaths.size); assertEquals(40, listening.exercises.size)
        assertNull(listening.timeLimitSeconds); assertEquals(10 * 60, listening.transferSeconds)
        assertTrue(listening.exercises.all { it.split == ContentSplit.ASSESSMENT })
        val run = PaperRun("l", Exam.IELTS, PaperKind.IELTS_LISTENING, true, listOf(listening), phase = PaperPhase.TRANSFER,
            transferElapsedSeconds = mapOf(listening.id to 600), startedAt = 0)
        assertTrue(run.timeUp(listening))
    }

    @Test fun ieltsWritingGivesTwentyMinutesForAVisualTaskAndFortyForAnEssay() {
        val (task1, task2) = ExamPapers.ieltsWriting(pack, emptyList())
        assertEquals(20 * 60, task1.timeLimitSeconds); assertEquals(40 * 60, task2.timeLimitSeconds)
        assertTrue(task1.exercises.single().let { it.chart != null || it.figure != null })
        assertTrue(task2.exercises.single().let { it.chart == null && it.figure == null })
        assertTrue((task1.exercises + task2.exercises).all { it.split != ContentSplit.DIAGNOSTIC && it.type == ExerciseType.WRITING })
    }

    @Test fun aBreakHasItsOwnClockAndNeverTimesOutTheNextModule() {
        val rw2 = ExamPapers.satModule(pack, emptyList(), "RW2", ModuleRoute.LOWER)
        val math1 = ExamPapers.satModule(pack, emptyList(), "MATH1", null, rw2.exercises)
        val run = PaperRun("b", Exam.SAT, PaperKind.SAT, true, listOf(rw2, math1), partIndex = 1, phase = PaperPhase.BREAK, breakElapsedSeconds = 700, startedAt = 0)
        assertEquals(-100, run.remaining(math1))
        assertFalse(run.timeUp(math1))
        assertEquals(35 * 60, run.copy(phase = PaperPhase.WORKING).remaining(math1))
    }
}
