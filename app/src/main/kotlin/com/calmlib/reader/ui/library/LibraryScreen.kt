package com.calmlib.reader.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookFormat
import com.calmlib.reader.data.model.Collection
import com.calmlib.reader.data.model.SortField
import com.calmlib.reader.ui.theme.CalmFonts
import com.calmlib.reader.ui.theme.CalmTypography

private val BOOK_MIME_TYPES = arrayOf(
    "application/epub+zip",
    "application/pdf",
    "text/plain",
    "application/x-fictionbook+xml",
    "application/octet-stream",
)

private val PAGE_PADDING = 22.dp
private val Ink = Color.Black
private val Grey = Color(0xFF6E6E6E)
private val LightGrey = Color(0xFF777777)
private val Hairline = Color(0xFFDADADA)
private val Paper = Color(0xFFF6F6F6)

private fun SortField.label() = when (this) {
    SortField.TITLE -> "Title"
    SortField.AUTHOR -> "Author"
    SortField.DATE_ADDED -> "Recently added"
    SortField.LAST_READ -> "Recently read"
    SortField.FORMAT -> "Format"
}

/**
 * The library. Laid out like a Kindle home screen: title, a line of counts,
 * a row of format tabs (All · EPUB · PDF) with the sort word on the right,
 * then shelves of big covers. Everything rarer lives behind "More".
 */
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookClick: (Book) -> Unit,
    onRequestScan: () -> Unit = {},
) {
    val books by viewModel.books.collectAsStateWithLifecycle()
    val currentlyReading by viewModel.currentlyReading.collectAsStateWithLifecycle()
    val sortField by viewModel.sortField.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val selectedCollectionId by viewModel.selectedCollectionId.collectAsStateWithLifecycle()
    val scanStatus by viewModel.scanStatus.collectAsStateWithLifecycle()
    val isScanning by viewModel.isScanning.collectAsStateWithLifecycle()
    val conversionStatus by viewModel.conversionStatus.collectAsStateWithLifecycle()
    val isConverting by viewModel.isConverting.collectAsStateWithLifecycle()
    val formatFilters by viewModel.formatFilters.collectAsStateWithLifecycle()
    val formatCounts by viewModel.formatCounts.collectAsStateWithLifecycle()
    val todaysPick by viewModel.todaysPick.collectAsStateWithLifecycle()
    val newArrivals by viewModel.newArrivals.collectAsStateWithLifecycle()
    val myShelf by viewModel.myShelf.collectAsStateWithLifecycle()
    val collectionBooks by viewModel.collectionBooks.collectAsStateWithLifecycle()
    val pinnedIds by viewModel.pinnedIds.collectAsStateWithLifecycle()

    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmingBulkDelete by remember { mutableStateOf(false) }
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
        confirmingBulkDelete = false
    }

    var showSearch by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showCrossSearch by remember { mutableStateOf(false) }
    var newCollectionName by remember { mutableStateOf("") }
    var showNewCollection by remember { mutableStateOf(false) }
    var longPressBook by remember { mutableStateOf<Book?>(null) }

    if (showSettings) {
        SettingsScreen(
            onBack = { showSettings = false },
            onScan = onRequestScan,
            isScanning = isScanning,
            onResetLibrary = { viewModel.resetLibrary() },
        )
        return
    }

    if (showCrossSearch) {
        CrossSearchScreen(
            onBack = { showCrossSearch = false },
            onOpenBook = { book ->
                showCrossSearch = false
                onBookClick(book)
            },
        )
        return
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? -> uri?.let { viewModel.importBook(it) } }

    // Derived from observed state so the shelves redraw the moment a filter,
    // search or collection result arrives. (Reading the view model's plain
    // getter here left the screen showing stale or empty lists.)
    val isSearching = searchQuery.isNotEmpty()
    val displayedBooks = when {
        searchQuery.length >= 2 -> searchResults
        selectedCollectionId != null -> collectionBooks
        else -> books
    }
    val selectedCollection = collections.find { it.id == selectedCollectionId }
    val showCurrentlyReading = !isSearching && selectedCollection == null
    val totalBooks = formatCounts.values.sum()
    // Only offer tabs for formats the library actually holds; EPUB and PDF always.
    val formatTabs = listOf("EPUB", "PDF") + listOf("TXT", "FB2").filter { (formatCounts[it] ?: 0) > 0 }
    val activeFormat = formatFilters.singleOrNull()?.takeIf { formatFilters.size == 1 }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),
    ) {
        // ── Title row ──────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = PAGE_PADDING, end = PAGE_PADDING - 6.dp, top = 16.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                text = selectedCollection?.name ?: "Library",
                style = CalmTypography.libraryTitle.copy(fontSize = 30.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!selectionMode) {
                HeaderWord(if (showSearch) "Done" else "Search") {
                    showSearch = !showSearch
                    if (!showSearch) viewModel.setSearch("")
                    showMore = false
                }
                Spacer(Modifier.width(6.dp))
                HeaderWord(if (showMore) "Close" else "More") {
                    showMore = !showMore
                    showSort = false
                }
            }
        }

        // ── Epigraph ───────────────────────────────────────────────────────
        // A line of the day under the title, like the quotation facing a
        // book's first page. Counts live in the tabs below, where they belong.
        val epigraph = remember { quoteForToday() }
        Row(
            Modifier.padding(horizontal = PAGE_PADDING).padding(top = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Text(
                text = if (selectedCollection != null)
                    "${displayedBooks.size} book${plural(displayedBooks.size)} on this shelf"
                else "“${epigraph.first}”  — ${epigraph.second}",
                style = TextStyle(
                    fontFamily = CalmFonts.serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Grey,
                ),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (selectedCollection != null) {
                Text(
                    "← All books",
                    style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 12.sp, color = Ink),
                    modifier = Modifier.clickable { viewModel.selectCollection(null) }.padding(start = 12.dp, top = 2.dp),
                )
            }
        }
        Spacer(Modifier.height(14.dp))

        // ── Tabs / selection bar ───────────────────────────────────────────
        if (selectionMode) {
            SelectionBar(
                count = selectedIds.size,
                confirming = confirmingBulkDelete,
                onRemove = { confirmingBulkDelete = true },
                onKeep = { confirmingBulkDelete = false },
                onConfirm = {
                    val all = (currentlyReading + books).distinctBy { it.id }
                    viewModel.deleteBooks(all.filter { it.id in selectedIds })
                    exitSelection()
                },
                onCancel = { exitSelection() },
            )
        } else if (selectedCollection == null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING),
                verticalAlignment = Alignment.Bottom,
            ) {
                FormatTab("All", totalBooks, activeFormat == null && formatFilters.isEmpty()) { viewModel.setFormatFilter(null) }
                formatTabs.forEach { f ->
                    Spacer(Modifier.width(20.dp))
                    FormatTab(f, formatCounts[f] ?: 0, activeFormat == f) { viewModel.setFormatFilter(f) }
                }
                Spacer(Modifier.weight(1f))
                Text(
                    text = if (showSort) "Sort ▴" else "${sortField.label()} ▾",
                    style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 13.sp, color = Grey),
                    modifier = Modifier
                        .clickable { showSort = !showSort; showMore = false }
                        .padding(bottom = 8.dp, start = 8.dp),
                )
            }
            HairlineRule()
            if (showSort) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Paper)
                        .padding(horizontal = PAGE_PADDING, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    SortField.entries.forEach { f ->
                        OptionChip(f.label(), sortField == f) {
                            viewModel.setSortField(f)
                            showSort = false
                        }
                    }
                }
                HairlineRule()
            }
        } else {
            HairlineRule()
        }

        // ── More panel ─────────────────────────────────────────────────────
        if (showMore && !selectionMode) {
            MorePanel(
                collections = collections,
                selectedCollectionId = selectedCollectionId,
                showNewCollection = showNewCollection,
                newCollectionName = newCollectionName,
                onNewCollectionName = { newCollectionName = it },
                onAdd = { filePicker.launch(BOOK_MIME_TYPES); showMore = false },
                onScan = { onRequestScan(); showMore = false },
                onSelect = { selectionMode = true; showMore = false },
                onCollection = { id -> viewModel.selectCollection(id); showMore = false },
                onStartNewCollection = { showNewCollection = true },
                onSaveCollection = {
                    if (newCollectionName.isNotBlank()) {
                        viewModel.createCollection(newCollectionName.trim())
                        newCollectionName = ""
                        showNewCollection = false
                    }
                },
                onFindInside = { showCrossSearch = true; showMore = false },
                onSettings = { showSettings = true; showMore = false },
            )
        }

        // ── Search field ───────────────────────────────────────────────────
        if (showSearch) {
            Box(Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING, vertical = 10.dp)) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearch(it) },
                    textStyle = TextStyle(fontFamily = CalmFonts.serif, fontSize = 17.sp, color = Ink),
                    cursorBrush = SolidColor(Ink),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    "Title or author…",
                                    style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 17.sp, color = LightGrey),
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(Ink))
            }
        }

        // ── Quiet status line ──────────────────────────────────────────────
        conversionStatus?.let { StatusLine(it, dismissable = !isConverting) { viewModel.clearConversionStatus() } }
        scanStatus?.let { StatusLine(it, dismissable = !isScanning) { viewModel.clearScanStatus() } }

        // ── Shelves ────────────────────────────────────────────────────────
        val libraryIsTrulyEmpty = totalBooks == 0 && currentlyReading.isEmpty()
        when {
            libraryIsTrulyEmpty && !isSearching -> {
                EmptyLibrary(
                    onAddBook = { filePicker.launch(BOOK_MIME_TYPES) },
                    onScanDevice = onRequestScan,
                )
            }
            displayedBooks.isEmpty() && isSearching -> {
                Box(Modifier.fillMaxWidth().padding(top = 64.dp), contentAlignment = Alignment.Center) {
                    Text("No books match “${searchQuery.trim()}”.", style = CalmTypography.emptyBody)
                }
            }
            displayedBooks.isEmpty() && !isSearching -> {
                NoMatchesHere(
                    formatFilters = formatFilters,
                    selectedCollection = selectedCollection,
                    onClearFilters = {
                        viewModel.clearFormatFilters()
                        viewModel.selectCollection(null)
                    },
                )
            }
            else -> {
                val shelfLabel = when {
                    isSearching -> "Found"
                    selectedCollection != null -> selectedCollection.name
                    activeFormat != null -> "$activeFormat books"
                    else -> "All books"
                }
                ShelfGrid(
                    books = displayedBooks,
                    currentlyReading = if (showCurrentlyReading) currentlyReading else emptyList(),
                    shelfLabel = shelfLabel,
                    myShelf = if (showCurrentlyReading && activeFormat == null) myShelf else emptyList(),
                    showShelfHint = showCurrentlyReading && activeFormat == null && myShelf.isEmpty(),
                    todaysPick = if (showCurrentlyReading && activeFormat == null && !selectionMode) todaysPick else null,
                    newArrivals = if (showCurrentlyReading && activeFormat == null && sortField != SortField.DATE_ADDED) newArrivals else emptyList(),
                    onBookClick = { book ->
                        if (selectionMode) {
                            selectedIds = if (book.id in selectedIds) selectedIds - book.id else selectedIds + book.id
                        } else onBookClick(book)
                    },
                    onBookLongClick = { book ->
                        if (selectionMode) {
                            selectedIds = if (book.id in selectedIds) selectedIds - book.id else selectedIds + book.id
                        } else longPressBook = book
                    },
                    selectionMode = selectionMode,
                    selectedIds = selectedIds,
                )
            }
        }
    }

    longPressBook?.let { b ->
        BookActionSheet(
            book = b,
            collections = collections,
            onDismiss = { longPressBook = null },
            onOpen = { longPressBook = null; onBookClick(b) },
            pinned = b.id in pinnedIds,
            onTogglePin = { viewModel.togglePin(b); longPressBook = null },
            onMarkFinished = { viewModel.markBookFinished(b); longPressBook = null },
            onRemoveFromReading = { viewModel.removeFromCurrentlyReading(b); longPressBook = null },
            onDelete = { viewModel.deleteBook(b); longPressBook = null },
            onAddToCollection = { c -> viewModel.addBookToCollection(b.id, c.id); longPressBook = null },
            onConvertToEpub = { viewModel.convertPdfToEpub(b); longPressBook = null },
            onRename = { title, author -> viewModel.renameBook(b, title, author); longPressBook = null },
            onSelectMultiple = {
                longPressBook = null
                selectionMode = true
                selectedIds = setOf(b.id)
            },
        )
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

@Composable
private fun HairlineRule() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Hairline))
}

@Composable
private fun HeaderWord(text: String, onClick: () -> Unit) {
    Text(
        text = text,
        style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Ink),
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 6.dp),
    )
}

/** Kindle-style tab: the selected word is black with a firm underline. */
@Composable
private fun FormatTab(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    Column(Modifier.width(IntrinsicSize.Max).clickable(onClick = onClick)) {
        Row(Modifier.padding(bottom = 6.dp), verticalAlignment = Alignment.Bottom) {
            Text(
                text = label,
                style = TextStyle(
                    fontFamily = CalmFonts.sans,
                    fontSize = 14.sp,
                    letterSpacing = 0.3.sp,
                    color = if (selected) Ink else Grey,
                ),
            )
            if (count > 0) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = count.toString(),
                    style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 10.sp, color = if (selected) Grey else LightGrey),
                    modifier = Modifier.padding(bottom = 1.dp),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected) Ink else Color.Transparent))
    }
}

@Composable
private fun StatusLine(text: String, dismissable: Boolean, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Paper)
            .padding(horizontal = PAGE_PADDING, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, color = Grey),
            modifier = Modifier.weight(1f),
        )
        if (dismissable) {
            Text(
                "Dismiss",
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 12.sp, color = Ink),
                modifier = Modifier.clickable(onClick = onDismiss).padding(start = 12.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
    HairlineRule()
}

@Composable
private fun SelectionBar(
    count: Int,
    confirming: Boolean,
    onRemove: () -> Unit,
    onKeep: () -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Paper)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = if (count == 0) "Tap books to select them" else "$count selected",
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Ink),
                modifier = Modifier.weight(1f),
            )
            Text(
                "Remove",
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = if (count == 0) LightGrey else Ink),
                modifier = Modifier.clickable(enabled = count > 0, onClick = onRemove).padding(horizontal = 10.dp, vertical = 4.dp),
            )
            Text(
                "Cancel",
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Grey),
                modifier = Modifier.clickable(onClick = onCancel).padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
            )
        }
        if (confirming) {
            Column(Modifier.fillMaxWidth().padding(horizontal = PAGE_PADDING).padding(bottom = 14.dp)) {
                Text(
                    "Remove $count book${plural(count)} from your library? The files in your Books folder stay where they are; they just won't show here again.",
                    style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 13.sp, lineHeight = 18.sp, color = Ink),
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ButtonWord("Keep them", filled = false, modifier = Modifier.weight(1f), onClick = onKeep)
                    ButtonWord("Remove", filled = true, modifier = Modifier.weight(1f), onClick = onConfirm)
                }
            }
        }
    }
    HairlineRule()
}

@Composable
private fun ButtonWord(text: String, filled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Text(
        text = text,
        style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = if (filled) Color.White else Ink),
        textAlign = TextAlign.Center,
        modifier = modifier
            .background(if (filled) Ink else Color.White)
            .then(if (filled) Modifier else Modifier.border1(Ink))
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    )
}

private fun Modifier.border1(color: Color) = this.border(1.dp, color)

@Composable
private fun MorePanel(
    collections: List<Collection>,
    selectedCollectionId: Long?,
    showNewCollection: Boolean,
    newCollectionName: String,
    onNewCollectionName: (String) -> Unit,
    onAdd: () -> Unit,
    onScan: () -> Unit,
    onSelect: () -> Unit,
    onCollection: (Long?) -> Unit,
    onStartNewCollection: () -> Unit,
    onSaveCollection: () -> Unit,
    onFindInside: () -> Unit,
    onSettings: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Paper).padding(horizontal = PAGE_PADDING, vertical = 6.dp)) {
        MoreRow("Add a book…", onAdd)
        MoreRow("Scan device for books", onScan)
        MoreRow("Select books to remove…", onSelect)
        MoreRow("Find a phrase inside all books", onFindInside)
        Spacer(Modifier.height(10.dp))
        Text("SHELVES", style = CalmTypography.sectionHeader.copy(color = Grey))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OptionChip("All", selectedCollectionId == null) { onCollection(null) }
            collections.forEach { c -> OptionChip(c.name, selectedCollectionId == c.id) { onCollection(c.id) } }
            OptionChip("+ New", false, onStartNewCollection)
        }
        if (showNewCollection) {
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicTextField(
                    value = newCollectionName,
                    onValueChange = onNewCollectionName,
                    textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Ink),
                    cursorBrush = SolidColor(Ink),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (newCollectionName.isEmpty()) Text("Shelf name", style = CalmTypography.controlValue)
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text("Save", style = CalmTypography.controlLabel, modifier = Modifier.clickable(onClick = onSaveCollection))
            }
        }
        Spacer(Modifier.height(12.dp))
        HairlineRule()
        MoreRow("Settings", onSettings)
    }
    HairlineRule()
}

@Composable
private fun MoreRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 15.sp, color = Ink),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp),
    )
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Ink else Color.White)
            .then(if (selected) Modifier else Modifier.border1(Color(0xFFBBBBBB)))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = CalmTypography.controlLabel.copy(color = if (selected) Color.White else Ink, fontSize = 12.sp),
        )
    }
}

/**
 * Long-press sheet for one book: the cover and title up top so you know which
 * book you're acting on, then a short list of plain-word actions.
 */
@Composable
private fun BookActionSheet(
    book: Book,
    collections: List<Collection>,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    pinned: Boolean,
    onTogglePin: () -> Unit,
    onMarkFinished: () -> Unit,
    onRemoveFromReading: () -> Unit,
    onDelete: () -> Unit,
    onAddToCollection: (Collection) -> Unit,
    onConvertToEpub: () -> Unit,
    onRename: (title: String, author: String) -> Unit,
    onSelectMultiple: () -> Unit = {},
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingCollection by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editTitle by remember(book.id) { mutableStateOf(book.displayTitle) }
    var editAuthor by remember(book.id) { mutableStateOf(book.author) }
    Box(
        modifier = Modifier.fillMaxSize().background(Color(0x55000000)).clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .clickable(enabled = false) {}
                .padding(horizontal = PAGE_PADDING, vertical = 18.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BookCover(book = book, width = 44.dp, height = 66.dp, showFormatTag = false)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.displayTitle, style = CalmTypography.bookTitle.copy(fontSize = 16.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    val meta = listOf(book.displayAuthor, book.format.label).filter { it.isNotEmpty() }.joinToString("  ·  ")
                    Text(meta, style = CalmTypography.metadata.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Spacer(Modifier.height(14.dp))
            HairlineRule()
            when {
                editing -> {
                    Spacer(Modifier.height(12.dp))
                    Text("Title", style = CalmTypography.metadata.copy(fontSize = 10.sp))
                    BasicTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        textStyle = TextStyle(fontFamily = CalmFonts.serif, fontSize = 16.sp, color = Ink),
                        cursorBrush = SolidColor(Ink),
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    HairlineRule()
                    Spacer(Modifier.height(12.dp))
                    Text("Author", style = CalmTypography.metadata.copy(fontSize = 10.sp))
                    BasicTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Ink),
                        cursorBrush = SolidColor(Ink),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        decorationBox = { inner ->
                            Box {
                                if (editAuthor.isBlank()) Text("(none)", style = CalmTypography.controlValue)
                                inner()
                            }
                        },
                    )
                    HairlineRule()
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ButtonWord("Cancel", filled = false, modifier = Modifier.weight(1f)) { editing = false }
                        ButtonWord("Save", filled = true, modifier = Modifier.weight(1f)) {
                            if (editTitle.isNotBlank()) onRename(editTitle.trim(), editAuthor.trim())
                        }
                    }
                }
                confirmingDelete -> {
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "Remove “${book.displayTitle}” from your library? The file in your Books folder stays where it is; it just won't show here again.",
                        style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 13.sp, lineHeight = 18.sp, color = Ink),
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ButtonWord("Keep it", filled = false, modifier = Modifier.weight(1f)) { confirmingDelete = false }
                        ButtonWord("Remove", filled = true, modifier = Modifier.weight(1f), onClick = onDelete)
                    }
                }
                pickingCollection -> {
                    if (collections.isEmpty()) {
                        Text("No shelves yet. Make one under More.", style = CalmTypography.emptyBody, modifier = Modifier.padding(vertical = 14.dp))
                    } else {
                        collections.forEach { c ->
                            SheetRow(c.name) { onAddToCollection(c) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Back", style = CalmTypography.controlLabel.copy(color = Grey),
                        modifier = Modifier.clickable { pickingCollection = false }.padding(vertical = 8.dp))
                }
                else -> {
                    val actions = listOfNotNull(
                        "Open" to onOpen,
                        (if (pinned) "Take off my shelf" else "Pin to my shelf") to onTogglePin,
                        "Edit title or author…" to { editing = true },
                        if (!book.isCurrentlyReading || book.progress < 1f) "Mark as finished" to onMarkFinished else null,
                        if (book.isCurrentlyReading) "Take off Reading now" to onRemoveFromReading else null,
                        if (book.format == BookFormat.PDF) "Convert to EPUB (reflowable)" to onConvertToEpub else null,
                        "Put on a shelf…" to { pickingCollection = true },
                        "Select more books…" to onSelectMultiple,
                        "Remove from library…" to { confirmingDelete = true },
                    )
                    actions.forEach { (label, action) -> SheetRow(label) { action() } }
                    Spacer(Modifier.height(8.dp))
                    Text("Cancel", style = CalmTypography.controlLabel.copy(color = Grey),
                        modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 16.sp, color = Ink),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 13.dp),
    )
    HairlineRule()
}
