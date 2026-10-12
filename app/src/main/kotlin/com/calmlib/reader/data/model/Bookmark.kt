package com.calmlib.reader.data.model

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = Book::class, parentColumns = ["id"], childColumns = ["bookId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("bookId")],
)
data class Bookmark(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val page: Int,
    val chapter: Int = 0,
    val label: String = "",
    val dateCreated: Long = System.currentTimeMillis(),
    /** Chapter-local character offset of the page top; -1 = not recorded
     *  (older bookmarks, PDFs), in which case [page] is used. */
    val charOffset: Int = -1,
)
