package com.tomilov.stylishsat.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Entity(tableName = "records", indices = [Index(value = ["kind", "exam"])])
data class StoredRecord(@PrimaryKey val key: String, val kind: String, val exam: String = "", val payload: String)

@Entity(tableName = "content_packs", primaryKeys = ["id", "version"])
data class StoredPack(val id: String, val version: Int, val payload: String)

data class RecordHeader(val key: String, val kind: String, val exam: String, val payloadLength: Int)
data class PackHeader(val id: String, val version: Int, val payloadLength: Int)

private const val PAYLOAD_SLICE_CHARACTERS = 65_536

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "lesson_search")
data class LessonSearch(val lessonId: String, val skillId: String, val body: String)

@Fts4
@Entity(tableName = "lesson_search_legacy")
data class LegacyLessonSearch(val lessonId: String, val skillId: String, val body: String)

@Dao
interface StudyDao {
    @Query("SELECT `key`, kind, exam, length(payload) AS payloadLength FROM records")
    fun observeRecordHeaders(): Flow<List<RecordHeader>>
    fun observeRecords(): Flow<List<StoredRecord>> = observeRecordHeaders().map { records() }
    @Query("SELECT `key`, kind, exam, length(payload) AS payloadLength FROM records")
    suspend fun recordHeaders(): List<RecordHeader>
    @Query("SELECT substr(payload, :start, :characters) FROM records WHERE `key` = :key")
    suspend fun recordPayloadSlice(key: String, start: Int, characters: Int): String
    /** A bounded SQLite cursor row also supports large preserved essays and plan snapshots. */
    @Transaction
    suspend fun records(): List<StoredRecord> = recordHeaders().map { row ->
        val payload = buildString {
            for (offset in 0 until row.payloadLength step PAYLOAD_SLICE_CHARACTERS) {
                append(recordPayloadSlice(row.key, offset + 1, PAYLOAD_SLICE_CHARACTERS))
            }
        }
        StoredRecord(row.key, row.kind, row.exam, payload)
    }
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(record: StoredRecord)
    @Query("SELECT id, version, length(payload) AS payloadLength FROM content_packs ORDER BY version")
    suspend fun packHeaders(): List<PackHeader>
    @Query("SELECT id, version, length(payload) AS payloadLength FROM content_packs WHERE id = :id AND version = :version")
    suspend fun packHeader(id: String, version: Int): PackHeader?
    @Query("SELECT substr(payload, :start, :characters) FROM content_packs WHERE id = :id AND version = :version")
    suspend fun packPayloadSlice(id: String, version: Int, start: Int, characters: Int): String
    @Transaction
    suspend fun pack(id: String, version: Int): StoredPack? {
        val row = packHeader(id, version) ?: return null
        val payload = buildString {
            for (offset in 0 until row.payloadLength step PAYLOAD_SLICE_CHARACTERS) {
                append(packPayloadSlice(row.id, row.version, offset + 1, PAYLOAD_SLICE_CHARACTERS))
            }
        }
        return StoredPack(row.id, row.version, payload)
    }
    /** Avoid Android CursorWindow's per-row limit without rewriting or compressing existing data. */
    @Transaction
    suspend fun packs(): List<StoredPack> = packHeaders().map { row ->
        val payload = buildString {
            for (offset in 0 until row.payloadLength step PAYLOAD_SLICE_CHARACTERS) {
                append(packPayloadSlice(row.id, row.version, offset + 1, PAYLOAD_SLICE_CHARACTERS))
            }
        }
        StoredPack(row.id, row.version, payload)
    }
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertPack(pack: StoredPack)
    @Query("DELETE FROM lesson_search") suspend fun clearSearch()
    @Insert suspend fun addSearch(rows: List<LessonSearch>)
    @Query("SELECT lessonId FROM lesson_search WHERE lesson_search MATCH :query LIMIT 5")
    suspend fun search(query: String): List<String>
    @Query("SELECT lessonId FROM lesson_search WHERE skillId = :skillId AND lesson_search MATCH :query LIMIT 5")
    suspend fun searchForSkill(skillId: String, query: String): List<String>
}

@Database(entities = [StoredRecord::class, StoredPack::class, LessonSearch::class, LegacyLessonSearch::class], version = 2, exportSchema = true)
abstract class StudyDatabase : RoomDatabase() {
    abstract fun dao(): StudyDao
    companion object {
        /** Keep the original index and all private data; copy its lesson rows into a Unicode index. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE lesson_search RENAME TO lesson_search_legacy")
                db.execSQL("CREATE VIRTUAL TABLE lesson_search USING FTS4(lessonId TEXT NOT NULL, skillId TEXT NOT NULL, body TEXT NOT NULL, tokenize=unicode61)")
                db.execSQL("INSERT INTO lesson_search(lessonId, skillId, body) SELECT lessonId, skillId, body FROM lesson_search_legacy")
            }
        }
        @Volatile private var instance: StudyDatabase? = null
        fun get(context: Context): StudyDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, StudyDatabase::class.java, "stylishsat.db")
                .addMigrations(MIGRATION_1_2).build().also { instance = it }
        }
    }
}
