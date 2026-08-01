package com.calmlib.reader.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import com.calmlib.reader.data.db.AppDatabase
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookCollection
import com.calmlib.reader.data.model.BookFormat
import com.calmlib.reader.data.model.Bookmark
import com.calmlib.reader.data.model.Collection
import com.calmlib.reader.data.model.SortField
import com.calmlib.reader.engine.EpubParser
import com.calmlib.reader.engine.Fb2Parser
import com.calmlib.reader.engine.TxtEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import com.calmlib.reader.util.readUpTo

class BookRepository(private val context: Context) {
    private val db = AppDatabase.get(context)
    private val bookDao = db.bookDao()
    private val collectionDao = db.collectionDao()
    private val bookmarkDao = db.bookmarkDao()

    private val booksDir = File(context.filesDir, "books").also { it.mkdirs() }
    private val coversDir = File(context.filesDir, "covers").also { it.mkdirs() }

    fun books(sort: SortField): Flow<List<Book>> = when (sort) {
        SortField.TITLE -> bookDao.allByTitle()
        SortField.AUTHOR -> bookDao.allByAuthor()
        SortField.DATE_ADDED -> bookDao.allByDateAdded()
        SortField.LAST_READ -> bookDao.allByLastRead()
        SortField.FORMAT -> bookDao.allByFormat()
    }

    fun currentlyReading(): Flow<List<Book>> = bookDao.currentlyReading()

    fun search(query: String): Flow<List<Book>> = bookDao.search(query)

    fun booksInCollection(collectionId: Long): Flow<List<Book>> =
        bookDao.booksInCollection(collectionId)

    suspend fun getBook(id: Long): Book? = bookDao.getById(id)

    suspend fun bookByPath(path: String): Book? = bookDao.getByPath(path)

    suspend fun importFromFile(file: File): Book? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) return@withContext null
            val format = detectFormatByName(file.name) ?: return@withContext null

            bookDao.getByPath(file.absolutePath)?.let { return@withContext it }

            var title = file.nameWithoutExtension
            var author = ""
            var coverBitmap: Bitmap? = null

            when (format) {
                BookFormat.EPUB -> try {
                    val parser = EpubParser(file)
                    title = parser.title.ifEmpty { title }
                    author = parser.author
                    coverBitmap = parser.extractCover()
                    parser.close()
                } catch (_: Exception) { }

                BookFormat.FB2 -> try {
                    val parser = Fb2Parser(file)
                    title = parser.title.ifEmpty { title }
                    author = parser.author
                    coverBitmap = parser.extractCover()
                } catch (_: Exception) { }

                BookFormat.TXT -> try {
                    val engine = TxtEngine(file)
                    title = engine.title.ifEmpty { title }
                } catch (_: Exception) { }

                else -> { }
            }

            // Content-based dedup so a manually imported book + a scanned one
            // at a different path don't both register.
            bookDao.getByFingerprint(title, author, file.length())?.let { return@withContext it }

            val coverName = "scan_${file.absolutePath.hashCode().toUInt()}"
            val coverPath = coverBitmap?.let { saveCover(it, coverName) }
                ?: generateTextCover(title, author, coverName)

            val book = Book(
                title = title,
                author = author,
                format = format,
                filePath = file.absolutePath,
                coverPath = coverPath,
                fileSize = file.length(),
            )
            val id = bookDao.insert(book)
            book.copy(id = id)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun importBook(uri: Uri): Book? = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return@withContext null

            // Copy the bytes FIRST, then detect the format. Mime/extension detection
            // fails on the opaque content:// URIs some file managers hand out, and
            // in that case we fall back to sniffing the file's magic bytes — so a
            // real EPUB/PDF imports fine even when it's served as octet-stream
            // with no extension in the path.
            val tempFile = File.createTempFile("import_", ".bin", context.cacheDir)
            tempFile.outputStream().use { out -> inputStream.copyTo(out) }
            val format = detectFormat(uri) ?: sniffFormat(tempFile) ?: run {
                tempFile.delete()
                return@withContext null
            }
            val ext = format.name.lowercase()

            var title = displayNameFor(uri) ?: tempFile.nameWithoutExtension
            var author = ""
            var coverBitmap: Bitmap? = null

            when (format) {
                BookFormat.EPUB -> try {
                    val parser = EpubParser(tempFile)
                    title = parser.title.ifEmpty { title }
                    author = parser.author
                    coverBitmap = parser.extractCover()
                    parser.close()
                } catch (_: Exception) { }

                BookFormat.FB2 -> try {
                    val parser = Fb2Parser(tempFile)
                    title = parser.title.ifEmpty { title }
                    author = parser.author
                    coverBitmap = parser.extractCover()
                } catch (_: Exception) { }

                BookFormat.TXT -> try {
                    val engine = TxtEngine(tempFile)
                    title = engine.title.ifEmpty { title }
                } catch (_: Exception) { }

                else -> { }
            }

            // Content-based dedup — if we already have this exact book from a scan,
            // return it instead of copying again.
            bookDao.getByFingerprint(title, author, tempFile.length())?.let {
                tempFile.delete()
                return@withContext it
            }

            val destFile = File(booksDir, "${System.currentTimeMillis()}_${sanitize(title)}.$ext")
            tempFile.copyTo(destFile, overwrite = true)
            tempFile.delete()

            val coverPath = coverBitmap?.let { saveCover(it, destFile.nameWithoutExtension) }
                ?: generateTextCover(title, author, destFile.nameWithoutExtension)

            val book = Book(
                title = title,
                author = author,
                format = format,
                filePath = destFile.absolutePath,
                coverPath = coverPath,
                fileSize = destFile.length(),
            )
            val id = bookDao.insert(book)
            book.copy(id = id)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun updateProgress(bookId: Long, page: Int, chapter: Int, offset: Float, bookProgress: Float = 0f) {
        bookDao.updateProgress(bookId, page, chapter, offset, bookProgress)
    }

    suspend fun updateTotalPages(bookId: Long, total: Int) {
        bookDao.updateTotalPages(bookId, total)
    }

    suspend fun saveReadingSettings(bookId: Long, json: String?) =
        bookDao.updateReadingSettingsJson(bookId, json)

    /**
     * Rename a book in the library. Updates title/author in the DB and regenerates
     * the typeset cover if the existing cover looks like one we generated (240×360
     * is our typeset cover dimension). Real EPUB/FB2 covers are left alone.
     */
    suspend fun renameBook(book: Book, newTitle: String, newAuthor: String) = withContext(Dispatchers.IO) {
        val title = newTitle.trim().ifBlank { book.title }
        val author = newAuthor.trim()
        bookDao.renameBook(book.id, title, author)

        // Try to detect a typeset cover by its dimensions, then re-render with the new
        // title + author. Cheap heuristic — if the cover happens to be 240×360 it's
        // a typeset one. EPUB covers are usually 600×900-ish.
        try {
            val coverPath = book.coverPath ?: return@withContext
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeFile(coverPath, opts)
            if (opts.outWidth == 240 && opts.outHeight == 360) {
                val name = File(coverPath).nameWithoutExtension
                generateTextCover(title, author, name)
            }
        } catch (_: Exception) { /* leave the cover alone if anything fails */ }
    }

    /**
     * Convert a PDF book to a brand-new EPUB. The EPUB lands in the same internal
     * books folder, gets indexed via [importFromFile] (which produces metadata + a
     * cover), and is returned so callers can navigate to it. The original PDF is
     * left untouched.
     */
    suspend fun convertPdfToEpub(
        book: Book,
        onProgress: (com.calmlib.reader.engine.PdfToEpubConverter.Progress) -> Unit = {},
    ): Result<Book> = withContext(Dispatchers.IO) {
        if (book.format != BookFormat.PDF) {
            return@withContext Result.failure(IllegalArgumentException("Not a PDF"))
        }
        val src = File(book.filePath)
        if (!src.exists()) return@withContext Result.failure(IllegalStateException("PDF file missing"))
        val converter = com.calmlib.reader.engine.PdfToEpubConverter(context)
        when (val result = converter.convert(src, booksDir, onProgress)) {
            is com.calmlib.reader.engine.PdfToEpubConverter.Result.Failure ->
                Result.failure(RuntimeException(result.message))
            is com.calmlib.reader.engine.PdfToEpubConverter.Result.Success -> {
                val imported = importFromFile(result.epub)
                if (imported != null) Result.success(imported)
                else Result.failure(RuntimeException("Couldn't import the converted EPUB"))
            }
        }
    }

    suspend fun markFinished(bookId: Long) = bookDao.markFinished(bookId)
    suspend fun removeFromCurrentlyReading(bookId: Long) = bookDao.removeFromCurrentlyReading(bookId)

    /**
     * Wipes the library and everything tied to specific books: all Book rows
     * (which cascades to bookmarks, highlights, and book↔collection mappings),
     * all reading sessions, all vocabulary entries, the on-disk book files in
     * /files/books, and the generated covers in /files/covers.
     *
     * KEPT: collections (empty, but the names survive), the bundled dictionary
     * in /files/dictionaries, app-level preferences (sort, filter), and the
     * book-scan first-run flag.
     */
    suspend fun resetLibrary() = withContext(Dispatchers.IO) {
        db.statsDao().deleteAll()
        db.highlightDao().deleteAllVocabulary()
        bookDao.deleteAll()
        // Wipe the on-disk files we created when importing.
        try { booksDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
        try { coversDir.listFiles()?.forEach { it.delete() } } catch (_: Exception) {}
    }

    suspend fun deleteBook(book: Book) = withContext(Dispatchers.IO) {
        // Only delete files WE created (copies inside our internal books dir).
        // Scanned books point at the user's own files in /sdcard/Books etc. —
        // removing a book from the library must never destroy their only copy.
        val file = File(book.filePath)
        if (file.absolutePath.startsWith(booksDir.absolutePath)) {
            file.delete()
        }
        book.coverPath?.let { File(it).delete() }
        bookDao.delete(book)
    }

    fun collections(): Flow<List<Collection>> = collectionDao.all()

    fun collectionsForBook(bookId: Long): Flow<List<Long>> =
        collectionDao.collectionsForBook(bookId)

    suspend fun createCollection(name: String): Long =
        collectionDao.insert(Collection(name = name))

    suspend fun deleteCollection(collection: Collection) =
        collectionDao.delete(collection)

    suspend fun addToCollection(bookId: Long, collectionId: Long) =
        collectionDao.addBookToCollection(BookCollection(bookId, collectionId))

    suspend fun removeFromCollection(bookId: Long, collectionId: Long) =
        collectionDao.removeBookFromCollection(BookCollection(bookId, collectionId))

    fun bookmarksForBook(bookId: Long): Flow<List<Bookmark>> =
        bookmarkDao.forBook(bookId)

    suspend fun addBookmark(bookId: Long, page: Int, chapter: Int, label: String): Long =
        bookmarkDao.insert(Bookmark(bookId = bookId, page = page, chapter = chapter, label = label))

    suspend fun deleteBookmark(bookmark: Bookmark) = bookmarkDao.delete(bookmark)

    private fun detectFormatByName(name: String): BookFormat? {
        val lower = name.lowercase()
        return when {
            lower.endsWith(".epub") -> BookFormat.EPUB
            lower.endsWith(".pdf") -> BookFormat.PDF
            lower.endsWith(".txt") -> BookFormat.TXT
            lower.endsWith(".fb2") -> BookFormat.FB2
            else -> null
        }
    }

    /**
     * Magic-byte detection for files whose URI carries no usable mime type or
     * extension. ZIP ("PK") is treated as EPUB (the only zip container we read),
     * "%PDF" is PDF, XML with a FictionBook root is FB2, and anything that looks
     * like printable text becomes TXT.
     */
    private fun sniffFormat(file: File): BookFormat? {
        return try {
            val head = file.inputStream().use { it.readUpTo(4096) }
            if (head.size < 4) return null
            when {
                head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() -> BookFormat.EPUB
                head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() &&
                    head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte() -> BookFormat.PDF
                else -> {
                    val text = String(head, Charsets.UTF_8)
                    when {
                        text.contains("<FictionBook", ignoreCase = true) -> BookFormat.FB2
                        // Heuristic for plain text: mostly printable, no NUL bytes
                        head.none { it == 0.toByte() } &&
                            head.count { it in 32..126 || it == 9.toByte() || it == 10.toByte() || it == 13.toByte() } > head.size * 0.85 -> BookFormat.TXT
                        else -> null
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Human filename from a content:// URI (OpenableColumns), extension stripped. */
    private fun displayNameFor(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && cursor.moveToFirst()) {
                    cursor.getString(idx)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
                } else null
            } ?: uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    private fun detectFormat(uri: Uri): BookFormat? {
        val mimeType = context.contentResolver.getType(uri)
        val path = uri.path ?: ""
        return when {
            mimeType == "application/epub+zip" || path.endsWith(".epub", true) -> BookFormat.EPUB
            mimeType == "application/pdf" || path.endsWith(".pdf", true) -> BookFormat.PDF
            mimeType == "text/plain" || path.endsWith(".txt", true) -> BookFormat.TXT
            path.endsWith(".fb2", true) || mimeType?.contains("fictionbook") == true -> BookFormat.FB2
            else -> null
        }
    }

    private fun saveCover(bitmap: Bitmap, name: String): String {
        val file = File(coversDir, "$name.png")
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
        return file.absolutePath
    }

    private fun generateTextCover(title: String, author: String, name: String): String {
        val w = 240
        val h = 360
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)

        val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = 22f
            typeface = Typeface.SERIF
        }
        val authorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = 14f
            typeface = Typeface.SERIF
        }
        val linePaint = Paint().apply {
            color = Color.BLACK
            strokeWidth = 1.5f
        }

        val margin = 20f
        val maxWidth = w - margin * 2

        var y = 80f
        for (line in wrapText(title, titlePaint, maxWidth)) {
            canvas.drawText(line, margin, y, titlePaint)
            y += titlePaint.textSize * 1.3f
        }

        y += 12f
        canvas.drawLine(margin, y, margin + 60, y, linePaint)
        y += 24f

        if (author.isNotEmpty()) {
            for (line in wrapText(author, authorPaint, maxWidth)) {
                canvas.drawText(line, margin, y, authorPaint)
                y += authorPaint.textSize * 1.4f
            }
        }

        return saveCover(bitmap, name)
    }

    private fun wrapText(text: String, paint: Paint, maxWidth: Float): List<String> {
        val words = text.split(" ")
        val lines = mutableListOf<String>()
        var current = ""
        for (word in words) {
            val test = if (current.isEmpty()) word else "$current $word"
            if (paint.measureText(test) <= maxWidth) {
                current = test
            } else {
                if (current.isNotEmpty()) lines.add(current)
                current = word
            }
        }
        if (current.isNotEmpty()) lines.add(current)
        return lines
    }

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._-]"), "_").take(60)
}
