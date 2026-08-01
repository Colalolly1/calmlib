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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.SortField
import com.calmlib.reader.ui.theme.CalmFonts
import com.calmlib.reader.ui.theme.CalmTypography

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

    // Bulk-selection mode: tap toggles membership instead of opening books.
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var confirmingBulkDelete by remember { mutableStateOf(false) }
    fun exitSelection() {
        selectionMode = false
        selectedIds = emptySet()
        confirmingBulkDelete = false
    }

    var showSearch by remember { mutableStateOf(false) }
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
    ) { uri: Uri? ->
        uri?.let { viewModel.importBook(it) }
    }

    val displayedBooks = viewModel.displayedBooks
    val showCurrentlyReading = !viewModel.isSearching && selectedCollectionId == null

    var showLibraryOptions by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding(),   // keep content below the Mudita's time/battery row
    ) {
        // Header — tappable to reveal extra options. Today's date sits underneath
        // the title in small italic serif, giving the library a "frontispiece" feel.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 2.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (selectedCollectionId != null) {
                    val collection = collections.find { it.id == selectedCollectionId }
                    Text(
                        text = collection?.name ?: "Collection",
                        style = CalmTypography.libraryTitle,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Library",
                        style = CalmTypography.controlLabel,
                        modifier = Modifier.clickable { viewModel.selectCollection(null) },
                    )
                } else {
                    Text(
                        text = "Library",
                        style = CalmTypography.libraryTitle,
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showLibraryOptions = !showLibraryOptions },
                    )
                    Text(
                        text = if (showLibraryOptions) "Close" else "···",
                        style = CalmTypography.controlLabel.copy(color = Color(0xFF666666), fontSize = 18.sp),
                        modifier = Modifier
                            .clickable { showLibraryOptions = !showLibraryOptions }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
            if (selectedCollectionId == null) {
                val count = books.size
                val countLabel = when {
                    count == 0 -> ""
                    formatFilters.isNotEmpty() -> "$count shown · "
                    else -> "$count book${if (count == 1) "" else "s"} · "
                }
                Text(
                    text = countLabel + remember { todaysDate() },
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = CalmFonts.serif,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                        fontSize = 12.sp,
                        color = Color(0xFF888888),
                    ),
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }

        if (selectionMode) {
            // Selection toolbar replaces the normal one while picking books.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF4F4F4))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${selectedIds.size} selected",
                    style = CalmTypography.controlLabel.copy(fontSize = 14.sp),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = "Remove",
                    style = CalmTypography.controlLabel.copy(
                        color = if (selectedIds.isEmpty()) Color(0xFFBBBBBB) else Color(0xFF8B0000),
                    ),
                    modifier = Modifier
                        .clickable(enabled = selectedIds.isNotEmpty()) { confirmingBulkDelete = true }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Text(
                    text = "Cancel",
                    style = CalmTypography.controlLabel,
                    modifier = Modifier
                        .clickable { exitSelection() }
                        .padding(start = 10.dp, top = 4.dp, bottom = 4.dp),
                )
            }
            if (confirmingBulkDelete) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFFFAFAFA))
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                ) {
                    Text(
                        "Remove ${selectedIds.size} book${if (selectedIds.size == 1) "" else "s"} from your library? " +
                            "Files in your Books folder are kept, but they won't be re-imported.",
                        style = CalmTypography.body.copy(fontSize = 13.sp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Keep them", style = CalmTypography.controlLabel,
                            modifier = Modifier.weight(1f).background(Color(0xFFEEEEEE))
                                .clickable { confirmingBulkDelete = false }.padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("Remove", style = CalmTypography.controlLabel.copy(color = Color.White),
                            modifier = Modifier.weight(1f).background(Color(0xFF8B0000))
                                .clickable {
                                    val all = (currentlyReading + books).distinctBy { it.id }
                                    viewModel.deleteBooks(all.filter { it.id in selectedIds })
                                    exitSelection()
                                }.padding(12.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }
        } else {
            // Minimal toolbar — only the three primary actions.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = "Add",
                    style = CalmTypography.controlLabel,
                    modifier = Modifier.clickable {
                        filePicker.launch(arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "text/plain",
                            "application/x-fictionbook+xml",
                            "application/octet-stream",
                        ))
                    },
                )

                Text(
                    text = if (showSearch) "Done" else "Search",
                    style = CalmTypography.controlLabel,
                    modifier = Modifier.clickable {
                        showSearch = !showSearch
                        if (!showSearch) viewModel.setSearch("")
                    },
                )

                Spacer(Modifier.weight(1f))

                Text(
                    text = "Settings",
                    style = CalmTypography.controlLabel.copy(color = Color(0xFF999999)),
                    modifier = Modifier.clickable { showSettings = true },
                )
            }
        }

        // Expandable library-options panel, hidden by default.
        if (showLibraryOptions) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8F8F8))
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                // Sort
                Text("SORT BY", style = CalmTypography.sectionHeader)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SortField.entries.forEach { f ->
                        val label = when (f) {
                            SortField.TITLE -> "Title"
                            SortField.AUTHOR -> "Author"
                            SortField.DATE_ADDED -> "Added"
                            SortField.LAST_READ -> "Recent"
                            SortField.FORMAT -> "Format"
                        }
                        OptionChip(
                            label = label,
                            selected = sortField == f,
                            onClick = { viewModel.setSortField(f) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                // Format filters — toggleable set so EPUB and PDF can be shown together.
                Text("FORMAT (tap to combine)", style = CalmTypography.sectionHeader)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OptionChip(
                        label = "All",
                        selected = formatFilters.isEmpty(),
                        onClick = { viewModel.clearFormatFilters() },
                    )
                    listOf("EPUB", "PDF", "TXT", "FB2").forEach { f ->
                        OptionChip(
                            label = f,
                            selected = f in formatFilters,
                            onClick = { viewModel.toggleFormatFilter(f) },
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                // Bulk selection entry
                Text(
                    "Select books…",
                    style = CalmTypography.controlLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showLibraryOptions = false
                            selectionMode = true
                        }
                        .padding(vertical = 10.dp),
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFDDDDDD)))
                Spacer(Modifier.height(14.dp))
                // Collections
                Text("COLLECTIONS", style = CalmTypography.sectionHeader)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OptionChip(
                        label = "All",
                        selected = selectedCollectionId == null,
                        onClick = { viewModel.selectCollection(null) },
                    )
                    collections.forEach { c ->
                        OptionChip(
                            label = c.name,
                            selected = selectedCollectionId == c.id,
                            onClick = { viewModel.selectCollection(c.id) },
                        )
                    }
                    OptionChip(
                        label = "+ New",
                        selected = false,
                        onClick = { showNewCollection = true },
                    )
                }
                Spacer(Modifier.height(14.dp))
                Text(
                    "Find across all books",
                    style = CalmTypography.controlLabel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            showLibraryOptions = false
                            showCrossSearch = true
                        }
                        .padding(vertical = 10.dp),
                )
            }
        }

        // Search field
        if (showSearch) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                BasicTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearch(it) },
                    textStyle = TextStyle(
                        fontFamily = CalmFonts.sans,
                        fontSize = 15.sp,
                        color = Color.Black,
                    ),
                    cursorBrush = SolidColor(Color.Black),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    decorationBox = { inner ->
                        Box {
                            if (searchQuery.isEmpty()) {
                                Text(
                                    text = "Search by title or author",
                                    style = CalmTypography.controlValue,
                                )
                            }
                            inner()
                        }
                    },
                )
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(Color.Black)
                )
            }
        }

        // (Old Filter chips + Collections bar removed — both surfaced under the "···"
        // library-options panel above. Keeping the new-collection composer below.)

        // New collection input
        if (showNewCollection) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BasicTextField(
                    value = newCollectionName,
                    onValueChange = { newCollectionName = it },
                    textStyle = TextStyle(
                        fontFamily = CalmFonts.sans,
                        fontSize = 14.sp,
                        color = Color.Black,
                    ),
                    cursorBrush = SolidColor(Color.Black),
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (newCollectionName.isEmpty()) {
                                Text("Collection name", style = CalmTypography.controlValue)
                            }
                            inner()
                        }
                    },
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = "Save",
                    style = CalmTypography.controlLabel,
                    modifier = Modifier.clickable {
                        if (newCollectionName.isNotBlank()) {
                            viewModel.createCollection(newCollectionName.trim())
                            newCollectionName = ""
                            showNewCollection = false
                        }
                    },
                )
            }
        }

        // Divider
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(Color(0xFFEEEEEE))
        )

        // Conversion status banner
        conversionStatus?.let { status ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0F0F0))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status,
                    style = CalmTypography.controlValue,
                    modifier = Modifier.weight(1f),
                )
                if (!isConverting) {
                    Text(
                        text = "Dismiss",
                        style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                        modifier = Modifier.clickable { viewModel.clearConversionStatus() },
                    )
                }
            }
        }

        // Scan status banner
        scanStatus?.let { status ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF8F8F8))
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = status,
                    style = CalmTypography.controlValue,
                    modifier = Modifier.weight(1f),
                )
                if (!isScanning) {
                    Text(
                        text = "Dismiss",
                        style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                        modifier = Modifier.clickable { viewModel.clearScanStatus() },
                    )
                }
            }
        }

        // Content
        // Three states:
        //   1) Library truly empty (no books at all): bookshop-style welcome screen.
        //   2) Library has books but the current filter/collection/search hides them
        //      all: small "no matches here" line, but the welcome screen stays away.
        //   3) Books to show: the shelves.
        val libraryIsTrulyEmpty = books.isEmpty() && currentlyReading.isEmpty()
        when {
            libraryIsTrulyEmpty && !viewModel.isSearching -> {
                EmptyLibrary(
                    onAddBook = {
                        filePicker.launch(arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "text/plain",
                            "application/x-fictionbook+xml",
                            "application/octet-stream",
                        ))
                    },
                    onScanDevice = onRequestScan,
                )
            }
            displayedBooks.isEmpty() && viewModel.isSearching -> {
                // Active search with zero hits — without this branch the screen
                // fell through to an empty ShelfGrid showing just the quote footer,
                // which read as a broken page.
                Box(
                    Modifier.fillMaxWidth().padding(top = 64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No books match “${searchQuery.trim()}”.",
                        style = CalmTypography.emptyBody,
                    )
                }
            }
            displayedBooks.isEmpty() && !viewModel.isSearching -> {
                NoMatchesHere(
                    formatFilters = formatFilters,
                    selectedCollection = collections.find { it.id == selectedCollectionId },
                    onClearFilters = {
                        viewModel.clearFormatFilters()
                        viewModel.selectCollection(null)
                    },
                )
            }
            else -> {
                ShelfGrid(
                    books = displayedBooks,
                    currentlyReading = if (showCurrentlyReading) currentlyReading else emptyList(),
                    onBookClick = { book ->
                        if (selectionMode) {
                            selectedIds = if (book.id in selectedIds) selectedIds - book.id else selectedIds + book.id
                        } else {
                            onBookClick(book)
                        }
                    },
                    onBookLongClick = { book ->
                        if (selectionMode) {
                            selectedIds = if (book.id in selectedIds) selectedIds - book.id else selectedIds + book.id
                        } else {
                            longPressBook = book
                        }
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
            onMarkFinished = { viewModel.markBookFinished(b); longPressBook = null },
            onRemoveFromReading = { viewModel.removeFromCurrentlyReading(b); longPressBook = null },
            onDelete = { viewModel.deleteBook(b); longPressBook = null },
            onAddToCollection = { c ->
                viewModel.addBookToCollection(b.id, c.id)
                longPressBook = null
            },
            onConvertToEpub = {
                viewModel.convertPdfToEpub(b)
                longPressBook = null
            },
            onRename = { title, author ->
                viewModel.renameBook(b, title, author)
                longPressBook = null
            },
            onSelectMultiple = {
                longPressBook = null
                selectionMode = true
                selectedIds = setOf(b.id)
            },
        )
    }
}

@Composable
private fun BookActionSheet(
    book: Book,
    collections: List<com.calmlib.reader.data.model.Collection>,
    onDismiss: () -> Unit,
    onMarkFinished: () -> Unit,
    onRemoveFromReading: () -> Unit,
    onDelete: () -> Unit,
    onAddToCollection: (com.calmlib.reader.data.model.Collection) -> Unit,
    onConvertToEpub: () -> Unit,
    onRename: (title: String, author: String) -> Unit,
    onSelectMultiple: () -> Unit = {},
) {
    var confirmingDelete by remember { mutableStateOf(false) }
    var pickingCollection by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var editTitle by remember(book.id) { mutableStateOf(book.title) }
    var editAuthor by remember(book.id) { mutableStateOf(book.author) }
    Box(
        modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Color.White).padding(20.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.Black))
            Spacer(Modifier.height(12.dp))
            Text(book.title, style = CalmTypography.bookTitle, maxLines = 2)
            if (book.author.isNotEmpty()) {
                Text(book.author, style = CalmTypography.metadata)
            }
            Spacer(Modifier.height(16.dp))
            when {
                editing -> {
                    Text("EDIT DETAILS", style = CalmTypography.sectionHeader)
                    Spacer(Modifier.height(8.dp))
                    Text("Title", style = CalmTypography.metadata.copy(fontSize = 10.sp))
                    BasicTextField(
                        value = editTitle,
                        onValueChange = { editTitle = it },
                        textStyle = TextStyle(fontFamily = CalmFonts.serif, fontSize = 16.sp, color = Color.Black),
                        cursorBrush = SolidColor(Color.Black),
                        singleLine = false,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    )
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFCCCCCC)))
                    Spacer(Modifier.height(12.dp))
                    Text("Author", style = CalmTypography.metadata.copy(fontSize = 10.sp))
                    BasicTextField(
                        value = editAuthor,
                        onValueChange = { editAuthor = it },
                        textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Color.Black),
                        cursorBrush = SolidColor(Color.Black),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        decorationBox = { inner ->
                            Box {
                                if (editAuthor.isBlank()) Text("(none)", style = CalmTypography.controlValue)
                                inner()
                            }
                        },
                    )
                    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFCCCCCC)))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Cancel", style = CalmTypography.controlLabel,
                            modifier = Modifier.weight(1f).background(Color(0xFFF0F0F0))
                                .clickable { editing = false }.padding(14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text("Save", style = CalmTypography.controlLabel.copy(color = Color.White),
                            modifier = Modifier.weight(1f).background(Color.Black)
                                .clickable {
                                    if (editTitle.isNotBlank()) onRename(editTitle.trim(), editAuthor.trim())
                                }.padding(14.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
                confirmingDelete -> {
                    Text("Delete this book? This removes the file from internal storage but leaves any external copy alone.",
                        style = CalmTypography.body.copy(fontSize = 13.sp))
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Cancel", style = CalmTypography.controlLabel,
                            modifier = Modifier.weight(1f).clickable { confirmingDelete = false }.padding(14.dp))
                        Text("Delete", style = CalmTypography.controlLabel.copy(color = Color.White),
                            modifier = Modifier.weight(1f).background(Color.Black).clickable { onDelete() }.padding(14.dp))
                    }
                }
                pickingCollection -> {
                    if (collections.isEmpty()) {
                        Text("No collections yet. Create one from the toolbar.", style = CalmTypography.emptyBody)
                    } else {
                        collections.forEach { c ->
                            Text(c.name, style = CalmTypography.controlLabel,
                                modifier = Modifier.fillMaxWidth().clickable { onAddToCollection(c) }.padding(vertical = 14.dp))
                            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFEEEEEE)))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Back", style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                        modifier = Modifier.clickable { pickingCollection = false })
                }
                else -> {
                    val actions = listOfNotNull(
                        "Edit title or author…" to { editing = true },
                        if (!book.isCurrentlyReading || book.progress < 1f) "Mark as finished" to onMarkFinished else null,
                        if (book.isCurrentlyReading) "Remove from Currently Reading" to onRemoveFromReading else null,
                        if (book.format == com.calmlib.reader.data.model.BookFormat.PDF)
                            "Convert to EPUB (reflowable)" to onConvertToEpub else null,
                        "Add to collection…" to { pickingCollection = true },
                        "Select multiple…" to onSelectMultiple,
                        "Delete from library…" to { confirmingDelete = true },
                    )
                    actions.forEach { (label, action) ->
                        Text(label, style = CalmTypography.controlLabel.copy(fontSize = 16.sp),
                            modifier = Modifier.fillMaxWidth().clickable { action() }.padding(vertical = 14.dp))
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFEEEEEE)))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Cancel", style = CalmTypography.controlLabel.copy(color = Color(0xFF999999)),
                        modifier = Modifier.clickable(onClick = onDismiss).padding(vertical = 8.dp))
                }
            }
        }
    }
}

/** "Wednesday · 14 May" style — small italic subtitle under the Library header. */
private fun todaysDate(): String {
    val fmt = java.text.SimpleDateFormat("EEEE · d MMMM", java.util.Locale.getDefault())
    return fmt.format(java.util.Date())
}

@Composable
private fun OptionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .background(if (selected) Color.Black else Color(0xFFEEEEEE))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = CalmTypography.controlLabel.copy(
                color = if (selected) Color.White else Color.Black,
                fontSize = 12.sp,
            ),
        )
    }
}
