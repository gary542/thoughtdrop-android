package org.thoughtdrop.notebook

import androidx.room.*

@Entity(tableName = "notes", indices = [Index(value = ["id"], unique = true)])
data class ThoughtNote(
    val id: String, val title: String, val transcript: String, val audioPath: String,
    val createdAt: Long, val isTranscribed: Boolean = false, val errorMessage: String = "",
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "rowid") val rowId: Long = 0
)

@Fts4(contentEntity = ThoughtNote::class, tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "note_search")
data class SearchEntry(val title: String, val transcript: String)

@Dao
interface ThoughtDao {
    @Query("SELECT * FROM notes WHERE id = :id") fun get(id: String): ThoughtNote?
    @Query("SELECT * FROM notes ORDER BY createdAt DESC") fun all(): List<ThoughtNote>
    @Query("SELECT notes.* FROM notes JOIN note_search ON notes.rowid = note_search.rowid WHERE note_search MATCH :expression ORDER BY createdAt DESC")
    fun search(expression: String): List<ThoughtNote>
    @Insert(onConflict = OnConflictStrategy.IGNORE) fun insert(note: ThoughtNote): Long
    @Update fun update(note: ThoughtNote)
    @Query("DELETE FROM notes WHERE id = :id") fun delete(id: String)
}

@Database(entities = [ThoughtNote::class, SearchEntry::class], version = 1, exportSchema = false)
abstract class NotebookDatabase : RoomDatabase() {
    abstract fun thoughts(): ThoughtDao
}

fun searchTerms(query: String): String = Regex("[\\p{L}\\p{N}]+").findAll(query)
    .take(24).joinToString(" ") { "\"${it.value}*\"" }
