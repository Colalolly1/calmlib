package com.calmlib.reader.engine

import android.content.Context
import com.calmlib.reader.data.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class BackupManager(private val context: Context) {

    suspend fun exportBackup(): File = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val books = db.bookDao().allByTitle().first()
        val collections = db.collectionDao().all().first()
        val highlights = db.highlightDao().all().first()
        val vocabulary = db.highlightDao().allVocabulary().first()

        val json = JSONObject().apply {
            put("version", 1)
            put("app", "CalmLib")
            put("exportDate", System.currentTimeMillis())

            put("books", JSONArray().apply {
                books.forEach { book ->
                    put(JSONObject().apply {
                        put("title", book.title)
                        put("author", book.author)
                        put("format", book.format.name)
                        put("filePath", book.filePath)
                        put("currentPage", book.currentPage)
                        put("currentChapter", book.currentChapter)
                        put("totalPages", book.totalPages)
                        put("dateAdded", book.dateAdded)
                        put("lastRead", book.lastRead)
                        put("fontSize", book.fontSize.toDouble())
                        put("lineSpacing", book.lineSpacing.toDouble())
                        put("fontFamily", book.fontFamily)
                    })
                }
            })

            put("collections", JSONArray().apply {
                collections.forEach { c ->
                    put(JSONObject().apply {
                        put("name", c.name)
                        put("dateCreated", c.dateCreated)
                    })
                }
            })

            put("highlights", JSONArray().apply {
                highlights.forEach { h ->
                    put(JSONObject().apply {
                        put("bookId", h.bookId)
                        put("chapter", h.chapter)
                        put("page", h.page)
                        put("text", h.text)
                        put("note", h.note)
                        put("dateCreated", h.dateCreated)
                    })
                }
            })

            put("vocabulary", JSONArray().apply {
                vocabulary.forEach { v ->
                    put(JSONObject().apply {
                        put("word", v.word)
                        put("definition", v.definition)
                        put("dateAdded", v.dateAdded)
                    })
                }
            })
        }

        val file = File(context.getExternalFilesDir(null), "calmlib_backup_${System.currentTimeMillis()}.json")
        file.writeText(json.toString(2))
        file
    }

    suspend fun importKoReaderProgress(file: File) = withContext(Dispatchers.IO) {
        try {
            val text = file.readText()
            val json = JSONObject(text)
            val db = AppDatabase.get(context)

            // KOReader stores progress in sidecar .sdr directories
            // This handles the simplified JSON export format
            if (json.has("title") && json.has("page")) {
                val title = json.getString("title")
                val page = json.getInt("page")
                val books = db.bookDao().allByTitle().first()
                books.find { it.title.equals(title, ignoreCase = true) }?.let { book ->
                    db.bookDao().updateProgress(book.id, page, 0, 0f, bookProgress = 0f)
                }
            }
        } catch (_: Exception) { }
    }
}
