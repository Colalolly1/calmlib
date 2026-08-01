package com.calmlib.reader.data.db

import androidx.room.*
import com.calmlib.reader.data.model.Highlight
import com.calmlib.reader.data.model.VocabularyEntry
import kotlinx.coroutines.flow.Flow

@Dao
interface HighlightDao {
    @Query("SELECT * FROM highlights WHERE bookId = :bookId ORDER BY chapter ASC, page ASC")
    fun forBook(bookId: Long): Flow<List<Highlight>>

    @Query("SELECT * FROM highlights ORDER BY dateCreated DESC")
    fun all(): Flow<List<Highlight>>

    @Insert
    suspend fun insert(highlight: Highlight): Long

    @Update
    suspend fun update(highlight: Highlight)

    @Delete
    suspend fun delete(highlight: Highlight)

    @Query("SELECT * FROM vocabulary ORDER BY dateAdded DESC")
    fun allVocabulary(): Flow<List<VocabularyEntry>>

    @Query("SELECT * FROM vocabulary WHERE bookId = :bookId ORDER BY dateAdded DESC")
    fun vocabularyForBook(bookId: Long): Flow<List<VocabularyEntry>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVocabulary(entry: VocabularyEntry): Long

    @Delete
    suspend fun deleteVocabulary(entry: VocabularyEntry)

    @Query("SELECT * FROM highlights WHERE text LIKE :query OR note LIKE :query ORDER BY dateCreated DESC LIMIT 100")
    suspend fun searchHighlights(query: String): List<Highlight>

    @Query("SELECT * FROM vocabulary WHERE word LIKE :query OR definition LIKE :query ORDER BY dateAdded DESC LIMIT 100")
    suspend fun searchVocabulary(query: String): List<VocabularyEntry>

    @Query("DELETE FROM vocabulary")
    suspend fun deleteAllVocabulary()
}
