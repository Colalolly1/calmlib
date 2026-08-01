package com.calmlib.reader.ui.reader

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import com.calmlib.reader.data.db.AppDatabase
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookFormat
import com.calmlib.reader.data.model.Bookmark
import com.calmlib.reader.data.model.Highlight
import com.calmlib.reader.data.model.ReadingSettings
import com.calmlib.reader.data.model.VocabularyEntry
import com.calmlib.reader.data.repository.BookRepository
import com.calmlib.reader.eink.EinkRefresh
import com.calmlib.reader.engine.DictionaryEngine
import com.calmlib.reader.engine.EpubParser
import com.calmlib.reader.engine.PdfEngine
import com.calmlib.reader.ui.theme.CalmLibTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class ReaderActivity : ComponentActivity() {

    companion object {
        const val EXTRA_BOOK_ID = "book_id"
        const val EXTRA_FILE_PATH = "file_path"
        /** Chapter render target, written inside the book's extraction dir. */
        private const val RENDER_FILE = ".calm_render.xhtml"
    }

    private lateinit var bookRepo: BookRepository
    private lateinit var dictEngine: DictionaryEngine
    private var webView: WebView? = null
    private var pdfView: PdfReaderView? = null

    /** Bumped when a dead WebView must be replaced — keyed into the composition
     *  so Compose disposes the old AndroidView and runs the factory again.
     *  Rebuilding just the WebView (instead of recreate()) keeps parsers, the
     *  in-memory position, and the activity alive. */
    private val webViewEpoch = androidx.compose.runtime.mutableIntStateOf(0)

    /** Timestamps of recent renderer deaths; caps recovery so a book whose load
     *  itself kills the renderer can't put the reader in an infinite
     *  rebuild-reload-die loop. */
    private val rendererDeaths = ArrayDeque<Long>()

    /** Recoveries since content last actually rendered (reset on a real page
     *  event) — the absolute backstop the sliding window can't provide. */
    private var rendererRecoveryTotal = 0

    /** Last forced image-page refresh (debounces the JS bridge signal). */
    private var lastImageFlashMs = 0L

    /** Last accepted next/prev page turn. E-ink digitizers double-report taps,
     *  and the slow image paint invites "did it turn?" re-taps — either one
     *  advanced PAST a picture page. One turn per 350ms is faster than anyone
     *  reads and slower than any bounce. */
    private var lastPageTurnMs = 0L

    private fun pageTurnDebounced(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastPageTurnMs < 350) {
            android.util.Log.d("CalmNav", "page turn debounced (${now - lastPageTurnMs}ms since last)")
            return true
        }
        lastPageTurnMs = now
        return false
    }

    /** True once the CURRENT load has actually paginated and reported a page.
     *  saveProgress must not run before then: onPause fires during any teardown,
     *  and saving page 0 / charOffset 0 for a book that never finished loading
     *  overwrites the user's real position with garbage. */
    @Volatile private var hasLiveContent = false
    private var book: Book? = null
    private var epubParser: EpubParser? = null
    private var fb2Parser: com.calmlib.reader.engine.Fb2Parser? = null
    private var pdfEngine: PdfEngine? = null
    private var epubDir: File? = null
    private var currentChapter = 0
    private var totalChapters = 0

    /**
     * Byte/char size per chapter (EPUB spine entry sizes, FB2 section HTML
     * lengths). Used to weight whole-book progress: "page 3 of 14 in chapter 2"
     * becomes a fraction of the entire book, so covers, the footer percentage
     * and time-remaining stop being chapter-scoped lies.
     */
    private var chapterWeights: List<Long> = emptyList()

    /** Whole-book progress 0..1 for the current position. */
    private fun bookProgressFraction(): Float {
        val pages = totalPages.intValue
        val pageFrac = if (pages > 0) (currentPage.intValue + 1).toFloat() / pages else 0f
        if (isPdf.value) return pageFrac.coerceIn(0f, 1f)
        val weights = chapterWeights
        if (weights.size <= 1 || totalChapters <= 1) return pageFrac.coerceIn(0f, 1f)
        val total = weights.sum().coerceAtLeast(1L).toFloat()
        val chapter = currentChapter.coerceIn(0, weights.size - 1)
        val before = weights.take(chapter).sum().toFloat()
        val current = weights[chapter].toFloat()
        return ((before + current * pageFrac) / total).coerceIn(0f, 1f)
    }

    /**
     * Rough whole-book page count, extrapolated from the current chapter's page
     * count and its share of the book by weight. Only used for the time-left
     * estimate; never persisted. Returns 0 ("unknown") when the current chapter
     * is a sliver of the book (< 2% by weight) — extrapolating from a 1-page
     * title page produces absurd numbers, and showing nothing beats lying.
     */
    private fun estimatedBookPages(): Int {
        val pages = totalPages.intValue
        if (isPdf.value || chapterWeights.size <= 1 || totalChapters <= 1) return pages
        val total = chapterWeights.sum().coerceAtLeast(1L).toFloat()
        val chapter = currentChapter.coerceIn(0, chapterWeights.size - 1)
        val current = chapterWeights[chapter].toFloat().coerceAtLeast(1f)
        if (current / total < 0.02f) return 0
        return (pages * total / current).toInt().coerceAtLeast(pages)
    }

    /** Multi-chapter formats show whole-book % next to the chapter-local page. */
    private fun isMultiChapter(): Boolean = !isPdf.value && totalChapters > 1
    private var sessionId: Long = 0
    private var sessionStartPage: Int = 0

    private var currentPage = mutableIntStateOf(0)
    private var totalPages = mutableIntStateOf(1)
    private var showControls = mutableStateOf(false)
    private var showToc = mutableStateOf(false)
    private var showBookmarks = mutableStateOf(false)
    private var showSearch = mutableStateOf(false)
    private var showHighlights = mutableStateOf(false)
    private var showDictionary = mutableStateOf(false)
    private var settings = mutableStateOf(ReadingSettings())
    private var bookmarks = mutableStateOf<List<Bookmark>>(emptyList())
    private var highlights = mutableStateOf<List<Highlight>>(emptyList())
    private var dictResult = mutableStateOf<String?>(null)
    /** Transient, dismissable banner. Distinct from errorMessage, which
     *  REPLACES the reader — only unrecoverable load failures deserve that. */
    private var noticeMessage = mutableStateOf<String?>(null)
    private var dictWord = mutableStateOf("")
    private var searchResults = mutableStateOf<List<Int>>(emptyList())
    private var searchSnippets = mutableStateOf<List<Pair<Int, String>>>(emptyList())
    private var errorMessage = mutableStateOf<String?>(null)
    private var isPdf = mutableStateOf(false)

    private var pendingSelection = mutableStateOf<String?>(null)
    private var showSelectionMenu = mutableStateOf(false)
    private var fullscreenImageUrl = mutableStateOf<String?>(null)

    /**
     * Guest preview: files opened from OUTSIDE CalmLib (file manager, email…)
     * are read from a cache copy and NEVER auto-imported. The reader works
     * normally except nothing is persisted — no library entry, no progress, no
     * highlights — unless the user taps "Add to library". This is what keeps
     * random work PDFs off the shelves.
     */
    private var isGuest = mutableStateOf(false)
    private var guestSourceUri: Uri? = null

    /** Set by the JS bridge when a tap landed on an <img>. The native tap-zone
     *  handler defers its action briefly and skips it when this is fresh, so
     *  touching a picture opens the image viewer WITHOUT also turning the page. */
    @Volatile private var lastImageTapMs = 0L

    /** Chapter-local character offset of the text at the top of the screen,
     *  pushed by the JS bridge on every page change. This — not the page index —
     *  is the durable reading position that gets persisted and restored. */
    @Volatile private var lastCharOffset = -1

    /** Page-change events since the last autosave; position is flushed to the DB
     *  every few turns so even a crash/kill can't lose more than a few pages. */
    private var pageEventsSinceSave = 0

    private fun maybeAutosave() {
        if (++pageEventsSinceSave >= 5) {
            pageEventsSinceSave = 0
            saveProgress()
        }
    }

    /** The current book only when it's a real library row — null for guest
     *  previews (id == 0), so persistence sites can simply no-op. */
    private fun persistableBook(): Book? = book?.takeIf { it.id > 0 }

    private var trackingStarted = false

    /**
     * Start the reading session and the bookmark/highlight Flow collectors for a
     * library book. Called from onCreate for normal opens AND from
     * addGuestToLibrary after a preview is promoted — without the second call
     * site, highlights added right after promotion would persist but never show
     * up in the overlays until the book was reopened.
     */
    private fun startLibraryTracking(b: Book, startPage: Int) {
        if (trackingStarted || b.id <= 0) return
        trackingStarted = true
        sessionStartPage = startPage
        lifecycleScope.launch {
            sessionId = AppDatabase.get(this@ReaderActivity).statsDao()
                .insert(com.calmlib.reader.data.model.ReadingSession(bookId = b.id, startTime = System.currentTimeMillis()))
        }
        lifecycleScope.launch { bookRepo.bookmarksForBook(b.id).collect { bookmarks.value = it } }
        lifecycleScope.launch { AppDatabase.get(this@ReaderActivity).highlightDao().forBook(b.id).collect { highlights.value = it } }
    }
    private var avgPagesPerMinute = mutableStateOf(0f)
    private var isSpeaking = mutableStateOf(false)
    private var lastInteractionTs = mutableStateOf(System.currentTimeMillis())
    private var tts: android.speech.tts.TextToSpeech? = null
    private var idleHandler: android.os.Handler? = null
    private var idleRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enterFullscreen()

        bookRepo = BookRepository(this)
        dictEngine = DictionaryEngine.get(this)
        // Bundled WordNet is decompressed from assets to filesDir on first launch
        // (~30MB write). Must NOT block the main thread.
        lifecycleScope.launch(Dispatchers.IO) { dictEngine.ensureInstalled() }

        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val filePath = intent.getStringExtra(EXTRA_FILE_PATH)
        val intentUri = intent.data

        lifecycleScope.launch {
            // Settings are PER-BOOK (not global). They're loaded from the Book row
            // and only saved back to that row, so changes never bleed into another book.
            // IMPORTANT: settings must be applied BEFORE loadBook so the initial render
            // uses the correct font/spacing/etc — otherwise the WebView paints at default
            // settings then re-paints when settings arrive, causing a visible flicker.
            when {
                bookId > 0 -> {
                    book = bookRepo.getBook(bookId)
                    book?.let {
                        settings.value = ReadingSettings.fromJson(it.readingSettingsJson)
                        loadBook(it)
                    }
                }
                filePath != null -> {
                    book = bookRepo.getBook(bookId)
                    val b = book
                    if (b != null) {
                        settings.value = ReadingSettings.fromJson(b.readingSettingsJson)
                        loadBook(b)
                    } else {
                        val imported = importAndLoad(Uri.fromFile(File(filePath)))
                        // importAndLoad has already called loadBook with default settings;
                        // apply the imported book's settings now (will be defaults).
                        imported?.let { settings.value = ReadingSettings.fromJson(it.readingSettingsJson) }
                    }
                }
                intentUri != null -> {
                    // Opened from outside the app — preview, don't import.
                    val guest = prepareGuestBook(intentUri)
                    if (guest != null) {
                        isGuest.value = true
                        guestSourceUri = intentUri
                        book = guest
                        settings.value = ReadingSettings()
                        loadBook(guest)
                    } else {
                        errorMessage.value = "Couldn't open that file. CalmLib reads EPUB, PDF, TXT and FB2."
                    }
                }
                else -> errorMessage.value = "No book specified"
            }

            // Persistence side-effects only for real library books (id > 0). Guest
            // previews get none — that's the point of a preview.
            book?.let { b ->
                startLibraryTracking(b, startPage = b.currentPage)
                // Reading speed for time-remaining estimates — fall back to ~30s/page if no history
                val mins = AppDatabase.get(this@ReaderActivity).statsDao().totalMinutesRead().first().coerceAtLeast(1)
                val pages = AppDatabase.get(this@ReaderActivity).statsDao().totalPagesRead().first()
                avgPagesPerMinute.value = if (pages > 0) (pages.toFloat() / mins.toFloat()).coerceIn(0.3f, 5f) else 2f
            }
        }
        startIdleWatcher()

        setContent {
            CalmLibTheme {
                ReaderScreenFull(
                    isPdf = isPdf.value,
                    webViewFactory = { createWebView().also { webView = it } },
                    webViewEpoch = webViewEpoch.intValue,
                    pdfViewFactory = { ctx -> PdfReaderView(ctx).also { pdfView = it } },
                    currentPage = currentPage.intValue,
                    totalPages = totalPages.intValue,
                    showControls = showControls.value,
                    showToc = showToc.value,
                    showBookmarks = showBookmarks.value,
                    showSearch = showSearch.value,
                    showHighlights = showHighlights.value,
                    showDictionary = showDictionary.value,
                    settings = settings.value,
                    bookmarks = bookmarks.value,
                    highlights = highlights.value,
                    tocEntries = epubParser?.tableOfContents ?: fb2Parser?.tableOfContents ?: emptyList(),
                    errorMessage = errorMessage.value,
                    noticeMessage = noticeMessage.value,
                    onDismissNotice = { noticeMessage.value = null },
                    dictWord = dictWord.value,
                    dictResult = dictResult.value,
                    searchResults = searchResults.value,
                    onToggleControls = { showControls.value = !showControls.value },
                    onToggleToc = { showToc.value = !showToc.value; showControls.value = false },
                    onToggleBookmarks = { showBookmarks.value = !showBookmarks.value; showControls.value = false },
                    onToggleSearch = { showSearch.value = !showSearch.value; showControls.value = false },
                    onToggleHighlights = { showHighlights.value = !showHighlights.value; showControls.value = false },
                    onDismissDictionary = { showDictionary.value = false },
                    onSettingsChanged = { s ->
                        settings.value = s
                        applySettings(s)
                        // Save to THIS book only — never the global store. So changes
                        // here don't leak into another book. (No-op for guest previews.)
                        persistableBook()?.let { b ->
                            lifecycleScope.launch { bookRepo.saveReadingSettings(b.id, s.toJson()) }
                        }
                    },
                    onRefresh = { EinkRefresh.manualRefresh(this) },
                    onTocNavigate = { entry -> navigateToTocEntry(entry) },
                    onAddBookmark = {
                        persistableBook()?.let { b ->
                            lifecycleScope.launch {
                                bookRepo.addBookmark(b.id, currentPage.intValue, currentChapter, "Page ${currentPage.intValue + 1}")
                            }
                        }
                    },
                    onDeleteBookmark = { bm -> lifecycleScope.launch { bookRepo.deleteBookmark(bm) } },
                    onBookmarkNavigate = { bm -> goToLocation(bm.chapter, bm.page) },
                    onHighlightNavigate = { h ->
                        showHighlights.value = false
                        goToLocation(h.chapter, h.page)
                    },
                    onSearch = { query -> performSearch(query) },
                    onSearchNavigate = { page -> goToPage(page) },
                    onAddHighlight = { text, note ->
                        persistableBook()?.let { b ->
                            lifecycleScope.launch {
                                AppDatabase.get(this@ReaderActivity).highlightDao().insert(
                                    Highlight(bookId = b.id, chapter = currentChapter, page = currentPage.intValue, text = text, note = note)
                                )
                            }
                        }
                    },
                    onDeleteHighlight = { h ->
                        lifecycleScope.launch { AppDatabase.get(this@ReaderActivity).highlightDao().delete(h) }
                    },
                    onDictLookup = { word -> lookupWord(word) },
                    onExportHighlights = { exportHighlightsMarkdown() },
                    selectedText = if (showSelectionMenu.value) pendingSelection.value else null,
                    onSelectionDismiss = {
                        showSelectionMenu.value = false
                        webView?.evaluateJavascript("CalmReader.clearSelection()", null)
                    },
                    onSelectionLookup = { text ->
                        showSelectionMenu.value = false
                        // Clear the DOM selection too — it kept the highlight AND
                        // the system's floating Copy/Share menu on screen behind
                        // the definition popup.
                        webView?.evaluateJavascript("CalmReader.clearSelection()", null)
                        // Pick first word for dictionary
                        val word = text.split(Regex("\\s+")).firstOrNull()?.trim('.', ',', ';', ':', '!', '?', '"', '\'')
                        if (!word.isNullOrEmpty()) lookupWord(word)
                    },
                    onSelectionHighlight = { text ->
                        showSelectionMenu.value = false
                        persistableBook()?.let { b ->
                            lifecycleScope.launch {
                                AppDatabase.get(this@ReaderActivity).highlightDao().insert(
                                    Highlight(bookId = b.id, chapter = currentChapter, page = currentPage.intValue, text = text, note = "")
                                )
                            }
                        }
                        // Apply visual highlight on page now
                        val esc = text.replace("\\", "\\\\").replace("'", "\\'")
                        webView?.evaluateJavascript("CalmReader.markHighlight('$esc'); CalmReader.clearSelection();", null)
                    },
                    onSelectionSearch = { text ->
                        showSelectionMenu.value = false
                        showSearch.value = true
                        performSearch(text.take(40))
                    },
                    fullscreenImageUrl = fullscreenImageUrl.value,
                    onDismissImage = { fullscreenImageUrl.value = null },
                    pagesPerMinute = avgPagesPerMinute.value,
                    bookProgress = bookProgressFraction(),
                    estimatedBookPages = estimatedBookPages(),
                    isMultiChapter = isMultiChapter(),
                    isGuestPreview = isGuest.value,
                    onAddToLibrary = { addGuestToLibrary() },
                    onSeekPage = { p -> goToPage(p) },
                    onToggleTts = { toggleTts() },
                    isSpeaking = isSpeaking.value,
                    searchSnippets = searchSnippets.value,
                )
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        lastInteractionTs.value = System.currentTimeMillis()
        if (fullscreenImageUrl.value != null && keyCode == KeyEvent.KEYCODE_BACK) {
            fullscreenImageUrl.value = null
            return true
        }
        if (showSelectionMenu.value && keyCode == KeyEvent.KEYCODE_BACK) {
            showSelectionMenu.value = false
            webView?.evaluateJavascript("CalmReader.clearSelection()", null)
            return true
        }
        if (showDictionary.value || showControls.value || showToc.value || showBookmarks.value || showSearch.value || showHighlights.value) {
            if (keyCode == KeyEvent.KEYCODE_BACK) {
                showDictionary.value = false; showControls.value = false; showToc.value = false
                showBookmarks.value = false; showSearch.value = false; showHighlights.value = false
                return true
            }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                event?.startTracking()
                if (event?.isLongPress == true) { nextChapter(); return true }
                return true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                event?.startTracking()
                if (event?.isLongPress == true) { prevChapter(); return true }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_PAGE_DOWN -> { nextPage(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_PAGE_UP -> { prevPage(); return true }
            KeyEvent.KEYCODE_MENU -> { showControls.value = !showControls.value; return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> { nextChapter(); return true }
            KeyEvent.KEYCODE_VOLUME_UP -> { prevChapter(); return true }
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        // If it was a quick tap (not a long-press), treat volume keys as page-turn here
        // so the long-press path can fire chapter-skip when the user holds.
        if (event != null && !event.isLongPress && !event.isCanceled) {
            when (keyCode) {
                KeyEvent.KEYCODE_VOLUME_DOWN -> { nextPage(); return true }
                KeyEvent.KEYCODE_VOLUME_UP -> { prevPage(); return true }
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun nextChapter() {
        if (isPdf.value) {
            // Approximate "chapter" = jump 25 pages
            val target = (currentPage.intValue + 25).coerceAtMost(totalPages.intValue - 1)
            goToPage(target)
            return
        }
        if (currentChapter < totalChapters - 1) {
            currentChapter++
            loadSection(currentChapter)
        }
    }

    private fun prevChapter() {
        if (isPdf.value) {
            val target = (currentPage.intValue - 25).coerceAtLeast(0)
            goToPage(target)
            return
        }
        if (currentChapter > 0) {
            currentChapter--
            loadSection(currentChapter)
        }
    }

    private fun attachPdfViewWhenReady(engine: PdfEngine, initialPage: Int, attempts: Int) {
        if (isFinishing || isDestroyed) return
        val view = pdfView
        if (view == null) {
            if (attempts > 0) {
                window.decorView.postDelayed({
                    attachPdfViewWhenReady(engine, initialPage, attempts - 1)
                }, 50)
            }
            return
        }
        view.loadPdf(engine)
        view.goToPage(initialPage.coerceAtLeast(0))
        hasLiveContent = true   // PDF renders natively; there is real content to save
        view.onPageChanged = { page, total ->
            currentPage.intValue = page
            totalPages.intValue = total
        }
        val s = settings.value
        view.setDisplayMode(s.contrastBoost, s.boldMode)
        view.onTapZone = { zone ->
            when (zone) {
                PdfReaderView.TapZone.LEFT -> prevPage()
                PdfReaderView.TapZone.RIGHT -> nextPage()
                PdfReaderView.TapZone.CENTER -> showControls.value = !showControls.value
            }
        }
    }

    /**
     * Background watcher that splits the reading session when the user goes idle for
     * more than 2 minutes — without this, leaving the phone on the table inflates
     * "minutes read" and skews the time-remaining estimate.
     */
    private fun startIdleWatcher() {
        val handler = android.os.Handler(mainLooper)
        idleHandler = handler
        val runnable = object : Runnable {
            override fun run() {
                if (isFinishing || isDestroyed) return
                val now = System.currentTimeMillis()
                if (now - lastInteractionTs.value > 120_000 && sessionId > 0) {
                    val sid = sessionId
                    sessionId = 0
                    val pagesRead = (currentPage.intValue - sessionStartPage).coerceAtLeast(0)
                    lifecycleScope.launch(Dispatchers.IO) {
                        AppDatabase.get(this@ReaderActivity).statsDao()
                            .endSession(sid, lastInteractionTs.value, pagesRead)
                    }
                    sessionStartPage = currentPage.intValue
                }
                handler.postDelayed(this, 30_000)
            }
        }
        idleRunnable = runnable
        handler.postDelayed(runnable, 30_000)
    }

    private fun bumpInteraction() {
        lastInteractionTs.value = System.currentTimeMillis()
        if (sessionId == 0L) {
            val b = persistableBook() ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                sessionId = AppDatabase.get(this@ReaderActivity).statsDao()
                    .insert(com.calmlib.reader.data.model.ReadingSession(bookId = b.id, startTime = System.currentTimeMillis()))
                sessionStartPage = currentPage.intValue
            }
        }
    }

    private fun toggleTts() {
        if (isPdf.value) {
            noticeMessage.value = "Read-aloud isn't available for PDFs."
            return
        }
        if (isSpeaking.value) {
            tts?.stop()
            isSpeaking.value = false
            return
        }
        if (tts != null) {
            speakCurrentPage()
            return
        }
        // TextToSpeech is reluctant — its init callback fires on a binder thread,
        // we need to bounce to main before touching it. Status != SUCCESS means there
        // is no TTS engine installed on the device (common on de-googled AOSP like the
        // Mudita) — surface that to the user rather than failing silently.
        // NB: this callback can fire BEFORE the constructor returns, so it must
        // use its own reference — `tts` is not assigned yet. The old code
        // configured `tts?` (null, silently doing nothing) and, on failure,
        // nulled `tts` only for the assignment below to store the dead engine
        // again — leaving read-aloud permanently broken on this device.
        var initFailed = false
        lateinit var engine: android.speech.tts.TextToSpeech
        engine = android.speech.tts.TextToSpeech(applicationContext) { status ->
            runOnUiThread {
                if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                    try { engine.language = java.util.Locale.getDefault() } catch (_: Exception) {}
                    engine.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onError(utteranceId: String?) {
                            runOnUiThread { isSpeaking.value = false }
                        }
                        override fun onDone(utteranceId: String?) {
                            // Auto-advance to next page and keep reading.
                            runOnUiThread {
                                if (isSpeaking.value && currentPage.intValue < totalPages.intValue - 1) {
                                    nextPage()
                                    webView?.postDelayed({ speakCurrentPage() }, 400)
                                } else {
                                    isSpeaking.value = false
                                }
                            }
                        }
                    })
                    speakCurrentPage()
                } else {
                    // Transient notice, NOT errorMessage: that unmounts the
                    // whole reader, so a missing speech engine used to destroy
                    // a perfectly good reading session.
                    isSpeaking.value = false
                    initFailed = true
                    noticeMessage.value = "No text-to-speech engine on this device. " +
                        "You can sideload eSpeak or Pico TTS from F-Droid."
                    try { engine.shutdown() } catch (_: Exception) {}
                    tts = null
                }
            }
        }
        if (!initFailed) tts = engine
    }

    private fun speakCurrentPage() {
        if (isPdf.value || tts == null) return
        // Ask the WebView for the actual visible text via DOM rect checks. Works with
        // the translateY pagination — the old approach used scrollX which is always 0
        // under translateY and would have read from the start of the chapter every time.
        webView?.evaluateJavascript("CalmReader.getCurrentPageText()") { result ->
            val cleaned = unescapeJsString(result)
            // A not-yet-ready document returns the literal "null", which the
            // engine would happily read aloud as the word "null".
            if (cleaned.isBlank() || cleaned == "null") {
                runOnUiThread { isSpeaking.value = false }
                return@evaluateJavascript
            }
            runOnUiThread {
                val ok = tts?.speak(
                    cleaned,
                    android.speech.tts.TextToSpeech.QUEUE_FLUSH,
                    null,
                    "calm_${System.currentTimeMillis()}",
                )
                // Only claim to be speaking if the engine accepted the job.
                isSpeaking.value = ok == android.speech.tts.TextToSpeech.SUCCESS
                if (ok != android.speech.tts.TextToSpeech.SUCCESS) {
                    noticeMessage.value = "Read-aloud couldn't start."
                }
            }
        }
    }

    /** evaluateJavascript wraps string returns in JSON quotes — strip and decode. */
    private fun unescapeJsString(raw: String): String {
        return try {
            val v = org.json.JSONTokener(raw).nextValue()
            (v as? String) ?: raw.trim('"')
        } catch (_: Exception) {
            raw.trim('"').replace("\\\"", "\"").replace("\\n", " ").replace("\\\\", "")
        }.replace(Regex("\\s+"), " ").trim()
    }

    private fun enterFullscreen() {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterFullscreen()
    }

    override fun onStart() {
        super.onStart()
        if (pendingRendererRecovery) {
            pendingRendererRecovery = false
            recoverFromRendererDeath()
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up any dictionary added via Settings while we were paused. Off-thread
        // because the first-time install decompresses ~30MB.
        lifecycleScope.launch(Dispatchers.IO) { dictEngine.ensureInstalled() }
    }

    override fun onPause() {
        super.onPause()
        saveProgress()
        if (sessionId > 0) {
            val pagesRead = (currentPage.intValue - sessionStartPage).coerceAtLeast(0)
            lifecycleScope.launch {
                AppDatabase.get(this@ReaderActivity).statsDao().endSession(sessionId, System.currentTimeMillis(), pagesRead)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        idleRunnable?.let { idleHandler?.removeCallbacks(it) }
        idleHandler = null
        idleRunnable = null
        // Compose never destroys the WebView for us; a leaked one keeps a
        // renderer-process binding alive after the reader closes, feeding the
        // very memory pressure that makes this OS kill renderers.
        try { webView?.destroy() } catch (_: Exception) {}
        webView = null
        epubParser?.close()
        pdfEngine?.close()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
        tts = null
    }

    private fun goToPage(page: Int) {
        if (isPdf.value) {
            pdfView?.goToPage(page)
        } else {
            webView?.evaluateJavascript("CalmReader.goToPage($page)", null)
        }
    }

    /**
     * evaluateJavascript returns the value as a JSON-encoded string, e.g. the
     * literal `false` arrives as the four characters f-a-l-s-e (no quotes) on
     * recent WebView builds but older versions wrap it as `"false"`. Strip any
     * wrapping quotes/whitespace and check the contents.
     */
    private fun jsReturnedFalse(result: String?): Boolean {
        if (result == null) return false
        val trimmed = result.trim().trim('"').trim()
        return trimmed.equals("false", ignoreCase = true)
    }

    /**
     * Load a chapter/section by index regardless of format. EPUBs route through
     * loadChapter (file-based, preserves relative asset URLs); FB2 sections are
     * rendered from the parsed section HTML. loadChapter alone silently no-ops
     * for FB2 (epubParser is null), which used to strand FB2 readers at the end
     * of a section — page turns just stopped working.
     */
    private fun loadSection(index: Int, goToEnd: Boolean = false, initialPage: Int = 0) {
        if (epubParser != null) {
            loadChapter(index, goToEnd = goToEnd, initialPage = initialPage)
            return
        }
        val fb2 = fb2Parser ?: return
        if (index !in 0 until fb2.sectionCount()) return
        loadHtmlContent(fb2.sectionHtml(index), initialPage)
        // Consumed from onPageFinished, so it survives watchdog retries.
        pendingGoToEnd = goToEnd
    }

    /**
     * Jump to a stored (chapter, page) location — used by bookmarks and
     * highlights. Bookmark navigation used to pass only the page, which landed
     * on the wrong text whenever the bookmark lived in a different chapter.
     */
    private fun goToLocation(chapter: Int, page: Int) {
        if (isPdf.value) {
            goToPage(page)
            return
        }
        if (chapter != currentChapter) {
            currentChapter = chapter.coerceIn(0, (totalChapters - 1).coerceAtLeast(0))
            loadSection(currentChapter, initialPage = page)
        } else {
            goToPage(page)
        }
    }

    private fun nextPage() {
        if (pageTurnDebounced()) return
        bumpInteraction()
        if (isPdf.value) {
            pdfView?.nextPage()
            EinkRefresh.onPageTurn(this, settings.value.autoRefreshInterval)
        } else {
            webView?.evaluateJavascript("CalmReader.nextPage()") { result ->
                runOnUiThread {
                    if (jsReturnedFalse(result) && currentChapter < totalChapters - 1) {
                        android.util.Log.d("CalmNav", "chapter-advance next: $currentChapter -> ${currentChapter + 1} (js=$result)")
                        currentChapter++
                        loadSection(currentChapter, goToEnd = false)
                    }
                    EinkRefresh.onPageTurn(this, settings.value.autoRefreshInterval)
                }
            }
        }
    }

    private fun prevPage() {
        if (pageTurnDebounced()) return
        bumpInteraction()
        if (isPdf.value) {
            pdfView?.prevPage()
            EinkRefresh.onPageTurn(this, settings.value.autoRefreshInterval)
        } else {
            webView?.evaluateJavascript("CalmReader.prevPage()") { result ->
                runOnUiThread {
                    if (jsReturnedFalse(result) && currentChapter > 0) {
                        currentChapter--
                        loadSection(currentChapter, goToEnd = true)
                    }
                    EinkRefresh.onPageTurn(this, settings.value.autoRefreshInterval)
                }
            }
        }
    }

    private fun performSearch(query: String) {
        if (isPdf.value) return
        val esc = query.replace("\\", "\\\\").replace("'", "\\'")
        webView?.evaluateJavascript("CalmReader.searchTextWithSnippets('$esc', 30)") { result ->
            val items = parseSearchResults(result)
            runOnUiThread {
                searchResults.value = items.map { it.first }
                searchSnippets.value = items
            }
        }
    }

    private fun parseSearchResults(rawJsResult: String): List<Pair<Int, String>> {
        // evaluateJavascript wraps a JSON string in another JSON layer. Decode once via
        // org.json, then parse the inner array.
        return try {
            val unwrapped = if (rawJsResult.startsWith("\"")) {
                org.json.JSONTokener(rawJsResult).nextValue() as? String ?: return emptyList()
            } else rawJsResult
            val arr = org.json.JSONArray(unwrapped)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                obj.optInt("page", 0) to obj.optString("snippet", "")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun lookupWord(word: String) {
        dictWord.value = word
        dictResult.value = null   // popup shows "Looking up…"
        showDictionary.value = true
        // Index lookups hit disk (RandomAccessFile) — never on the main thread.
        lifecycleScope.launch(Dispatchers.IO) {
            // First run: the bundled WordNet may still be decompressing. Finish
            // the install here (loadBundledDict is synchronized + idempotent)
            // instead of telling the user no dictionary exists.
            if (!dictEngine.hasDictionaries) dictEngine.loadBundledDict()
            val results = dictEngine.lookup(word)
            val display = when {
                results.isNotEmpty() -> results.joinToString("\n\n") { entry ->
                    // Show the base form when the match came via inflection
                    // stripping ("running" → run).
                    val head = if (!entry.word.equals(word.trim().trim { !it.isLetterOrDigit() }, ignoreCase = true))
                        "→ ${entry.word}\n\n" else ""
                    if (entry.source.isNotEmpty()) "$head${entry.definition}\n— ${entry.source}"
                    else "$head${entry.definition}"
                }
                !dictEngine.hasDictionaries ->
                    "The offline dictionary isn't ready yet — on first launch it takes " +
                    "a minute to unpack.\n\nTry again shortly, or add a StarDict ZIP in Settings → Dictionary."
                else -> "No definition for “$word” in the offline dictionary."
            }
            withContext(Dispatchers.Main) { dictResult.value = display }

            // Save to vocabulary (library books only — previews leave no trace)
            val b = persistableBook()
            if (b != null && results.isNotEmpty()) {
                AppDatabase.get(this@ReaderActivity).highlightDao().insertVocabulary(
                    VocabularyEntry(word = results.first().word, definition = results.first().definition, bookId = b.id)
                )
            }
        }
    }

    private fun exportHighlightsMarkdown() {
        val b = book ?: return
        val hl = highlights.value
        if (hl.isEmpty()) return

        val md = buildString {
            appendLine("# Highlights: ${b.title}")
            if (b.author.isNotEmpty()) appendLine("*${b.author}*")
            appendLine()
            hl.forEach { h ->
                appendLine("> ${h.text}")
                if (h.note.isNotEmpty()) appendLine("\n${h.note}")
                appendLine("\n— Page ${h.page + 1}, Chapter ${h.chapter + 1}")
                appendLine()
            }
        }

        val file = File(getExternalFilesDir(null), "${b.title.take(40)}_highlights.md")
        file.writeText(md)

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_TEXT, md)
            putExtra(Intent.EXTRA_SUBJECT, "Highlights: ${b.title}")
        }
        startActivity(Intent.createChooser(intent, "Export highlights"))
    }

    private suspend fun importAndLoad(uri: Uri): Book? {
        val imported = bookRepo.importBook(uri)
        if (imported != null) {
            book = imported
            loadBook(imported)
        } else {
            errorMessage.value = "This file could not be opened"
        }
        return imported
    }

    /**
     * Copy an externally-opened file into cache and wrap it in a TRANSIENT Book
     * (id = 0, never inserted into the DB) so the normal loadBook pipeline can
     * render it. Returns null for unsupported/unreadable files.
     */
    private suspend fun prepareGuestBook(uri: Uri): Book? = withContext(Dispatchers.IO) {
        try {
            val mime = contentResolver.getType(uri)
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "document"
            val format = when {
                mime == "application/epub+zip" || name.endsWith(".epub", true) -> BookFormat.EPUB
                mime == "application/pdf" || name.endsWith(".pdf", true) -> BookFormat.PDF
                mime == "text/plain" || name.endsWith(".txt", true) -> BookFormat.TXT
                name.endsWith(".fb2", true) || mime?.contains("fictionbook") == true -> BookFormat.FB2
                else -> null
            } ?: return@withContext null

            val ext = format.name.lowercase()
            val target = File(cacheDir, "guest_preview.$ext")
            contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: return@withContext null
            if (target.length() == 0L) return@withContext null

            Book(
                id = 0,
                title = name.removeSuffix(".$ext").removeSuffix(".${ext.uppercase()}"),
                format = format,
                filePath = target.absolutePath,
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Promote the current guest preview to a real library book. Imports through
     * the standard pipeline (metadata, cover, dedup) and swaps the in-memory
     * Book so progress/highlights persist from this moment on. The rendered
     * WebView/PDF content is untouched — same bytes, same position.
     *
     * Two review-confirmed hazards handled here:
     *  - Double-tap: guestSourceUri is claimed synchronously on the main thread
     *    before the import coroutine starts, so a second tap no-ops instead of
     *    racing a second import past the fingerprint dedup.
     *  - Already-owned book: the dedup can return an EXISTING library row (you
     *    previewed a copy of a book you own). Persisting the preview position
     *    (page ~0) would silently wipe your saved place, so we only save when
     *    the returned row has no prior reading history.
     */
    private fun addGuestToLibrary() {
        val uri = guestSourceUri ?: return
        guestSourceUri = null   // claim immediately — second tap no-ops
        lifecycleScope.launch {
            val imported = bookRepo.importBook(uri)
            if (imported != null) {
                val hasExistingProgress =
                    imported.lastRead > 0 || imported.currentPage > 0 || imported.bookProgress > 0f
                book = imported
                isGuest.value = false
                startLibraryTracking(imported, startPage = currentPage.intValue)
                if (!hasExistingProgress) {
                    // Fresh import: carry the preview position forward.
                    saveProgress()
                }
                // Already-owned with progress: leave the stored position alone;
                // the current session continues from wherever you are now.
            } else {
                guestSourceUri = uri   // allow retry
                errorMessage.value = "Couldn't add this file to the library."
            }
        }
    }

    private suspend fun loadBook(book: Book) {
        when (book.format) {
            BookFormat.EPUB -> loadEpub(book)
            BookFormat.PDF -> loadPdf(book)
            BookFormat.TXT -> loadTxt(book)
            BookFormat.FB2 -> loadFb2(book)
        }
    }

    private suspend fun loadTxt(book: Book) = withContext(Dispatchers.IO) {
        try {
            val file = File(book.filePath)
            if (!file.exists()) { errorMessage.value = "File not found"; return@withContext }
            val engine = com.calmlib.reader.engine.TxtEngine(file)
            val html = engine.toHtmlPages()
            totalChapters = 1
            currentChapter = 0
            withContext(Dispatchers.Main) { loadHtmlContent(html, book.currentPage, book.scrollOffset) }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { errorMessage.value = "This file could not be opened" }
        }
    }

    private suspend fun loadFb2(book: Book) = withContext(Dispatchers.IO) {
        try {
            val file = File(book.filePath)
            if (!file.exists()) { errorMessage.value = "File not found"; return@withContext }
            fb2Parser = com.calmlib.reader.engine.Fb2Parser(file)
            totalChapters = fb2Parser!!.sectionCount().coerceAtLeast(1)
            chapterWeights = fb2Parser!!.sectionSizes()
            currentChapter = book.currentChapter.coerceIn(0, (totalChapters - 1).coerceAtLeast(0))
            withContext(Dispatchers.Main) {
                if (totalChapters <= 1) {
                    loadHtmlContent(fb2Parser!!.toHtml(), book.currentPage, book.scrollOffset)
                } else {
                    loadHtmlContent(fb2Parser!!.sectionHtml(currentChapter), book.currentPage, book.scrollOffset)
                }
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { errorMessage.value = "This file could not be opened" }
        }
    }

    /**
     * Monotonic token for chapter loads. Each loadHtmlContent/loadChapter bumps
     * it; their delayed setup callbacks no-op if a newer load superseded them.
     * Without this, two rapid jumps (e.g. tapping two highlights < 300ms apart)
     * let the FIRST load's stale goToPage fire on the SECOND load's content.
     */
    private var loadGeneration = 0

    /** Generation of the last chapter whose onPageFinished actually fired. The
     *  Mudita kills WebView renderer processes ("isolated not needed") and takes
     *  ~10s to spawn a replacement — loads issued in that window are silently
     *  dropped. The load watchdog compares this against loadGeneration and
     *  re-issues until the content genuinely loads. */
    @Volatile private var lastFinishedGeneration = -1

    /** Generation of the last load the renderer COMMITTED (onPageStarted). This
     *  fires within milliseconds even for image-heavy chapters, because images
     *  load after commit. It lets the watchdog tell "renderer never got the
     *  load" (re-issue) apart from "load is alive but slow" (wait) — re-issuing
     *  over a live slow load aborts it every 2.5s, so an image-heavy chapter
     *  could NEVER finish loading. */
    @Volatile private var lastStartedGeneration = -1

    /** Renderer died while we weren't visible — defer the rebuild to onStart
     *  instead of reloading into the same memory squeeze while backgrounded. */
    private var pendingRendererRecovery = false

    /** Position restore for the in-flight chapter load. Consumed from
     *  onPageFinished — the only signal that content REALLY loaded — so restores
     *  survive watchdog retries instead of dying with a dropped load. */
    private var pendingGoToEnd = false
    private var pendingRestoreChar = 0f
    private var pendingRestorePage = 0

    private fun consumePendingRestore(wv: WebView) {
        when {
            pendingGoToEnd -> wv.evaluateJavascript("CalmReader.goToEndOfChapter()", null)
            pendingRestoreChar > 0f ->
                wv.evaluateJavascript("CalmReader.goToCharOffset(${pendingRestoreChar.toInt()})", null)
            pendingRestorePage > 0 ->
                wv.evaluateJavascript("CalmReader.goToPage($pendingRestorePage)", null)
        }
        pendingGoToEnd = false
        pendingRestoreChar = 0f
        pendingRestorePage = 0
    }

    /**
     * Run block once the WebView exists. Compose creates the WebView during the
     * first composition, which on a COLD start can finish AFTER loadBook's
     * coroutine reaches loadChapter — `webView?.loadDataWithBaseURL` then lands
     * on null and silently no-ops, leaving a permanently blank, dead reader.
     * (Proven live on-device: loadChapter logged, zero JS afterward, taps dead.)
     */
    private fun withWebView(attempts: Int = 200, block: (WebView) -> Unit) {
        if (isFinishing || isDestroyed) return
        val wv = webView
        if (wv != null) {
            block(wv)
            return
        }
        if (attempts <= 0) {
            // 10s and Compose never produced a WebView — surface it instead of
            // silently doing nothing (which reads as "this book never loads").
            android.util.Log.d("CalmNav", "withWebView gave up — no WebView after 10s")
            errorMessage.value = "The reader view didn't start.\n\nGo back and reopen this book."
            return
        }
        window.decorView.postDelayed({ withWebView(attempts - 1, block) }, 50)
    }

    private fun loadHtmlContent(html: String, initialPage: Int = 0, initialCharOffset: Float = 0f) {
        val s = settings.value
        val fontCss = fontFamilyCss(s.fontFamily)
        val safeHtml = sanitizeBookHtml(html)   // FB2/TXT bodies are untrusted too
        val fullHtml = """<!DOCTYPE html><html><head><meta charset="utf-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
            <link rel="stylesheet" href="file:///android_asset/reader/reader.css" />
            <script src="file:///android_asset/reader/reader.js"></script>
            <style>body { font-size: ${s.fontSize}px; line-height: ${s.lineSpacing};
                padding: ${s.marginVertical}px ${s.marginHorizontal}px; font-family: $fontCss; }</style>
            </head><body class="paginated">$safeHtml</body></html>""".trimIndent()
        val gen = ++loadGeneration
        lastCharOffset = -1   // stale offsets must not leak across section loads
        hasLiveContent = false
        pendingGoToEnd = false
        pendingRestoreChar = initialCharOffset
        pendingRestorePage = initialPage
        withWebView { wv ->
            loadWithWatchdog(wv, gen) {
                // ?g=<gen> tags the load so onPageFinished knows WHICH load
                // finished — a stale finish must not satisfy a newer watchdog.
                wv.loadDataWithBaseURL("file:///android_asset/reader/?g=$gen", fullHtml, "text/html", "utf-8", null)
            }
        }
    }

    /**
     * Issue a content load and babysit it. If onPageFinished hasn't fired within
     * 2.5s, the renderer wasn't alive to receive it (this device tears WebView
     * renderers down aggressively and takes ~10s to respawn them) — re-issue the
     * load, up to 6 times. The setup path (applySettings + position restore) is
     * driven by onPageFinished, so it re-runs naturally with a successful retry.
     */
    private fun loadWithWatchdog(wv: WebView, gen: Int, attempt: Int = 0, issue: () -> Unit) {
        if (gen != loadGeneration) return
        issue()
        // NEVER re-issue a load on a timer. A cold renderer on this device
        // legitimately needs ~10s+ (process spawn, fallback service, slow
        // flash) before our document even begins parsing — and issuing again
        // ABORTS the in-flight navigation. A 2.5s re-issue loop turned every
        // cold start into deterministic permanent failure: each load was
        // killed before it could execute a single script, forever. One load,
        // one generous deadline; if there are zero signs of life by then,
        // rebuild the WebView through the capped recovery path. (Timer lives
        // on the decor view — a detached WebView freezes its own callbacks.)
        window.decorView.postDelayed({
            if (gen != loadGeneration) return@postDelayed
            if (lastFinishedGeneration >= gen || lastStartedGeneration >= gen || hasLiveContent) return@postDelayed
            android.util.Log.d("CalmNav", "load deadline: no life signs gen=$gen after 15s — rebuilding")
            recoverFromRendererDeath()
        }, 15_000)
    }

    /**
     * Replace a dead WebView with a fresh one and reload the current content at
     * the live in-memory position. Capped: a book whose load reliably kills the
     * renderer must degrade to an error message, not an infinite rebuild loop.
     */
    private fun recoverFromRendererDeath() {
        val now = android.os.SystemClock.elapsedRealtime()
        while (rendererDeaths.isNotEmpty() && now - rendererDeaths.first() > 90_000) rendererDeaths.removeFirst()
        rendererDeaths.addLast(now)
        rendererRecoveryTotal++
        // Two caps: a burst cap (sliding window) AND a total since content last
        // rendered — a slow death cycle (>30s each) never trips a 90s window
        // alone, which would rebuild-loop forever.
        if (rendererDeaths.size > 3 || rendererRecoveryTotal > 5) {
            android.util.Log.d("CalmNav", "renderer death loop — giving up " +
                "(${rendererDeaths.size} in 90s, $rendererRecoveryTotal total)")
            errorMessage.value = "The system keeps stopping the reader while opening this book.\n\n" +
                "Go back and reopen it — if this keeps happening, restart the phone to free memory."
            return
        }
        // The watchdog-exhaustion path reaches here with the zombie WebView
        // still alive; Compose disposal alone never destroys it, and a leaked
        // WebView feeds exactly the memory pressure we're recovering from.
        webView?.let { old ->
            webView = null
            (old.parent as? android.view.ViewGroup)?.removeView(old)
            try { old.destroy() } catch (_: Exception) {}
        }
        webViewEpoch.intValue++   // composition disposes the dead view, factory makes a new one
        // Small settle delay: reloading instantly lands in the same memory
        // squeeze that just killed the renderer.
        window.decorView.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            reloadCurrentContent()
        }, 800)
    }

    /** Reload whatever is currently open, preserving the live position. */
    private fun reloadCurrentContent() {
        val b = book ?: return
        // If content never actually rendered, the live currentPage is a
        // meaningless 0 — restore from the position the book was OPENED with.
        val restorePage = if (hasLiveContent) currentPage.intValue else b.currentPage
        val restoreChar = if (hasLiveContent && lastCharOffset >= 0) lastCharOffset.toFloat() else b.scrollOffset
        if (epubParser != null) {
            loadChapter(currentChapter, initialPage = restorePage, initialCharOffset = restoreChar)
        } else {
            // TXT / FB2: re-run the format loader with the live position patched
            // into the book copy it reads its start position from.
            val patched = b.copy(
                currentPage = restorePage,
                currentChapter = currentChapter,
                scrollOffset = restoreChar,
            )
            book = patched
            lifecycleScope.launch { loadBook(patched) }
        }
    }

    private fun fontFamilyCss(family: String): String = when (family) {
        "atkinson", "sans" -> "'CalmSans', 'Atkinson Hyperlegible', sans-serif"
        "jetbrains_mono", "mono" -> "'CalmMono', 'JetBrains Mono', monospace"
        else -> "'CalmSerif', 'Literata', serif"
    }

    private suspend fun loadPdf(book: Book) = withContext(Dispatchers.IO) {
        val file = File(book.filePath)
        if (!file.exists()) {
            withContext(Dispatchers.Main) { errorMessage.value = "File not found." }
            return@withContext
        }
        try {
            pdfEngine = PdfEngine(file)
            withContext(Dispatchers.Main) {
                isPdf.value = true
                totalPages.intValue = pdfEngine!!.pageCount
                currentPage.intValue = book.currentPage.coerceIn(0, totalPages.intValue - 1)
                val engine = pdfEngine
                if (engine != null) attachPdfViewWhenReady(engine, currentPage.intValue, attempts = 40)
            }
        } catch (e: SecurityException) {
            withContext(Dispatchers.Main) {
                errorMessage.value = "This PDF is password-protected.\n\n" +
                    "Android's built-in PDF reader can't open encrypted files. " +
                    "Strip the password externally (e.g. with Calibre on a computer) " +
                    "and re-import."
            }
        } catch (e: java.io.IOException) {
            withContext(Dispatchers.Main) {
                errorMessage.value = "This PDF file is damaged or incomplete.\n\n" +
                    "Try downloading it again, or — back in the library — long-press " +
                    "this book and tap \"Convert to EPUB\" to attempt a recovery."
            }
        } catch (e: IllegalArgumentException) {
            withContext(Dispatchers.Main) {
                errorMessage.value = "This PDF uses a feature that isn't supported " +
                    "(often XFA forms or PDF 2.0).\n\n" +
                    "Long-press this book in the library and tap \"Convert to EPUB\" — " +
                    "the PDF→EPUB converter handles more PDF features than the renderer does."
            }
        } catch (e: OutOfMemoryError) {
            withContext(Dispatchers.Main) {
                errorMessage.value = "This PDF is too large for the Mudita's memory. " +
                    "Try splitting it into smaller PDFs on a computer."
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                val why = e.message?.take(120) ?: e.javaClass.simpleName
                errorMessage.value = "Couldn't open this PDF: $why\n\n" +
                    "Long-press it in the library and try \"Convert to EPUB\"."
            }
        }
    }

    private suspend fun loadEpub(book: Book) = withContext(Dispatchers.IO) {
        try {
            val file = File(book.filePath)
            if (!file.exists()) { errorMessage.value = "File not found"; return@withContext }
            epubParser = EpubParser(file)
            epubDir = epubParser!!.extractToDir(cacheDir)
            totalChapters = epubParser!!.chapters.size
            chapterWeights = epubParser!!.chapterSizes()
            currentChapter = book.currentChapter.coerceIn(0, (totalChapters - 1).coerceAtLeast(0))
            withContext(Dispatchers.Main) {
                loadChapter(currentChapter, goToEnd = false, initialPage = book.currentPage, initialCharOffset = book.scrollOffset)
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { errorMessage.value = "This file could not be opened" }
        }
    }

    private fun loadChapter(index: Int, goToEnd: Boolean = false, initialPage: Int = 0, initialCharOffset: Float = 0f) {
        val parser = epubParser ?: return
        val dir = epubDir ?: return
        if (parser.chapters.isEmpty()) {
            // A spine whose idrefs don't resolve parses to zero chapters. A bare
            // return here is a permanently blank reader with no explanation.
            android.util.Log.d("CalmNav", "loadChapter: empty spine")
            errorMessage.value = "This EPUB has no readable chapters.\n\n" +
                "The file's internal index (spine) doesn't match its content. " +
                "Re-exporting it with Calibre usually repairs this."
            return
        }
        if (index !in parser.chapters.indices) return
        android.util.Log.d("CalmNav", "loadChapter index=$index goToEnd=$goToEnd initialPage=$initialPage")

        val chapter = parser.chapters[index]
        // Spine hrefs are relative to the OPF's directory (e.g. OEBPS/), not the
        // zip root — resolving from the root gave the wrong baseUrl, and every
        // relative <img src> in the chapter silently broke.
        val chapterFile = findChapterFile(dir, parser.contentDir + chapter.href)
            ?: findChapterFile(dir, chapter.href)
        val baseUrl = "file://${chapterFile?.parentFile?.absolutePath ?: dir.absolutePath}/"
        // Relative: reader.js/css are copied next to the rendered chapter.
        val cssPath = "reader.css"
        val jsPath = "reader.js"

        val chapterContent = sanitizeBookHtml(
            try {
                chapterFile?.readText() ?: parser.getChapterContent(index) ?: "<p>Chapter not found</p>"
            } catch (_: Exception) {
                parser.getChapterContent(index) ?: "<p>Chapter not found</p>"
            }
        )

        val s = settings.value
        val fontCss = fontFamilyCss(s.fontFamily)

        val styleBlock = """
            <style>
                body {
                    font-size: ${s.fontSize}px;
                    line-height: ${s.lineSpacing};
                    padding: ${s.marginVertical}px ${s.marginHorizontal}px;
                    font-family: $fontCss;
                }
            </style>
        """.trimIndent()

        val injection = "<link rel=\"stylesheet\" href=\"$cssPath\" />\n<script src=\"$jsPath\"></script>\n$styleBlock\n"
        val headEnd = chapterContent.indexOf("</head>", ignoreCase = true)
        val bodyStart = chapterContent.indexOf("<body", ignoreCase = true)
        val styled = when {
            // Normal XHTML: inject just before </head> (case-insensitive — some
            // publishers ship </HEAD>, which the old exact-match replace missed,
            // leaving the chapter without reader.js entirely).
            headEnd >= 0 -> StringBuilder(chapterContent).insert(headEnd, injection).toString()
            // Has a <body> but no head: inject right before it — browsers tolerate this.
            chapterContent.contains("<html", ignoreCase = true) && bodyStart >= 0 ->
                StringBuilder(chapterContent).insert(bodyStart, injection).toString()
            // Fragment: wrap fully.
            else ->
                """<!DOCTYPE html><html><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0, user-scalable=no">
                $injection</head><body class="paginated">$chapterContent</body></html>""".trimIndent()
        }

        val gen = ++loadGeneration
        lastCharOffset = -1   // stale offsets must not leak across chapter loads
        hasLiveContent = false
        pendingGoToEnd = goToEnd
        pendingRestoreChar = initialCharOffset
        pendingRestorePage = initialPage

        // Render from a REAL file in the chapter's own directory rather than
        // loadDataWithBaseURL. A genuine file:// document loads its
        // same-directory images with only allowFileAccess — so the dangerous
        // cross-origin file flags (which also handed book scripts the whole
        // filesystem) are no longer needed. reader.js/css are copied in
        // alongside so every subresource is same-directory too.
        val renderDir = chapterFile?.parentFile ?: dir
        val renderFile = try {
            copyReaderAssetsTo(renderDir)
            File(renderDir, RENDER_FILE).apply { writeText(styled) }
        } catch (e: Exception) {
            android.util.Log.d("CalmNav", "render file write failed: $e")
            null
        }

        withWebView { wv ->
            loadWithWatchdog(wv, gen) {
                if (renderFile != null) {
                    // #g=<gen> tags the load for attribution; a fragment never
                    // affects file resolution or relative asset paths.
                    wv.loadUrl("file://${renderFile.absolutePath}#g=$gen")
                } else {
                    wv.loadDataWithBaseURL("$baseUrl?g=$gen", styled, "text/html", "utf-8", null)
                }
            }
        }
    }

    /** Put reader.js/reader.css next to the rendered chapter so the document
     *  only ever loads same-directory subresources. */
    private fun copyReaderAssetsTo(dir: File) {
        listOf("reader.js", "reader.css").forEach { name ->
            val target = File(dir, name)
            val src = assets.open("reader/$name").use { it.readBytes() }
            if (!target.exists() || target.length() != src.size.toLong()) {
                target.writeBytes(src)
            }
        }
    }

    /**
     * Remove executable content from book-supplied HTML.
     *
     * An EPUB is a zip a stranger emailed you, and its chapters are arbitrary
     * XHTML. Any <script> in one runs inside our reader document — same context
     * as reader.js — so it could call the CalmBridge JavaScript interface
     * (faking reading positions, spamming saves), rewrite the page, or probe
     * local files. No legitimate book needs scripting to be read.
     */
    private fun sanitizeBookHtml(html: String): String {
        var out = html.replace(
            Regex("<script\\b[^>]*>.*?</script\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), ""
        )
        // Unclosed/truncated script tag: drop from the tag to end of document.
        out = out.replace(Regex("<script\\b[^>]*>.*", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        out = out.replace(
            Regex("<(iframe|object|embed|frame|frameset)\\b", RegexOption.IGNORE_CASE), "<x-blocked-$1 "
        )
        // Inline handlers: on*="…" / on*='…' / on*=bare
        out = out.replace(Regex("\\son[a-zA-Z]+\\s*=\\s*\"[^\"]*\"", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("\\son[a-zA-Z]+\\s*=\\s*'[^']*'", RegexOption.IGNORE_CASE), "")
        out = out.replace(Regex("\\son[a-zA-Z]+\\s*=\\s*[^\\s>]+", RegexOption.IGNORE_CASE), "")
        // javascript: / data:text/html URLs in href/src
        out = out.replace(Regex("(href|src)\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\2", RegexOption.IGNORE_CASE), "$1=$2#$2")
        out = out.replace(Regex("(href|src)\\s*=\\s*([\"'])\\s*data:text/html[^\"']*\\2", RegexOption.IGNORE_CASE), "$1=$2#$2")
        return out
    }

    private fun findChapterFile(dir: File, href: String): File? {
        // Hrefs in the OPF are URL-encoded ("chapter%201.xhtml") while the files
        // on disk have real spaces — try both spellings at each location.
        val candidates = listOf(
            href,
            try { java.net.URLDecoder.decode(href, "UTF-8") } catch (_: Exception) { href },
        ).distinct()
        for (candidate in candidates) {
            val direct = File(dir, candidate)
            if (direct.exists()) return direct
            dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
                val inSub = File(sub, candidate)
                if (inSub.exists()) return inSub
            }
        }
        return null
    }

    private fun applySettings(s: ReadingSettings, retriesLeft: Int = 6) {
        if (isPdf.value) {
            pdfView?.setDisplayMode(s.contrastBoost, s.boldMode)
            return
        }
        val fontCss = fontFamilyCss(s.fontFamily).replace("'", "\\'")
        val readyCheck = "(typeof CalmReader !== 'undefined' && typeof CalmReader.setStyle === 'function') ? 'ok' : 'wait'"
        webView?.evaluateJavascript(readyCheck) { result ->
            val ready = result?.contains("ok") == true
            if (!ready && retriesLeft > 0) {
                webView?.postDelayed({ applySettings(s, retriesLeft - 1) }, 120)
                return@evaluateJavascript
            }
            if (!ready) return@evaluateJavascript
            webView?.evaluateJavascript(
                "CalmReader.setStyle(${s.fontSize}, ${s.lineSpacing}, ${s.marginHorizontal}, ${s.marginVertical}, '$fontCss')", null)
            webView?.evaluateJavascript(
                "CalmReader.setDisplayMode(${s.contrastBoost}, ${s.boldMode}, ${!s.antiAliasing})", null)
            webView?.evaluateJavascript(
                "CalmReader.setTextOptions(${s.justify}, ${s.hyphenation}, ${s.bionicReading})", null)
        }
    }

    private fun applyDisplayClasses(s: ReadingSettings) {
        val classes = buildList {
            if (s.contrastBoost) add("contrast-boost")
            if (s.boldMode) add("bold-mode")
            if (!s.antiAliasing) add("no-antialias")
        }
        classes.forEach { cls ->
            webView?.evaluateJavascript("document.body.classList.add('$cls')", null)
        }
    }

    private fun navigateToTocEntry(entry: com.calmlib.reader.engine.TocEntry) {
        showToc.value = false
        val parser = epubParser
        if (parser != null) {
            val href = entry.href.substringBefore("#")
            val chapterIndex = parser.chapters.indexOfFirst { it.href == href || it.href.endsWith(href) }
            if (chapterIndex in parser.chapters.indices) {
                currentChapter = chapterIndex
                loadChapter(chapterIndex)
            }
            return
        }
        // FB2 — entry.href is the section index as a String
        val fb2 = fb2Parser
        if (fb2 != null) {
            val idx = entry.href.toIntOrNull() ?: return
            if (idx in 0 until fb2.sectionCount()) {
                currentChapter = idx
                loadHtmlContent(fb2.sectionHtml(idx))
            }
        }
    }

    private fun saveProgress() {
        val b = persistableBook() ?: return
        // Nothing rendered for this load yet → nothing meaningful to save, and
        // writing would clobber the real stored position with page 0.
        if (!hasLiveContent) return
        // Snapshot everything on the caller's thread, then write on a
        // PROCESS-lifetime scope. lifecycleScope cancels at onDestroy, which
        // could abort the final save when the user quits — the root cause of
        // "sometimes it doesn't stay on the page".
        val fraction = bookProgressFraction()
        val page = currentPage.intValue
        val chapter = currentChapter
        val total = totalPages.intValue
        val charOffset = if (lastCharOffset >= 0) lastCharOffset.toFloat() else 0f
        com.calmlib.reader.util.AppScope.io.launch {
            bookRepo.updateProgress(b.id, page, chapter, charOffset, bookProgress = fraction)
            bookRepo.updateTotalPages(b.id, total)
        }
    }

    override fun onStop() {
        super.onStop()
        // Belt to onPause's braces: one more flush as the activity leaves the screen.
        saveProgress()
    }

    @Suppress("SetJavaScriptEnabled")
    private fun createWebView(): WebView {
        return WebView(this).apply {
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)
            setBackgroundColor(android.graphics.Color.WHITE)
            settings.javaScriptEnabled = true   // reader.js drives pagination
            // allowFileAccess lets a file:// document pull in its OWN directory's
            // images/CSS/fonts, which EPUB chapters need.
            settings.allowFileAccess = true
            // The two cross-origin file flags are deliberately OFF. They were on
            // to fix image loading, but they also let any <script> inside a book
            // read arbitrary local files (the app database, other books, shared
            // storage). Book scripts are now stripped (sanitizeBookHtml) AND the
            // capability is removed — either alone would do; both is cheap.
            @Suppress("DEPRECATION") settings.allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION") settings.allowUniversalAccessFromFileURLs = false
            settings.allowContentAccess = false
            settings.loadWithOverviewMode = false
            settings.useWideViewPort = false
            settings.setSupportZoom(false)
            settings.displayZoomControls = false
            settings.builtInZoomControls = false
            settings.domStorageEnabled = false
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER

            addJavascriptInterface(object {
                @JavascriptInterface
                fun onPageChanged(page: Int, total: Int, charOffset: Int) {
                    hasLiveContent = true
                    if (charOffset >= 0) lastCharOffset = charOffset
                    runOnUiThread {
                        rendererRecoveryTotal = 0   // content is alive again
                        currentPage.intValue = page
                        totalPages.intValue = total
                        maybeAutosave()
                    }
                }

                @JavascriptInterface
                fun onTextSelected(text: String) {
                    val cleaned = text.trim()
                    if (cleaned.length < 2) return
                    runOnUiThread {
                        pendingSelection.value = cleaned
                        showSelectionMenu.value = true
                    }
                }

                @JavascriptInterface
                fun onImageTapped(src: String) {
                    if (src.isBlank()) return
                    // Stamp BEFORE hopping threads so the pending tap-zone runnable
                    // (fires 160ms after the touch) reliably sees it and stands down.
                    lastImageTapMs = System.currentTimeMillis()
                    android.util.Log.d("CalmNav", "onImageTapped src=$src")
                    runOnUiThread {
                        fullscreenImageUrl.value = src
                    }
                }

                @JavascriptInterface
                fun onDebug(msg: String) {
                    android.util.Log.d("CalmJS", msg)
                }

                @JavascriptInterface
                fun onImageVisible() {
                    // A picture is on screen: force a full e-ink repaint even
                    // when auto-refresh is off — partial updates render large
                    // images poorly and slowly enough to invite double-taps.
                    runOnUiThread {
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastImageFlashMs > 600) {
                            lastImageFlashMs = now
                            android.util.Log.d("CalmNav", "image page — forced full refresh")
                            EinkRefresh.manualRefresh(this@ReaderActivity)
                        }
                    }
                }

                @JavascriptInterface
                fun onDocStarted(g: Int) {
                    // reader.js announcing it is executing inside a live
                    // renderer — the watchdog's commit signal.
                    android.util.Log.d("CalmNav", "doc beacon g=$g")
                    val eff = if (g > 0) g else loadGeneration
                    if (eff > lastStartedGeneration) lastStartedGeneration = eff
                }
            }, "CalmBridge")

            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true

                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    android.util.Log.d("CalmNav", "onPageStarted url=$url")
                    if (url == null || url == "about:blank") return
                    // Commit = the renderer is alive and took the load. Some
                    // WebView builds report the URL without our ?g= query — an
                    // untagged file:// commit must still count as the current
                    // generation, or the watchdog keeps re-issuing right over
                    // the live load and aborts it forever.
                    val g = Regex("[?&#]g=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                        ?: if (url.startsWith("file://")) loadGeneration else return
                    if (g > lastStartedGeneration) lastStartedGeneration = g
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    val wv = view ?: return
                    // A fresh WebView can report about:blank — that must not count
                    // as "our content loaded" or the watchdog would stand down.
                    if (url == null || url == "about:blank") return
                    // The ?g=<gen> tag on the baseUrl says WHICH load finished.
                    // A slow finish from a superseded load must not stamp the
                    // current generation: the watchdog would stand down while the
                    // real load was dropped by a dead renderer → blank forever.
                    // Untagged file:// URLs get benefit of the doubt (all our
                    // loads are file://); anything else (chrome-error pages,
                    // data: fallbacks) must NOT disarm the watchdog.
                    val finishedGen = Regex("[?&#]g=(\\d+)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                        ?: if (url.startsWith("file://")) loadGeneration else return
                    android.util.Log.d("CalmNav", "onPageFinished url=$url gen=$finishedGen")
                    if (finishedGen > lastFinishedGeneration) lastFinishedGeneration = finishedGen
                    if (finishedGen != loadGeneration) return
                    val gen = loadGeneration
                    wv.evaluateJavascript("CalmReader.init()", null)
                    // After init runs, push the current settings — beats any race where
                    // the panel applied them before reader.js was loaded.
                    wv.postDelayed({
                        if (gen != loadGeneration) return@postDelayed
                        applyDisplayClasses(this@ReaderActivity.settings.value)
                        applySettings(this@ReaderActivity.settings.value)
                        consumePendingRestore(wv)
                    }, 200)
                    // Liveness probe: "finished" is not "working". Rapid re-issues
                    // into a respawning renderer can leave a finished-but-broken
                    // document (error page, script never executed, layout stuck
                    // at zero height) — blank screen, zero logs, watchdog
                    // pacified. Assume DEAD unless proven alive: on a dead
                    // renderer evaluateJavascript never invokes its callback, so
                    // the recovery decision cannot live inside the callback.
                    window.decorView.postDelayed({
                        if (gen != loadGeneration || hasLiveContent) return@postDelayed
                        var provenAlive = false
                        wv.evaluateJavascript(
                            "(typeof CalmReader !== 'undefined') && CalmReader._paginated === true"
                        ) { res -> if (res != null && res.contains("true")) provenAlive = true }
                        window.decorView.postDelayed({
                            if (gen != loadGeneration || hasLiveContent) return@postDelayed
                            if (!provenAlive) {
                                android.util.Log.d("CalmNav", "liveness probe failed gen=$gen — recovering")
                                recoverFromRendererDeath()
                            }
                        }, 700)
                    }, 2000)
                }

                // The Mudita's OS kills WebView renderer processes under memory
                // pressure ("isolated not needed"); a WebView whose renderer died
                // is unusable and must be destroyed. Recovery rebuilds JUST the
                // WebView (epoch key in the composition) and reloads in place —
                // recreate() here caused an infinite recreate loop for books
                // whose load itself killed the renderer, which read as "this
                // book never loads at all".
                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: android.webkit.RenderProcessGoneDetail?
                ): Boolean {
                    android.util.Log.d("CalmNav", "renderer gone (crash=${detail?.didCrash()}) gen=$loadGeneration")
                    if (view === webView) webView = null
                    (view?.parent as? android.view.ViewGroup)?.removeView(view)
                    try { view?.destroy() } catch (_: Exception) {}
                    if (isFinishing || isDestroyed) return true
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                        // Screen off / another app in front: the OS reclaims idle
                        // renderers routinely. Rebuilding now would reload into
                        // the same squeeze (and could churn repeatedly while
                        // invisible) — do it when the reader comes back.
                        pendingRendererRecovery = true
                        return true
                    }
                    recoverFromRendererDeath()
                    return true
                }
            }

            val gd = android.view.GestureDetector(this@ReaderActivity, object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                    val w = width.toFloat()
                    val x = e.x
                    // Defer the zone action by 160ms: if the same touch clicked an
                    // <img> in the DOM, the JS bridge stamps lastImageTapMs within
                    // a few ms and we stand down — one tap, one action. The delay
                    // is invisible next to the E-Ink refresh itself.
                    postDelayed({
                        if (System.currentTimeMillis() - lastImageTapMs < 450) return@postDelayed
                        if (fullscreenImageUrl.value != null) return@postDelayed
                        android.util.Log.d("CalmNav", "tapZone x=$x w=$w")
                        when {
                            x < w * 0.40f -> prevPage()
                            x > w * 0.60f -> nextPage()
                            else -> showControls.value = !showControls.value
                        }
                    }, 160)
                    return true
                }
            })

            // Pinch-to-zoom adjusts font size live. Commit to settings on release.
            val activity = this@ReaderActivity
            var pinchStartFontSize = 0f
            val sd = android.view.ScaleGestureDetector(activity, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScaleBegin(d: android.view.ScaleGestureDetector): Boolean {
                    pinchStartFontSize = activity.settings.value.fontSize
                    return true
                }
                override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
                    val factor = d.scaleFactor
                    val current = activity.settings.value.fontSize
                    val newSize = (current * factor).coerceIn(10f, 48f)
                    if (kotlin.math.abs(newSize - current) >= 0.5f) {
                        val updated = activity.settings.value.copy(fontSize = newSize)
                        activity.settings.value = updated
                        activity.applySettings(updated)
                    }
                    return true
                }
                override fun onScaleEnd(d: android.view.ScaleGestureDetector) {
                    if (activity.settings.value.fontSize != pinchStartFontSize) {
                        val b = activity.persistableBook()
                        val s = activity.settings.value
                        if (b != null) activity.lifecycleScope.launch {
                            activity.bookRepo.saveReadingSettings(b.id, s.toJson())
                        }
                    }
                }
            })

            setOnTouchListener { _, event ->
                sd.onTouchEvent(event)
                // Don't dispatch single-finger taps to the GestureDetector while pinching.
                if (!sd.isInProgress) gd.onTouchEvent(event)
                false   // let the WebView see the event too (selection, scroll)
            }
        }
    }
}
