package com.calmlib.reader.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "collections")
data class Collection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val dateCreated: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "book_collections",
    primaryKeys = ["bookId", "collectionId"],
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
        ForeignKey(entity = Collection::class, parentColumns = ["id"], childColumns = ["collectionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("collectionId")],
)
data class BookCollection(
    val bookId: Long,
    val collectionId: Long,
)
