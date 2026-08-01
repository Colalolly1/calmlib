package com.calmlib.reader.data.db

import androidx.room.*
import com.calmlib.reader.data.model.ReadingSession
import kotlinx.coroutines.flow.Flow

@Dao
interface StatsDao {
    @Insert
    suspend fun insert(session: ReadingSession): Long

    @Query("UPDATE reading_sessions SET endTime = :endTime, pagesRead = :pages WHERE id = :id")
    suspend fun endSession(id: Long, endTime: Long, pages: Int)

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions")
    fun totalPagesRead(): Flow<Int>

    @Query("SELECT COALESCE(SUM(endTime - startTime), 0) / 60000 FROM reading_sessions WHERE endTime > 0")
    fun totalMinutesRead(): Flow<Long>

    @Query("SELECT COALESCE(SUM(pagesRead), 0) FROM reading_sessions WHERE startTime >= :dayStart")
    fun pagesToday(dayStart: Long): Flow<Int>

    @Query("SELECT COALESCE(SUM(endTime - startTime), 0) / 60000 FROM reading_sessions WHERE startTime >= :dayStart AND endTime > 0")
    fun minutesToday(dayStart: Long): Flow<Long>

    // bookProgress is the authoritative whole-book fraction; the legacy
    // page-based check only applies to rows that predate the column (it
    // over-counts EPUBs because their pages were per-chapter).
    @Query("""
        SELECT COUNT(*) FROM books
        WHERE bookProgress >= 0.995
           OR (bookProgress <= 0 AND currentPage >= totalPages AND totalPages > 0)
    """)
    fun booksCompleted(): Flow<Int>

    @Query("DELETE FROM reading_sessions")
    suspend fun deleteAll()
}
