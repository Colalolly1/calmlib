package com.calmlib.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import kotlin.math.ceil
import kotlin.math.max

private const val PER_SHELF = 3
private const val MIN_SHELVES = 3
private val CASE_MARGIN = 16.dp
private val SIDE_BOARD = 9.dp
private val INNER_PAD = 12.dp

/**
 * The home page: a bookcase holding only the books you've chosen to read.
 * Three to a shelf, standing on planks between two side boards, with room
 * left over so it always looks like a piece of furniture rather than a grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookcaseScreen(
    books: List<Book>,
    readingNow: Book?,
    epigraph: Pair<String, String>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGoToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxSize()) {
        val innerWidth = maxWidth - CASE_MARGIN * 2 - SIDE_BOARD * 2 - INNER_PAD * 2
        val coverWidth = ((innerWidth - 14.dp * (PER_SHELF - 1)) / PER_SHELF).coerceAtMost(132.dp)
        val coverHeight = coverWidth * 1.5f
        val shelves = max(MIN_SHELVES, ceil(books.size / PER_SHELF.toFloat()).toInt())

        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = CASE_MARGIN + 6.dp).padding(top = 4.dp, bottom = 16.dp)) {
                    Text(
                        text = "“${epigraph.first}”  — ${epigraph.second}",
                        style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 18.sp, color = Color(0xFF6E6E6E)),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            item {
                Bookcase(
                    books = books,
                    shelves = shelves,
                    coverWidth = coverWidth,
                    coverHeight = coverHeight,
                    onBookClick = onBookClick,
                    onBookLongClick = onBookLongClick,
                    onGoToLibrary = onGoToLibrary,
                )
            }
            if (readingNow != null) {
                item {
                    Spacer(Modifier.height(18.dp))
                    ReadingNowLine(readingNow) { onBookClick(readingNow) }
                }
            }
            item {
                Spacer(Modifier.height(30.dp))
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("❧", style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 15.sp, color = Color(0xFF555555)))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = when (books.size) {
                            0 -> "An empty shelf is a promise."
                            1 -> "One book waiting for you."
                            else -> "${books.size} books waiting for you."
                        },
                        style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 12.sp, color = Color(0xFF777777)),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Bookcase(
    books: List<Book>,
    shelves: Int,
    coverWidth: Dp,
    coverHeight: Dp,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGoToLibrary: () -> Unit,
) {
    val rows = books.chunked(PER_SHELF)
    Column(Modifier.fillMaxWidth().padding(horizontal = CASE_MARGIN)) {
        // Top board
        Board(height = 7.dp)
        Row(Modifier.fillMaxWidth()) {
            SideBoard()
            Column(Modifier.weight(1f)) {
                repeat(shelves) { i ->
                    val row = rows.getOrNull(i) ?: emptyList()
                    // Air above the books, then the books standing on the plank.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(coverHeight + 22.dp)
                            .padding(horizontal = INNER_PAD),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (row.isEmpty() && i == rows.size) {
                            // First empty shelf carries the one hint the page needs.
                            Text(
                                text = "Room for more.\nIn Library, long-press a book and choose “Put on my shelf”.",
                                style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF777777)),
                                textAlign = TextAlign.Center,
                                modifier = Modifier.align(Alignment.Center).clickable(onClick = onGoToLibrary).padding(12.dp),
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            row.forEach { book ->
                                Box(
                                    Modifier.combinedClickable(
                                        onClick = { onBookClick(book) },
                                        onLongClick = { onBookLongClick(book) },
                                    ),
                                ) {
                                    BookCover(book = book, width = coverWidth, height = coverHeight)
                                }
                            }
                        }
                    }
                    Plank()
                }
            }
            SideBoard()
        }
        // Plinth
        Board(height = 10.dp)
    }
}

/** Horizontal boards: the dark top edge, a pale face, and a shadow line. */
@Composable
private fun Plank() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(2.5.dp).background(Color(0xFF1A1A1A)))
        Box(Modifier.fillMaxWidth().height(7.dp).background(Color(0xFFE4E4E4)))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9C9C9C)))
    }
}

@Composable
private fun Board(height: Dp) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(height).background(Color(0xFF1A1A1A)))
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFFBDBDBD)))
    }
}

@Composable
private fun SideBoard() {
    Row(Modifier.width(SIDE_BOARD).fillMaxHeight()) {
        Box(Modifier.width(SIDE_BOARD - 1.5.dp).fillMaxHeight().background(Color(0xFF1A1A1A)))
        Box(Modifier.width(1.5.dp).fillMaxHeight().background(Color(0xFFBDBDBD)))
    }
}

/** Under the case: the book you were last in and the word that takes you back. */
@Composable
private fun ReadingNowLine(book: Book, onClick: () -> Unit) {
    val pct = (book.progress * 100).toInt().coerceIn(0, 100)
    val where = when {
        book.totalPages > 1 && book.currentPage > 0 -> "Page ${book.currentPage} of ${book.totalPages}"
        pct > 0 -> "$pct% read"
        else -> "Just started"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = CASE_MARGIN + 6.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Reading now", style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Color(0xFF777777)))
            Spacer(Modifier.height(3.dp))
            Text(book.displayTitle, style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 16.sp, color = Color.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text(where, style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 11.sp, color = Color(0xFF777777)))
        }
        Spacer(Modifier.width(14.dp))
        Box(Modifier.background(Color.Black).padding(horizontal = 20.dp, vertical = 9.dp)) {
            Text("Resume", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 13.sp))
        }
    }
}
