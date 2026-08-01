package com.calmlib.reader.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reading_sessions")
data class ReadingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: Long,
    val startTime: Long,
    val endTime: Long = 0,
    val pagesRead: Int = 0,
)

data class ReadingStats(
    val totalBooksRead: Int = 0,
    val totalPagesRead: Int = 0,
    val totalTimeMinutes: Long = 0,
    val pagesToday: Int = 0,
    val minutesToday: Long = 0,
)
