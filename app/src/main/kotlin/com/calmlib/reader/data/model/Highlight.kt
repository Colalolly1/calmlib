package com.calmlib.reader.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "highlights",
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId")],
)
data class Highlight(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val chapter: Int = 0,
    val page: Int = 0,
    val text: String,
    val note: String = "",
    val startOffset: Int = 0,
    val endOffset: Int = 0,
    val dateCreated: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "vocabulary",
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.SET_NULL),
    ],
    indices = [Index("bookId")],
)
data class VocabularyEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val word: String,
    val definition: String,
    val bookId: Long? = null,
    val dateAdded: Long = System.currentTimeMillis(),
)
