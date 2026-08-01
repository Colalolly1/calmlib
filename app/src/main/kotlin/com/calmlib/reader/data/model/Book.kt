package com.calmlib.reader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class BookFormat { EPUB, PDF, TXT, FB2 }
enum class SortField { TITLE, AUTHOR, DATE_ADDED, LAST_READ, FORMAT }

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val format: BookFormat,
    val filePath: String,
    val coverPath: String? = null,
    val fileSize: Long = 0,
    val totalPages: Int = 0,
    val currentPage: Int = 0,
    val currentChapter: Int = 0,
    val scrollOffset: Float = 0f,
    val dateAdded: Long = System.currentTimeMillis(),
    val lastRead: Long = 0,
    val isCurrentlyReading: Boolean = false,
    val fontSize: Float = 0f,
    val lineSpacing: Float = 0f,
    val marginHorizontal: Int = -1,
    val marginVertical: Int = -1,
    val fontFamily: String = "",
    val boldMode: Boolean = false,
    /** Per-book reading settings serialized as JSON. Null = book has never been
     *  customized and should open with global defaults. */
    val readingSettingsJson: String? = null,
    /** Whole-book progress 0..1, weighted by chapter byte sizes for EPUB/FB2.
     *  currentPage/totalPages alone is per-chapter for those formats and was
     *  misleading on covers and in stats. 0 = unknown/never opened. */
    val bookProgress: Float = 0f,
) {
    val progress: Float
        get() = when {
            bookProgress > 0f -> bookProgress.coerceIn(0f, 1f)
            totalPages > 0 -> currentPage.toFloat() / totalPages
            else -> 0f
        }

    val hasCustomSettings: Boolean
        get() = readingSettingsJson != null
}
