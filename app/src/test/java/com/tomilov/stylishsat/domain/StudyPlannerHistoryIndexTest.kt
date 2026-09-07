package com.tomilov.stylishsat.domain

import kotlin.math.abs
import kotlin.random.Random
import org.junit.Assert.*
import org.junit.Test

/** A deliberately simple, frozen pre-index scan protects compatibility while the hot path changes. */
class StudyPlannerHistoryIndexTest {
    private fun familiarByScan(exercise: Exercise, pack: ContentPack, attempts: List<Attempt>): Boolean {
        val current = pack.exercises.associateBy { it.id }
        return attempts.any { old ->
            val priorItem = current[old.exerciseId]
            old.exerciseId == exercise.id ||
                (old.familyId ?: priorItem?.familyId) == exercise.familyId ||
                (old.sourceId ?: priorItem?.sourceId) == exercise.sourceId
        }
    }

    private fun replayByScan(pack: ContentPack, attempts: List<Attempt>): List<SkillState> {
        val states = pack.skills.associate { it.id to SkillState(it.id) }.toMutableMap()
        val current = pack.exercises.associateBy { it.id }
        val prior = mutableListOf<Attempt>()
        attempts.distinctBy { it.id }.sortedWith(compareBy({ it.timestampEpochMillis }, { it.id })).forEach { attempt ->
            val exercise = current[attempt.exerciseId]
            val familiar = if (exercise != null) familiarByScan(exercise, pack, prior) else prior.any { old ->
                old.exerciseId == attempt.exerciseId ||
                    (attempt.familyId != null && attempt.familyId == old.familyId) ||
                    (attempt.sourceId != null && attempt.sourceId == old.sourceId)
            }
            states[attempt.skillId]?.let { state ->
                // Skill-update rules did not change; only the determination of prior exposure did.
                states[attempt.skillId] = StudyPlanner.updateSkill(state, attempt.copy(
                    isRepeat = attempt.isRepeat || familiar,
                    expectedSeconds = attempt.expectedSeconds.takeIf { it > 0 } ?: exercise?.expectedSeconds ?: 0,
                ), attempt.localDateEpochDay ?: Math.floorDiv(attempt.timestampEpochMillis, 86_400_000L))
            }
            prior += attempt
        }
        return states.values.toList()
    }

    private fun freshByScan(pack: ContentPack, exam: Exam, split: ContentSplit, attempts: List<Attempt>) =
        pack.exercises.filter { it.exam == exam && it.split == split && !familiarByScan(it, pack, attempts) }.sortedBy { it.id }

    private fun assessmentByScan(pack: ContentPack, exam: Exam, attempts: List<Attempt>, limit: Int): List<Exercise> {
        val groups = freshByScan(pack, exam, ContentSplit.ASSESSMENT, attempts).groupBy { it.skillId }
        return (0 until (groups.values.maxOfOrNull { it.size } ?: 0)).flatMap { index ->
            pack.skills.filter { it.exam == exam }.mapNotNull { groups[it.id]?.getOrNull(index) }
        }.take(limit)
    }

    private fun practiceByScan(pack: ContentPack, exam: Exam, states: List<SkillState>, attempts: List<Attempt>, limit: Int): List<Exercise> {
        val stateBySkill = states.associateBy { it.skillId }
        val ranked = StudyPlanner.rankedSkills(pack, exam, states, 200)
        val seenIds = attempts.map { it.exerciseId }.toSet()
        val pools = ranked.associate { skill ->
            val level = stateBySkill[skill.id]?.difficulty ?: 1
            val candidates = pack.exercises.filter { it.skillId == skill.id && it.split == ContentSplit.PRACTICE }
            val nearest = candidates.filter { it.type != ExerciseType.WRITING && it.type != ExerciseType.SPEAKING }
                .minWithOrNull(compareBy<Exercise>({ abs(it.difficulty - level) }, { it.difficulty }))?.difficulty
            skill.id to candidates.filter { it.type == ExerciseType.WRITING || it.type == ExerciseType.SPEAKING || it.difficulty == nearest }
                .sortedWith(compareBy<Exercise>({ abs(it.difficulty - level) }, { it.id in seenIds },
                    { familiarByScan(it, pack, attempts) }, { it.id }))
        }
        return (0 until (pools.values.maxOfOrNull { it.size } ?: 0)).flatMap { index ->
            ranked.mapNotNull { pools[it.id]?.getOrNull(index) }
        }.take(limit)
    }

    @Test fun indexedSelectionAndReplayMatchTheFrozenScanAcrossMixedLegacyHistories() {
        val random = Random(0x51A72026)
        repeat(120) { case ->
            val skills = Exam.entries.flatMap { exam -> (0..1).map { Skill("$exam-$it", exam, LocalizedText("Skill", "Навык"), "Section") } }
            val allItems = (0 until 36).map { index ->
                val exam = if (index < 18) Exam.SAT else Exam.IELTS
                val type = if (exam == Exam.IELTS && index % 4 == 0) ExerciseType.WRITING else ExerciseType.SHORT_ANSWER
                Exercise(id = "q-${index.toString().padStart(2, '0')}", version = 1 + random.nextInt(3), exam = exam,
                    skillId = "$exam-${index % 2}", difficulty = 1 + random.nextInt(3),
                    split = ContentSplit.entries[(index / 6) % 3], familyId = "family-${index / 3}", sourceId = "source-${index / 6}",
                    type = type, prompt = "Original fixture $index", acceptedAnswers = if (type == ExerciseType.SHORT_ANSWER) listOf("answer") else emptyList(),
                    expectedSeconds = 60 + index * 3, author = "Differential fixture",
                    criteria = if (type == ExerciseType.WRITING) listOf(LocalizedText("Criterion", "Критерий")) else emptyList())
            }
            val pack = ContentPack(id = "history-$case", version = 4, title = LocalizedText("Fixture", "Тест"),
                skills = skills.shuffled(random), lessons = emptyList(),
                exercises = allItems.filterIndexed { index, _ -> index % 5 != 0 }.shuffled(random))
            val generated = (0 until case % 32).map { index ->
                val item = allItems[random.nextInt(allItems.size)] // Includes deleted item IDs.
                val day = 180L + random.nextInt(20)
                val metadata = index % 7
                Attempt(id = "attempt-$index", exerciseId = item.id, exerciseVersion = 1 + random.nextInt(3), exam = item.exam,
                    skillId = if (index % 13 == 0) "removed-skill" else item.skillId,
                    answer = "answer", correct = when (random.nextInt(4)) { 0 -> null; 1 -> false; else -> true },
                    timestampEpochMillis = day * 86_400_000L + random.nextInt(5000),
                    elapsedSeconds = random.nextInt(200), hintsUsed = random.nextInt(3), isRepeat = random.nextInt(5) == 0,
                    difficulty = 1 + random.nextInt(3), split = item.split,
                    familyId = when (metadata) { 1, 3 -> null; 4 -> "historical-family-${index % 3}"; 6 -> ""; else -> item.familyId },
                    sourceId = when (metadata) { 1, 2 -> null; 5 -> "historical-source-${index % 3}"; 6 -> ""; else -> item.sourceId },
                    expectedSeconds = if (random.nextBoolean()) 0 else item.expectedSeconds,
                    localDateEpochDay = if (random.nextBoolean()) null else day + 1)
            }.toMutableList()
            // Preserve the old first-occurrence rule for duplicate IDs, including conflicting copies.
            if (generated.isNotEmpty()) repeat(3) {
                val old = generated[random.nextInt(generated.size)]
                generated += old.copy(correct = old.correct?.not(), timestampEpochMillis = old.timestampEpochMillis + 17)
            }
            val history = generated.shuffled(random)
            val expectedStates = replayByScan(pack, history)
            // Open-response ordering intentionally now balances formats and prioritizes novelty.
            // Keep the pre-index selection oracle unchanged for closed practice, and keep the
            // full mixed-history familiarity/replay/held-out comparisons below unchanged.
            val closedPracticePack = pack.copy(exercises = pack.exercises.filter {
                it.split != ContentSplit.PRACTICE || it.type != ExerciseType.WRITING && it.type != ExerciseType.SPEAKING
            })
            assertEquals("replay case=$case", expectedStates.associateBy { it.skillId },
                StudyPlanner.statesFromAttempts(pack, history).associateBy { it.skillId })
            pack.exercises.forEach { candidate ->
                assertEquals("familiar case=$case item=${candidate.id}", familiarByScan(candidate, pack, history),
                    StudyPlanner.isFamiliar(candidate, pack, history))
            }
            Exam.entries.forEach { exam ->
                val expectedDiagnostic = pack.skills.filter { it.exam == exam }.flatMap { skill ->
                    freshByScan(pack, exam, ContentSplit.DIAGNOSTIC, history).filter { it.skillId == skill.id }
                        .sortedWith(compareBy({ it.difficulty }, { it.id })).take(if (exam == Exam.SAT) 2 else 1)
                }
                assertEquals("diagnostic case=$case exam=$exam", expectedDiagnostic, StudyPlanner.diagnostic(pack, exam, history))
                listOf(0, 1, 5, 20).forEach { limit ->
                    assertEquals("assessment case=$case exam=$exam limit=$limit", assessmentByScan(pack, exam, history, limit),
                        StudyPlanner.assessment(pack, exam, history, limit))
                    assertEquals("closed practice case=$case exam=$exam limit=$limit", practiceByScan(closedPracticePack, exam, expectedStates, history, limit),
                        StudyPlanner.nextPractice(closedPracticePack, exam, expectedStates, history, 200, limit))
                }
            }
        }
    }

    @Test fun removedItemsKeepTheLegacyExplicitMetadataRuleDuringReplay() {
        val skill = Skill("skill", Exam.SAT, LocalizedText("Skill", "Навык"), "Section")
        val present = Exercise("present", exam = Exam.SAT, skillId = skill.id, split = ContentSplit.PRACTICE,
            familyId = "shared-family", sourceId = "present-source", type = ExerciseType.SHORT_ANSWER,
            prompt = "Fixture", acceptedAnswers = listOf("answer"), author = "Fixture")
        val pack = ContentPack(id = "legacy-removal", version = 2, title = LocalizedText("Fixture", "Тест"),
            skills = listOf(skill), lessons = emptyList(), exercises = listOf(present))
        val legacy = Attempt("first", present.id, 1, Exam.SAT, skill.id, "answer", true, 1,
            familyId = null, sourceId = null)
        val removed = Attempt("second", "removed-item", 1, Exam.SAT, skill.id, "answer", true, 2,
            familyId = "shared-family", sourceId = "removed-source")
        val actual = StudyPlanner.statesFromAttempts(pack, listOf(legacy, removed)).single()
        assertEquals(replayByScan(pack, listOf(legacy, removed)).single(), actual)
        assertEquals("the pre-index removed-item branch compared explicit metadata only", 2, actual.independentCount)
    }
}
