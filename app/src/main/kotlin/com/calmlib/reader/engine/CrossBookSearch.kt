package com.calmlib.reader.engine

import android.content.Context
import com.calmlib.reader.data.db.AppDatabase
import com.calmlib.reader.data.model.Book
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Cross-book search across metadata, highlights, bookmarks and vocabulary —
 * everything that's already indexed in the database. We deliberately don't scan
 * book content here because re-parsing every book on a Mudita-class device would
 * lock up the UI for minutes.
 */
class CrossBookSearch(private val context: Context) {

    enum class HitType { BOOK_TITLE, BOOK_AUTHOR, HIGHLIGHT, BOOKMARK, VOCABULARY }

    data class Hit(
        val type: HitType,
        val book: Book?,
        val snippet: String,
        val page: Int? = null,
        val chapter: Int? = null,
    )

    suspend fun search(query: String, limit: Int = 100): List<Hit> = withContext(Dispatchers.IO) {
        if (query.length < 2) return@withContext emptyList()
        val q = query.lowercase()
        val db = AppDatabase.get(context)
        val books = db.bookDao().allByTitle().first()
        val bookById = books.associateBy { it.id }
        val hits = mutableListOf<Hit>()

        for (b in books) {
            if (b.title.lowercase().contains(q)) hits.add(Hit(HitType.BOOK_TITLE, b, b.title))
            if (b.author.isNotEmpty() && b.author.lowercase().contains(q))
                hits.add(Hit(HitType.BOOK_AUTHOR, b, b.author))
            if (hits.size >= limit) return@withContext hits
        }

        // Highlights search — pull all matching, attach to their book.
        val highlights = db.highlightDao().searchHighlights("%$q%")
        for (h in highlights) {
            val b = bookById[h.bookId] ?: continue
            hits.add(Hit(HitType.HIGHLIGHT, b, h.text, h.page, h.chapter))
            if (hits.size >= limit) return@withContext hits
        }

        // Vocabulary
        val vocab = db.highlightDao().searchVocabulary("%$q%")
        for (v in vocab) {
            val b = v.bookId?.let { bookById[it] }
            hits.add(Hit(HitType.VOCABULARY, b, "${v.word} — ${v.definition.take(80)}"))
            if (hits.size >= limit) return@withContext hits
        }

        // Bookmarks
        val bookmarks = db.bookmarkDao().searchBookmarks("%$q%")
        for (bm in bookmarks) {
            val b = bookById[bm.bookId] ?: continue
            hits.add(Hit(HitType.BOOKMARK, b, bm.label, bm.page, bm.chapter))
            if (hits.size >= limit) return@withContext hits
        }

        hits
    }
}
