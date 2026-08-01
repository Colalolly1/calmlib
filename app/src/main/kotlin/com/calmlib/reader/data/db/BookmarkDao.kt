package com.calmlib.reader.data.db

import androidx.room.*
import com.calmlib.reader.data.model.Bookmark
import kotlinx.coroutines.flow.Flow

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY page ASC")
    fun forBook(bookId: Long): Flow<List<Bookmark>>

    @Insert
    suspend fun insert(bookmark: Bookmark): Long

    @Delete
    suspend fun delete(bookmark: Bookmark)

    @Query("SELECT * FROM bookmarks WHERE label LIKE :query ORDER BY dateCreated DESC LIMIT 100")
    suspend fun searchBookmarks(query: String): List<Bookmark>
}
