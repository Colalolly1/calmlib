package com.calmlib.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.ui.theme.CalmFonts
import com.calmlib.reader.ui.theme.CalmTypography

// Two books to a shelf: covers big enough to actually read, the way they'd sit
// on a real shelf at arm's length. Cover width is derived from the screen so it
// fills whatever device this runs on.
private const val BOOKS_PER_SHELF = 2
private val SHELF_SIDE_PADDING = 22.dp
private val COVER_GAP = 26.dp
private val MAX_COVER_WIDTH = 180.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfGrid(
    books: List<Book>,
    currentlyReading: List<Book>,
    shelfLabel: String,
    myShelf: List<Book> = emptyList(),
    showShelfHint: Boolean = false,
    todaysPick: Book? = null,
    newArrivals: List<Book> = emptyList(),
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit = {},
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val readingIds = currentlyReading.map { it.id }.toSet()
    val otherBooks = books.filter { it.id !in readingIds }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val coverWidth = ((maxWidth - SHELF_SIDE_PADDING * 2 - COVER_GAP) / BOOKS_PER_SHELF)
            .coerceAtMost(MAX_COVER_WIDTH)
        val coverHeight = coverWidth * 1.5f

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = 4.dp, bottom = 16.dp),
        ) {
            if (myShelf.isNotEmpty()) {
                item {
                    ShelfHeader("My shelf")
                    MyShelfRow(
                        books = myShelf,
                        coverWidth = coverWidth * 0.6f,
                        coverHeight = coverHeight * 0.6f,
                        onBookClick = onBookClick,
                        onBookLongClick = onBookLongClick,
                        selectionMode = selectionMode,
                        selectedIds = selectedIds,
                    )
                    currentlyReading.firstOrNull()?.let { latest ->
                        ResumeLine(latest, onClick = { onBookClick(latest) })
                    }
                }
            } else if (showShelfHint) {
                item {
                    ShelfHeader("My shelf")
                    Text(
                        text = "Empty for now. Long-press any book and choose “Pin to my shelf” to stand it here.",
                        style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF777777)),
                        modifier = Modifier.padding(horizontal = SHELF_SIDE_PADDING),
                    )
                    Spacer(Modifier.height(16.dp))
                    ShelfBoard()
                    Spacer(Modifier.height(14.dp))
                }
            }

            if (todaysPick != null && todaysPick.id !in readingIds) {
                item {
                    if (myShelf.isNotEmpty() || showShelfHint) Spacer(Modifier.height(10.dp))
                    ShelfHeader("From the shelf today")
                    TodaysPickCard(
                        book = todaysPick,
                        coverWidth = coverWidth * 0.62f,
                        coverHeight = coverHeight * 0.62f,
                        onClick = { onBookClick(todaysPick) },
                        onLongClick = { onBookLongClick(todaysPick) },
                    )
                }
            }

            if (newArrivals.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(10.dp))
                    ShelfHeader("New arrivals")
                }
                items(newArrivals.chunked(BOOKS_PER_SHELF)) { row ->
                    Shelf(row, coverWidth, coverHeight, onBookClick, onBookLongClick, selectionMode, selectedIds)
                }
            }

            if (otherBooks.isNotEmpty()) {
                item {
                    if (myShelf.isNotEmpty() || showShelfHint || todaysPick != null || newArrivals.isNotEmpty()) Spacer(Modifier.height(10.dp))
                    ShelfHeader(shelfLabel)
                }
                items(otherBooks.chunked(BOOKS_PER_SHELF)) { row ->
                    Shelf(row, coverWidth, coverHeight, onBookClick, onBookLongClick, selectionMode, selectedIds)
                }
            }

            item {
                Spacer(Modifier.height(24.dp))
                Colophon(books.size)
                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

/** Small black/white checkbox drawn over a cover corner during selection mode. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(26.dp)
            .background(if (selected) Color.Black else Color.White)
            .border(1.5.dp, Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 16.sp))
        }
    }
}

/**
 * The reader's own shelf: a row of smaller covers standing on one plank,
 * scrolling sideways if it fills up. Progress shows as the thin bar on each
 * cover, so a glance tells you where you are in everything.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MyShelfRow(
    books: List<Book>,
    coverWidth: Dp,
    coverHeight: Dp,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    selectionMode: Boolean,
    selectedIds: Set<Long>,
) {
    Column {
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = SHELF_SIDE_PADDING),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            items(books, key = { it.id }) { book ->
                ShelfBook(book, coverWidth, coverHeight, onBookClick, onBookLongClick, selectionMode, book.id in selectedIds)
            }
        }
        ShelfBoard()
    }
}

/** One line under the shelf: the book you were last in, and the word that takes you back. */
@Composable
private fun ResumeLine(book: Book, onClick: () -> Unit) {
    val pct = (book.progress * 100).toInt().coerceIn(0, 100)
    val where = when {
        book.totalPages > 1 && book.currentPage > 0 -> "Page ${book.currentPage} of ${book.totalPages}"
        pct > 0 -> "$pct% read"
        else -> "Just started"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = SHELF_SIDE_PADDING, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = book.displayTitle,
                style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 16.sp, color = Color.Black),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = listOfNotNull(where, lastOpened(book.lastRead)).joinToString("  ·  "),
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 11.sp, color = Color(0xFF777777)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(14.dp))
        Box(
            Modifier
                .background(Color.Black)
                .padding(horizontal = 20.dp, vertical = 9.dp),
        ) {
            Text("Resume", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 13.sp))
        }
    }
    Spacer(Modifier.height(6.dp))
}

/**
 * Section header — italic serif label between two hairlines, like a running
 * head in a printed book.
 */
@Composable
private fun ShelfHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = SHELF_SIDE_PADDING, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp).height(0.5.dp).background(Color(0xFF888888)))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontStyle = FontStyle.Italic,
                fontSize = 15.sp,
                color = Color(0xFF333333),
            ),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
    }
}

/**
 * The shelf itself: a dark top edge, a pale front face and a thin underside
 * shadow. Runs the full width so every shelf reads as one plank of wood.
 */
@Composable
private fun ShelfBoard() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.5.dp).background(Color(0xFF1A1A1A)))
        Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFFE4E4E4)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9C9C9C)))
    }
}

/**
 * The librarian's counter: one book you've never opened, offered for today.
 * Small cover, a line of invitation, and a single word to begin.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodaysPickCard(
    book: Book,
    coverWidth: Dp,
    coverHeight: Dp,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = SHELF_SIDE_PADDING),
            verticalAlignment = Alignment.Bottom,
        ) {
            BookCover(book = book, width = coverWidth, height = coverHeight)
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).padding(bottom = 8.dp)) {
                Text(
                    text = book.displayTitle,
                    style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 16.sp, lineHeight = 21.sp, color = Color.Black),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 12.sp, color = Color(0xFF555555)),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Still unopened. Perhaps today.",
                    style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 12.sp, color = Color(0xFF777777)),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .border(1.dp, Color.Black)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 22.dp, vertical = 9.dp),
                ) {
                    Text("Begin", style = CalmTypography.controlLabel.copy(fontSize = 13.sp))
                }
            }
        }
        ShelfBoard()
        Spacer(Modifier.height(18.dp))
    }
}

/** "Last opened yesterday" — warm, approximate, no clock-watching. */
private fun lastOpened(ts: Long): String? {
    if (ts <= 0L) return null
    val days = ((System.currentTimeMillis() - ts) / (24L * 60 * 60 * 1000)).toInt()
    return when {
        days <= 0 -> "Opened today"
        days == 1 -> "Last opened yesterday"
        days < 7 -> "Last opened $days days ago"
        days < 30 -> "Last opened ${days / 7} week${if (days / 7 == 1) "" else "s"} ago"
        else -> "Last opened " + java.text.SimpleDateFormat("d MMMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}

/** Closing ornament — the little printer's mark at the end of a book. */
@Composable
private fun Colophon(count: Int) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(0.5f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
            Text(" ❧ ", style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 15.sp, color = Color(0xFF555555)))
            Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = "$count book${if (count == 1) "" else "s"} on these shelves",
            style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 12.sp, color = Color(0xFF777777)),
        )
    }
}

/**
 * One shelf: covers standing bottom-aligned on the plank, labels underneath
 * like the little cards in a bookshop window.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Shelf(
    books: List<Book>,
    coverWidth: Dp,
    coverHeight: Dp,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHELF_SIDE_PADDING),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            books.forEach { book ->
                ShelfBook(book, coverWidth, coverHeight, onBookClick, onBookLongClick, selectionMode, book.id in selectedIds)
            }
            repeat(BOOKS_PER_SHELF - books.size) { Spacer(Modifier.width(coverWidth)) }
        }
        ShelfBoard()
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SHELF_SIDE_PADDING),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            books.forEach { book -> BookLabel(book, coverWidth) }
            repeat(BOOKS_PER_SHELF - books.size) { Spacer(Modifier.width(coverWidth)) }
        }
        Spacer(Modifier.height(30.dp))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBook(
    book: Book,
    coverWidth: Dp,
    coverHeight: Dp,
    onClick: (Book) -> Unit,
    onLongClick: (Book) -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier.combinedClickable(
            onClick = { onClick(book) },
            onLongClick = { onLongClick(book) },
        ),
    ) {
        BookCover(book = book, width = coverWidth, height = coverHeight)
        if (selectionMode) {
            SelectionMark(selected, Modifier.align(Alignment.TopEnd).padding(6.dp))
        }
    }
}

@Composable
private fun BookLabel(book: Book, width: Dp) {
    Column(
        modifier = Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = book.displayTitle,
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 13.sp,
                lineHeight = 17.sp,
                color = Color.Black,
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (book.author.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = book.author,
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 11.sp, color = Color(0xFF6A6A6A)),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}
