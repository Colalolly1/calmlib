package com.calmlib.reader.data.db

import androidx.room.*
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookFormat
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY title ASC")
    fun allByTitle(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY author ASC")
    fun allByAuthor(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY dateAdded DESC")
    fun allByDateAdded(): Flow<List<Book>>

    // "Recently active": reading counts, but so does just adding the book —
    // otherwise a fresh import (lastRead = 0) sorts to the very bottom and looks
    // like it never arrived.
    @Query("SELECT * FROM books ORDER BY MAX(lastRead, dateAdded) DESC")
    fun allByLastRead(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY format ASC, title ASC")
    fun allByFormat(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE isCurrentlyReading = 1 ORDER BY lastRead DESC LIMIT 5")
    fun currentlyReading(): Flow<List<Book>>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getById(id: Long): Book?

    @Query("SELECT * FROM books WHERE filePath = :path LIMIT 1")
    suspend fun getByPath(path: String): Book?

    @Query("SELECT * FROM books WHERE title = :title AND author = :author AND fileSize = :size LIMIT 1")
    suspend fun getByFingerprint(title: String, author: String, size: Long): Book?

    @Query("SELECT * FROM books WHERE title LIKE '%' || :query || '%' OR author LIKE '%' || :query || '%'")
    fun search(query: String): Flow<List<Book>>

    @Query("""
        SELECT b.* FROM books b
        INNER JOIN book_collections bc ON b.id = bc.bookId
        WHERE bc.collectionId = :collectionId
        ORDER BY b.title ASC
    """)
    fun booksInCollection(collectionId: Long): Flow<List<Book>>

    @Insert
    suspend fun insert(book: Book): Long

    @Update
    suspend fun update(book: Book)

    @Delete
    suspend fun delete(book: Book)

    @Query("UPDATE books SET currentPage = :page, currentChapter = :chapter, scrollOffset = :offset, bookProgress = :bookProgress, lastRead = :time, isCurrentlyReading = 1 WHERE id = :bookId")
    suspend fun updateProgress(bookId: Long, page: Int, chapter: Int, offset: Float, bookProgress: Float, time: Long = System.currentTimeMillis())

    @Query("UPDATE books SET totalPages = :total WHERE id = :bookId")
    suspend fun updateTotalPages(bookId: Long, total: Int)

    @Query("UPDATE books SET readingSettingsJson = :json WHERE id = :bookId")
    suspend fun updateReadingSettingsJson(bookId: Long, json: String?)

    // Books that were never opened still have totalPages = 0; "currentPage =
    // totalPages" would be a no-op (0 = 0) and the book would never read as
    // finished. Coerce both to at least 1 so progress lands at 100%.
    @Query("""
        UPDATE books SET
            totalPages = CASE WHEN totalPages <= 0 THEN 1 ELSE totalPages END,
            currentPage = CASE WHEN totalPages <= 0 THEN 1 ELSE totalPages END,
            bookProgress = 1.0,
            isCurrentlyReading = 0,
            lastRead = :time
        WHERE id = :bookId
    """)
    suspend fun markFinished(bookId: Long, time: Long = System.currentTimeMillis())

    @Query("UPDATE books SET isCurrentlyReading = 0 WHERE id = :bookId")
    suspend fun removeFromCurrentlyReading(bookId: Long)

    @Query("SELECT * FROM books WHERE format = :format ORDER BY title ASC")
    fun byFormat(format: BookFormat): Flow<List<Book>>

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    @Query("UPDATE books SET title = :title, author = :author WHERE id = :bookId")
    suspend fun renameBook(bookId: Long, title: String, author: String)
}
