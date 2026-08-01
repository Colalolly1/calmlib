package com.calmlib.reader.data.db

import androidx.room.*
import com.calmlib.reader.data.model.BookCollection
import com.calmlib.reader.data.model.Collection
import kotlinx.coroutines.flow.Flow

@Dao
interface CollectionDao {
    @Query("SELECT * FROM collections ORDER BY name ASC")
    fun all(): Flow<List<Collection>>

    @Query("SELECT * FROM collections WHERE id = :id")
    suspend fun getById(id: Long): Collection?

    @Query("SELECT collectionId FROM book_collections WHERE bookId = :bookId")
    fun collectionsForBook(bookId: Long): Flow<List<Long>>

    @Insert
    suspend fun insert(collection: Collection): Long

    @Update
    suspend fun update(collection: Collection)

    @Delete
    suspend fun delete(collection: Collection)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addBookToCollection(bookCollection: BookCollection)

    @Delete
    suspend fun removeBookFromCollection(bookCollection: BookCollection)
}
