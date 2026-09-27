package com.tomilov.stylishsat.domain

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class SectionPracticeTest {
    private fun bundled(): ContentPack = ContentPackCodec.decode(listOf(
        File("src/main/assets/content/seed-v1.json"), File("app/src/main/assets/content/seed-v1.json"),
    ).first { it.isFile }.readText())

    private fun item(id: String, source: String = "passage-a", answers: List<String> = listOf("noon"), split: ContentSplit = ContentSplit.PRACTICE) = Exercise(
        id = id, exam = Exam.IELTS, skillId = "ielts_reading", split = split, familyId = source, sourceId = source,
        type = ExerciseType.SHORT_ANSWER, prompt = "Write ONE WORD.", passage = "A. Readings were taken at noon.",
        acceptedAnswers = answers, wordLimit = 1, author = "Test fixture",
    )

    private fun pack(vararg items: Exercise) = ContentPack(id = "fixture", version = 1, title = LocalizedText("F", "F"),
        skills = listOf(Skill("ielts_reading", Exam.IELTS, LocalizedText("Reading", "Чтение"), "Reading")), lessons = emptyList(), exercises = items.toList())

    private fun run(part: PaperPart, answers: Map<String, String> = emptyMap(), seconds: Int = 0) =
        PaperRun("run-1", Exam.IELTS, PaperKind.SECTION, strict = false, parts = listOf(part), answers = answers,
            elapsedSeconds = mapOf(part.id to seconds), startedAt = 0)

    @Test fun everyBundledReadingAndListeningSourceBecomesOneTenQuestionSection() {
        val pack = bundled()
        val sections = Sections.of(pack, Exam.IELTS)
        listOf("ielts_reading", "ielts_listening").forEach { skill ->
            val original = sections.filter { it.skillId == skill && it.exercises.size == 10 }
            assertEquals(skill, 24, original.size)
            assertTrue(original.all { section -> section.exercises.all { it.sourceId == section.sourceId && it.split == section.split } })
        }
        assertTrue(sections.filter { it.skillId == "ielts_listening" }.all { it.listening && it.audioAssetPath != null })
        assertTrue(Sections.of(pack, Exam.SAT).isEmpty())
        assertEquals("Avel coldroom", Sections.humanize("reading-v3-avel-coldroom"))
        assertEquals("Theatre", Sections.humanize("listening-a-theatre"))
    }

    @Test fun strictReadingClockAllowsNinetySecondsPerQuestion() {
        val section = Sections.of(listOf(item("q1"), item("q2"))).single()
        assertEquals(180, Sections.strictSeconds(section))
        val listening = section.copy(exercises = section.exercises.map { it.copy(passage = null, audioAssetPath = "audio/a.ogg", transcript = "t") })
        assertNull(Sections.strictSeconds(listening))
    }

    @Test fun blanksAreNotEvidenceButMalformedAnswersAreWrong() {
        val numeric = item("n").copy(type = ExerciseType.NUMERIC, acceptedAnswers = listOf("1/2"), wordLimit = null)
        val part = PaperPart("p", "P", listOf(item("q1"), item("q2"), numeric))
        val outcomes = PaperScoring.score(part, mapOf("q1@1" to " NOON ", "n@1" to "half"))
        assertEquals(listOf(true, null, false), outcomes.map { it.correct })
        assertEquals(listOf(null, "SKIPPED", "NUMERIC_FORMAT"), outcomes.map { it.errorType })
        val raw = PaperScoring.raw(outcomes)
        assertEquals(1, raw.correct); assertEquals(3, raw.closed); assertEquals(2, raw.answered)
    }

    @Test fun apportionedSecondsAddUpToTheClock() {
        assertEquals(listOf(4, 3, 3), PaperScoring.apportion(10, 3))
        assertEquals(10, PaperScoring.apportion(10, 3).sum())
        assertEquals(emptyList<Int>(), PaperScoring.apportion(10, 0))
    }

    @Test fun siblingsInOneSittingStayIndependentButLaterDrillsAreRepeats() {
        val first = item("q1"); val second = item("q2"); val third = item("q3")
        val pack = pack(first, second, third)
        val part = PaperPart("p", "P", listOf(first, second))
        val sitting = run(part, mapOf("q1@1" to "noon", "q2@1" to "noon"), seconds = 61)
        var counter = 0
        val attempts = PaperScoring.attempts(sitting, part, PaperScoring.score(part, sitting.answers), pack, emptyList(), 1_000, 10) { "a${counter++}" }
        assertTrue(attempts.none { it.isRepeat })
        assertTrue(attempts.all { it.independent && it.runId == "run-1" })
        assertEquals(61, attempts.sumOf { it.elapsedSeconds })
        // Replaying history keeps both answers as independent evidence for the skill.
        val state = StudyPlanner.statesFromAttempts(pack, attempts).single()
        assertEquals(2, state.independentCount)
        // A later drill on the same passage outside the sitting is familiar.
        assertTrue(StudyPlanner.isFamiliar(third, pack, attempts))
        assertFalse(StudyPlanner.isFamiliar(third, pack, attempts, runId = "run-1"))
        val drill = Attempt("d", "q3", 1, Exam.IELTS, "ielts_reading", "noon", true, 2_000, familyId = "passage-a", sourceId = "passage-a")
        assertEquals(2, StudyPlanner.statesFromAttempts(pack, attempts + drill).single().independentCount)
    }

    @Test fun anEarlierDrillStillMakesTheWholeSittingFamiliar() {
        val first = item("q1"); val second = item("q2")
        val pack = pack(first, second)
        val drill = Attempt("d", "q1", 1, Exam.IELTS, "ielts_reading", "noon", true, 500, familyId = "passage-a", sourceId = "passage-a")
        val part = PaperPart("p", "P", listOf(first, second))
        val sitting = run(part, mapOf("q1@1" to "noon", "q2@1" to "noon"))
        var counter = 0
        val attempts = PaperScoring.attempts(sitting, part, PaperScoring.score(part, sitting.answers), pack, listOf(drill), 1_000, 10) { "s${counter++}" }
        assertTrue(attempts.all { it.isRepeat })
        assertEquals(1, StudyPlanner.statesFromAttempts(pack, listOf(drill) + attempts).single().independentCount)
    }

    @Test fun timeUpUsesTheCheckClockAfterARecordingAndOvertimeReleasesIt() {
        val part = PaperPart("p", "P", listOf(item("q1")), timeLimitSeconds = 90, transferSeconds = 120)
        val working = run(part, seconds = 90)
        assertTrue(working.timeUp(part))
        assertFalse(working.copy(overtimeParts = listOf("p")).timeUp(part))
        val transfer = working.copy(phase = PaperPhase.TRANSFER, transferElapsedSeconds = mapOf("p" to 30))
        assertEquals(90, transfer.remaining(part))
        assertFalse(transfer.timeUp(part))
    }

    @Test fun groupedQuestionsMustShareContextAndReferOnlyToMembers() {
        val group = QuestionGroup("g1", "Complete the summary.", text = "Roofs were cooler at [[q1]] and [[q2]].")
        val one = item("q1").copy(group = group, format = QuestionFormat.SUMMARY_COMPLETION)
        val two = item("q2").copy(group = group, format = QuestionFormat.SUMMARY_COMPLETION)
        val valid = pack(one, two).copy(schemaVersion = 3)
        ContentPackCodec.validate(valid)
        assertEquals("Roofs were cooler at (1) ______ and ______.", ContentPackCodec.fillGaps(group.text!!, mapOf("q1" to 1)))
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.validate(valid.copy(schemaVersion = 2)) }
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(pack(one, two.copy(group = group.copy(instruction = "Other"))).copy(schemaVersion = 3))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(pack(one.copy(group = group.copy(text = "[[q9]]")), two.copy(group = group.copy(text = "[[q9]]"))).copy(schemaVersion = 3))
        }
    }

    @Test fun matchingKeysMustComeFromTheSharedListAndFiguresStayInsideTheirPanel() {
        val list = QuestionGroup("g", "Choose the heading.", options = listOf(GroupOption("i", "Cause"), GroupOption("ii", "Cost"), GroupOption("iii", "Risk")))
        val matching = item("m").copy(type = ExerciseType.MULTIPLE_CHOICE, options = listOf("i", "ii", "iii"), acceptedAnswers = listOf("ii"), wordLimit = null,
            group = list, format = QuestionFormat.MATCHING_HEADINGS)
        ContentPackCodec.validate(pack(matching).copy(schemaVersion = 3))
        assertThrows(IllegalArgumentException::class.java) {
            ContentPackCodec.validate(pack(matching.copy(options = listOf("i", "iv"), acceptedAnswers = listOf("i"))).copy(schemaVersion = 3))
        }
        val figure = Figure(FigureKind.PROCESS, "Process", listOf(FigurePanel(nodes = listOf(FigureNode("a", "Wash", 0.8f, 0.1f, width = 0.3f)))))
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.validate(pack(item("f").copy(figure = figure)).copy(schemaVersion = 3)) }
        val pie = ChartData("Share", "Use", "Share", "%", listOf("A", "B"), listOf(ChartSeries("2020", listOf(40.0, -1.0))), ChartKind.PIE)
        assertThrows(IllegalArgumentException::class.java) { ContentPackCodec.validate(pack(item("c").copy(chart = pie)).copy(schemaVersion = 3)) }
    }
}
