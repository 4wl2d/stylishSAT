package com.tomilov.stylishsat.data

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class SearchMigrationDeviceTest {
    @Test fun unicodeSearchMigrationPreservesEveryOriginalTableAndPrivateRecord(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "search-migration-test-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        // Exact version1 DDL and identity from the checked-in Room1 schema, in a new test-only database.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            old.execSQL("CREATE TABLE records (`key` TEXT NOT NULL PRIMARY KEY, kind TEXT NOT NULL, exam TEXT NOT NULL, payload TEXT NOT NULL)")
            old.execSQL("CREATE INDEX index_records_kind_exam ON records(kind,exam)")
            old.execSQL("CREATE TABLE content_packs (id TEXT NOT NULL, version INTEGER NOT NULL, payload TEXT NOT NULL, PRIMARY KEY(id,version))")
            old.execSQL("CREATE VIRTUAL TABLE lesson_search USING FTS4(lessonId TEXT NOT NULL,skillId TEXT NOT NULL,body TEXT NOT NULL)")
            old.execSQL("CREATE TABLE room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            old.execSQL("INSERT INTO room_master_table VALUES(42,'b95f8f9e5477fc005cc90f537f5d02b0')")
            old.execSQL("INSERT INTO records VALUES(?,?,?,?)", arrayOf("private", "draft", "IELTS", "Мой полный черновик / complete private draft"))
            old.execSQL("INSERT INTO content_packs VALUES(?,?,?)", arrayOf<Any>("pack", 2, "original package payload"))
            old.execSQL("INSERT INTO lesson_search VALUES(?,?,?)", arrayOf("lesson", "skill", "НЕЗАВИСИМОСТЬ и Evidence"))
            old.version = 1
        }
        val migrated = Room.databaseBuilder(context, StudyDatabase::class.java, name).addMigrations(StudyDatabase.MIGRATION_1_2).build()
        try {
            assertEquals("Мой полный черновик / complete private draft", migrated.dao().records().single().payload)
            assertEquals("original package payload", migrated.dao().packs().single().payload)
            assertEquals(listOf("lesson"), migrated.dao().search("независимость"))
            assertEquals(listOf("lesson"), migrated.dao().search("evidence"))
            assertEquals(listOf("lesson"), migrated.dao().search("EVIDENCE"))
            migrated.openHelper.readableDatabase.query("SELECT body FROM lesson_search_legacy").use { rows ->
                assertTrue(rows.moveToFirst()); assertEquals("НЕЗАВИСИМОСТЬ и Evidence", rows.getString(0))
            }
        } finally { migrated.close() }
    }
}
