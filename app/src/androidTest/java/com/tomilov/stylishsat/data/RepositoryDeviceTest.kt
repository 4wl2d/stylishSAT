package com.tomilov.stylishsat.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tomilov.stylishsat.StudySession
import com.tomilov.stylishsat.domain.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RepositoryDeviceTest {
    @Test fun keywordMatchWithinTheSkillOutranksEarlierGenericLessons(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            val skill = Skill("lookup", Exam.SAT, LocalizedText("Lookup", "Поиск"), "Math")
            val pack = ContentPack(id = "lookup-test", version = 1, title = LocalizedText("Lookup", "Поиск"),
                skills = listOf(skill), lessons = (1..6).map { index ->
                    Lesson("lesson-$index", skill.id, LocalizedText("Lesson $index", "Урок $index"),
                        LocalizedText(if (index == 5) "Use arcsine for this angle." else "A generic rule.",
                            if (index == 5) "АРКСИНУС помогает найти угол." else "Общее правило."),
                        LocalizedText("Example", "Пример"))
                }, exercises = listOf(Exercise(id = "question", exam = Exam.SAT, skillId = skill.id,
                    split = ContentSplit.PRACTICE, familyId = "question", type = ExerciseType.NUMERIC,
                    prompt = "What is 1+1?", acceptedAnswers = listOf("2"), author = "test")))
            val repository = ContentRepository(context, database)
            repository.importPack(ContentPackCodec.encode(pack))
            assertEquals("lesson-5", repository.contextFor(skill.id, "Explain how arcsine works").first().id)
            assertEquals("lesson-5", repository.contextFor(skill.id, "арксинус").first().id)
            assertEquals(3, repository.contextFor(skill.id, "").size)
        } finally { database.close() }
    }
    @Test fun updatePreservesAttemptsSnapshotsAndSafeRetrieval(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            val repository = ContentRepository(context, database)
            val pack = repository.initialize()
            val question = pack.exercises.first { it.split == ContentSplit.PRACTICE }
            val session = StudySession(exam = question.exam, mode = question.split, exercises = listOf(question), draft = "preserved draft", activeSeconds = 17)
            val attempt = Attempt("test-attempt", question.id, question.version, question.exam, question.skillId, question.acceptedAnswers.firstOrNull() ?: "draft", true, 1, hintsUsed = 1)
            database.dao().put(StoredRecord("session", "session", question.exam.name, storageJson.encodeToString(session)))
            database.dao().put(StoredRecord("attempt", "attempt", question.exam.name, storageJson.encodeToString(attempt)))
            val changed = question.copy(version = question.version + 1, prompt = question.prompt + " Updated wording.")
            repository.importPack(ContentPackCodec.encode(pack.copy(version = pack.version + 1, exercises = pack.exercises.map { if (it.id == question.id) changed else it })))
            val restored = database.dao().records()
            assertEquals(attempt, storageJson.decodeFromString<Attempt>(restored.first { it.kind == "attempt" }.payload))
            assertEquals(session, storageJson.decodeFromString<StudySession>(restored.first { it.kind == "session" }.payload))
            assertTrue(repository.allExercises().contains(question))
            assertTrue(repository.allExercises().contains(changed))
            val excerpts = repository.contextFor(question.skillId, "rule правило")
            assertTrue(excerpts.isNotEmpty())
            assertTrue(excerpts.all { candidate -> pack.lessons.any { it.id == candidate.id } })
            assertEquals(pack.version + 1, repository.activePack().version)
        } finally { database.close() }
    }
    @Test fun invalidUpdateIsAtomicAndDoesNotReplaceInstalledContent(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, StudyDatabase::class.java).build()
        try {
            val repository = ContentRepository(context, database)
            val pack = repository.initialize()
            val changedWithoutVersion = pack.copy(version = pack.version + 1, exercises = pack.exercises.mapIndexed { index, exercise -> if (index == 0) exercise.copy(prompt = "Changed without item version") else exercise })
            assertTrue(runCatching { repository.importPack(ContentPackCodec.encode(changedWithoutVersion)) }.isFailure)
            assertEquals(pack, repository.activePack())
            assertEquals(1, database.dao().packs().size)
            val shifted = pack.copy(version = pack.version + 1, exercises = pack.exercises.map { exercise -> exercise.copy(version = exercise.version + 1, split = ContentSplit.PRACTICE) })
            assertTrue(runCatching { repository.importPack(ContentPackCodec.encode(shifted)) }.isFailure)
            assertEquals(pack, repository.activePack())
        } finally { database.close() }
    }
}
