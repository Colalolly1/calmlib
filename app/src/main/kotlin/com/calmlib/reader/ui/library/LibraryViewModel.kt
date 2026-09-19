package com.calmlib.reader.ui.library

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.Collection
import com.calmlib.reader.data.model.SortField
import com.calmlib.reader.data.repository.BookRepository
import com.calmlib.reader.data.repository.SettingsRepository
import com.calmlib.reader.engine.BackupManager
import com.calmlib.reader.engine.BookScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val bookRepo = BookRepository(app)
    private val settingsRepo = SettingsRepository(app)
    private val scanner = BookScanner(app, bookRepo)

    private val _scanStatus = MutableStateFlow<String?>(null)
    val scanStatus: StateFlow<String?> = _scanStatus

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning

    private val _conversionStatus = MutableStateFlow<String?>(null)
    val conversionStatus: StateFlow<String?> = _conversionStatus

    private val _isConverting = MutableStateFlow(false)
    val isConverting: StateFlow<Boolean> = _isConverting

    val sortField: StateFlow<SortField> = settingsRepo.sortField
        .stateIn(viewModelScope, SharingStarted.Eagerly, SortField.TITLE)

    /** Active format filters; empty set = show everything. */
    val formatFilters: StateFlow<Set<String>> = settingsRepo.formatFilters
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val books: StateFlow<List<Book>> = combine(sortField, formatFilters) { s, f -> s to f }
        .flatMapLatest { (s, f) ->
            bookRepo.books(s).map { list ->
                val shown = if (f.isEmpty()) list else list.filter { it.format.name in f }
                // Sort by the tidied title so "328186006-Secret-of-…" files the
                // way a librarian would, not by the store number.
                if (s == SortField.TITLE) shown.sortedBy { it.displayTitle.lowercase() } else shown
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Every book, newest first, regardless of filter — feeds counts and the home shelves. */
    val allBooks: StateFlow<List<Book>> = bookRepo.books(SortField.DATE_ADDED)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** How many books of each format the whole library holds, ignoring the filter. */
    val formatCounts: StateFlow<Map<String, Int>> = allBooks
        .map { list -> list.groupingBy { it.format.name }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /**
     * One book you have never opened, chosen fresh each day — the librarian
     * leaving something on the counter for you. Same pick all day, so it
     * doesn't shuffle every time the screen redraws.
     */
    val todaysPick: StateFlow<Book?> = allBooks
        .map { list ->
            val unopened = list.filter { it.lastRead == 0L && it.progress == 0f && !it.isCurrentlyReading }
                .sortedBy { it.id }
            if (unopened.isEmpty()) null
            else unopened[(java.time.LocalDate.now().toEpochDay() % unopened.size).toInt()]
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Books added in the last fortnight, newest first, at most two shelves' worth. */
    val newArrivals: StateFlow<List<Book>> = allBooks
        .map { list ->
            val cutoff = System.currentTimeMillis() - 14L * 24 * 60 * 60 * 1000
            list.filter { it.dateAdded > cutoff }.sortedByDescending { it.dateAdded }.take(4)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val currentlyReading: StateFlow<List<Book>> = bookRepo.currentlyReading()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val pinnedIds: StateFlow<List<Long>> = settingsRepo.pinnedBooks
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /**
     * The reader's own little bookshelf at the top of the library: the books
     * they pinned, in pin order, then anything they're currently reading that
     * isn't pinned yet.
     */
    val myShelf: StateFlow<List<Book>> = combine(allBooks, pinnedIds, currentlyReading) { all, pins, reading ->
        val byId = all.associateBy { it.id }
        val pinned = pins.mapNotNull { byId[it] }
        pinned + reading.filter { it.id !in pins }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    fun togglePin(book: Book) {
        viewModelScope.launch {
            val pins = pinnedIds.value
            settingsRepo.setPinnedBooks(if (book.id in pins) pins - book.id else pins + book.id)
        }
    }


    val collections: StateFlow<List<Collection>> = bookRepo.collections()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    val searchResults: StateFlow<List<Book>> = _searchQuery
        .debounce(200)
        .flatMapLatest { query ->
            if (query.length < 2) flowOf(emptyList())
            else bookRepo.search(query)
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _selectedCollectionId = MutableStateFlow<Long?>(null)
    val selectedCollectionId: StateFlow<Long?> = _selectedCollectionId

    val collectionBooks: StateFlow<List<Book>> = _selectedCollectionId
        .flatMapLatest { id ->
            if (id != null) bookRepo.booksInCollection(id)
            else flowOf(emptyList())
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isSearching: Boolean get() = _searchQuery.value.isNotEmpty()

    val displayedBooks: List<Book>
        get() = when {
            _searchQuery.value.length >= 2 -> searchResults.value
            _selectedCollectionId.value != null -> collectionBooks.value
            else -> books.value
        }

    fun setSearch(query: String) { _searchQuery.value = query }

    fun setSortField(field: SortField) {
        viewModelScope.launch { settingsRepo.setSortField(field) }
    }

    fun selectCollection(id: Long?) { _selectedCollectionId.value = id }

    fun importBook(uri: Uri) {
        viewModelScope.launch {
            val imported = bookRepo.importBook(uri)
            if (imported != null) {
                // If an active format filter would HIDE the book that was just
                // added, drop the filter — nothing is more confusing than adding
                // a book and not seeing it appear.
                if (formatFilters.value.isNotEmpty() && imported.format.name !in formatFilters.value) {
                    settingsRepo.setFormatFilters(emptySet())
                }
                _scanStatus.value = "Added “${imported.title}” to your library."
            } else {
                _scanStatus.value = "Couldn't add that file — it may be corrupt, password-protected, or an unsupported format. CalmLib reads EPUB, PDF, TXT and FB2."
            }
        }
    }

    fun deleteBook(book: Book) = deleteBooks(listOf(book))

    /**
     * Remove books from the library. Their source paths go on the scanner's
     * ignore list so the quiet auto-rescan can't resurrect them; external files
     * in the user's Books folder are never deleted (only CalmLib's own copies).
     */
    fun deleteBooks(booksToDelete: List<Book>) {
        if (booksToDelete.isEmpty()) return
        viewModelScope.launch {
            settingsRepo.addIgnoredScanPaths(booksToDelete.map { it.filePath })
            val gone = booksToDelete.map { it.id }.toSet()
            if (pinnedIds.value.any { it in gone }) settingsRepo.setPinnedBooks(pinnedIds.value.filter { it !in gone })
            booksToDelete.forEach { bookRepo.deleteBook(it) }
            _scanStatus.value = if (booksToDelete.size == 1) {
                "Removed “${booksToDelete.first().title}” from your library."
            } else {
                "Removed ${booksToDelete.size} books from your library."
            }
        }
    }

    fun createCollection(name: String) {
        viewModelScope.launch { bookRepo.createCollection(name) }
    }

    fun deleteCollection(collection: Collection) {
        viewModelScope.launch { bookRepo.deleteCollection(collection) }
    }

    fun scanForBooks() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            _scanStatus.value = "Scanning Books / Ebooks / Reading folders…"
            try {
                val result = scanner.scan()
                _scanStatus.value = when {
                    result.imported == 0 && result.skipped == 0 ->
                        "No book files found in /Books, /Ebooks or /Reading. Move your books into one of those folders, or use Add to pick a single file."
                    result.imported == 0 -> "Library is up to date — nothing new."
                    else -> "Imported ${result.imported} book${if (result.imported == 1) "" else "s"}."
                }
            } catch (e: Exception) {
                _scanStatus.value = "Scan failed: ${e.message}"
            } finally {
                _isScanning.value = false
            }
        }
    }

    fun clearScanStatus() { _scanStatus.value = null }

    /**
     * Background rescan of the dedicated book folders (/Books, /Ebooks, /Reading…)
     * that stays SILENT unless it actually finds something new. Runs on every
     * app resume so a book copied onto the phone appears without a manual scan.
     * Safe against the old duplicate/pollution problems because the scanner no
     * longer touches Downloads/Documents and dedups by path AND fingerprint.
     */
    fun scanForBooksQuietly() {
        if (_isScanning.value) return
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val result = scanner.scan()
                if (result.imported > 0) {
                    _scanStatus.value = "Found ${result.imported} new book${if (result.imported == 1) "" else "s"} on your device."
                }
            } catch (_: Exception) {
                // quiet by design
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** Show one format only (Kindle-style tabs); null shows everything. */
    fun setFormatFilter(format: String?) {
        viewModelScope.launch {
            settingsRepo.setFormatFilters(if (format == null) emptySet() else setOf(format))
        }
    }

    fun toggleFormatFilter(format: String) {
        viewModelScope.launch {
            val current = formatFilters.value
            settingsRepo.setFormatFilters(if (format in current) current - format else current + format)
        }
    }

    fun clearFormatFilters() {
        viewModelScope.launch { settingsRepo.setFormatFilters(emptySet()) }
    }

    fun markBookFinished(book: Book) {
        viewModelScope.launch { bookRepo.markFinished(book.id) }
    }

    fun removeFromCurrentlyReading(book: Book) {
        viewModelScope.launch { bookRepo.removeFromCurrentlyReading(book.id) }
    }

    fun addBookToCollection(bookId: Long, collectionId: Long) {
        viewModelScope.launch { bookRepo.addToCollection(bookId, collectionId) }
    }

    fun convertPdfToEpub(book: Book) {
        if (_isConverting.value) return
        viewModelScope.launch {
            _isConverting.value = true
            _conversionStatus.value = "Converting \"${book.title}\" to EPUB…"
            val result = bookRepo.convertPdfToEpub(book) { p ->
                _conversionStatus.value = if (p.phase == "Extracting text")
                    "Extracting text · page ${p.pagesProcessed}/${p.totalPages}"
                else "Building EPUB…"
            }
            _isConverting.value = false
            _conversionStatus.value = result.fold(
                onSuccess = { converted -> "Added \"${converted.title}\" as EPUB" },
                onFailure = { e -> "Couldn't convert: ${e.message}" },
            )
        }
    }

    fun clearConversionStatus() { _conversionStatus.value = null }

    fun renameBook(book: Book, newTitle: String, newAuthor: String) {
        viewModelScope.launch { bookRepo.renameBook(book, newTitle, newAuthor) }
    }

    fun resetLibrary(onDone: () -> Unit = {}) {
        viewModelScope.launch {
            bookRepo.resetLibrary()
            // Fresh start also forgets which files were deliberately deleted, so a
            // rescan can rebuild the library from everything on disk.
            settingsRepo.clearIgnoredScanPaths()
            _scanStatus.value = "Library cleared. Tap Scan device for books to rebuild."
            onDone()
        }
    }

    /**
     * Checks if it's been more than 7 days since the last automatic backup and runs
     * one if so. Backups land in the app's external storage so they survive uninstalls.
     */
    /** Swap old baked-in placeholder covers for real PDF first pages, once. */
    fun repairCoversOnce() {
        viewModelScope.launch {
            if (settingsRepo.coversRepaired()) return@launch
            val n = bookRepo.repairPlaceholderCovers()
            settingsRepo.setCoversRepaired()
            if (n > 0) _scanStatus.value = "Refreshed covers for $n book${if (n == 1) "" else "s"}."
        }
    }

    fun maybeRunWeeklyBackup() {
        viewModelScope.launch {
            val last = settingsRepo.lastAutoBackup.first()
            val now = System.currentTimeMillis()
            val sevenDays = 7L * 24 * 60 * 60 * 1000
            if (now - last < sevenDays) return@launch
            try {
                withContext(Dispatchers.IO) {
                    BackupManager(getApplication()).exportBackup()
                }
                settingsRepo.setLastAutoBackup(now)
            } catch (_: Exception) { /* ignore — backup is best effort */ }
        }
    }
}
