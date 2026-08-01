package com.calmlib.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.ui.theme.CalmTypography

// One physical shelf holds N books on the Mudita's 480px-wide screen. Bumping
// this changes the cover size automatically since the row Arrangement.SpaceEvenly
// distributes whatever's there.
private const val BOOKS_PER_SHELF = 3

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ShelfGrid(
    books: List<Book>,
    currentlyReading: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit = {},
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    modifier: Modifier = Modifier,
) {
    val readingIds = currentlyReading.map { it.id }.toSet()
    val otherBooks = books.filter { it.id !in readingIds }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
    ) {
        if (currentlyReading.isNotEmpty()) {
            // The most recently read book gets a hero card: big cover, progress,
            // one-tap resume. Open the app → one tap → back in your book.
            item { ShelfHeader("Continue reading") }
            item {
                ContinueReadingHero(
                    book = currentlyReading.first(),
                    onClick = { onBookClick(currentlyReading.first()) },
                    onLongClick = { onBookLongClick(currentlyReading.first()) },
                    selectionMode = selectionMode,
                    selected = currentlyReading.first().id in selectedIds,
                )
            }
            val restReading = currentlyReading.drop(1)
            if (restReading.isNotEmpty()) {
                item { ShelfHeader("Also reading") }
                items(restReading.chunked(BOOKS_PER_SHELF)) { row ->
                    Shelf(row, onBookClick, onBookLongClick, selectionMode, selectedIds)
                }
            }
        }

        if (otherBooks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(20.dp))
                ShelfHeader("On your shelves")
            }
            items(otherBooks.chunked(BOOKS_PER_SHELF)) { row ->
                Shelf(row, onBookClick, onBookLongClick, selectionMode, selectedIds)
            }
        }

        // Closing flourish — ornament + literary quote of the day. Same quote shows
        // across both the empty welcome screen and the populated library, so the
        // app feels like a single curated space.
        item {
            Spacer(Modifier.height(36.dp))
            QuoteFooter()
            Spacer(Modifier.height(36.dp))
        }
    }
}

/** Small black/white checkbox drawn over a cover corner during selection mode. */
@Composable
private fun SelectionMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .background(if (selected) Color.Black else Color.White)
            .border(1.5.dp, Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("✓", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 15.sp))
        }
    }
}

/**
 * Hero card for the book you're in the middle of. Large cover on the left;
 * title, author, progress and a filled Resume button on the right. Sits on its
 * own shelf line like everything else.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ContinueReadingHero(
    book: Book,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 24.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Box {
                BookCover(
                    book = book,
                    width = 124.dp,
                    height = 186.dp,
                    showShelfLine = false,
                )
                if (selectionMode) {
                    SelectionMark(
                        selected = selected,
                        modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
                    )
                }
            }
            Spacer(Modifier.width(18.dp))
            Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                Text(
                    text = book.title,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = com.calmlib.reader.ui.theme.CalmFonts.serif,
                        fontSize = 17.sp,
                        color = Color.Black,
                    ),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotEmpty()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = book.author,
                        style = CalmTypography.metadata.copy(fontSize = 12.sp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                val pct = (book.progress * 100).toInt().coerceIn(0, 100)
                Text(
                    text = if (pct > 0) "$pct% read" else "Just started",
                    style = CalmTypography.metadata.copy(fontSize = 11.sp),
                )
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .background(Color.Black)
                        .clickable(onClick = onClick)
                        .padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text(
                        "Resume",
                        style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 13.sp),
                    )
                }
            }
        }
        // The hero sits on the same continuous shelf line as everything else.
        Spacer(Modifier.height(1.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(2.dp)
                .background(Color(0xFF2A2A2A))
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * Section header — italic serif label between two hairlines, like a chapter
 * heading in a printed book. Feels far more "library" than the old ALL-CAPS
 * mini-heading.
 */
@Composable
private fun ShelfHeader(label: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(24.dp).height(0.5.dp).background(Color(0xFF888888)))
        Spacer(Modifier.width(12.dp))
        Text(
            text = label,
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = com.calmlib.reader.ui.theme.CalmFonts.serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 14.sp,
                color = androidx.compose.ui.graphics.Color(0xFF333333),
            ),
        )
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
    }
}

/** Literary quote of the day, rendered as the final flourish on the shelves. */
@Composable
private fun QuoteFooter() {
    val (quote, attribution) = quoteForToday()
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(0.55f),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
            Text(
                text = " ❧ ",
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = com.calmlib.reader.ui.theme.CalmFonts.serif,
                    fontSize = 14.sp,
                    color = androidx.compose.ui.graphics.Color(0xFF555555),
                ),
            )
            Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
        }
        Spacer(Modifier.height(16.dp))
        Text(
            text = "“$quote”",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = com.calmlib.reader.ui.theme.CalmFonts.serif,
                fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                color = androidx.compose.ui.graphics.Color(0xFF666666),
            ),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "— $attribution",
            style = androidx.compose.ui.text.TextStyle(
                fontFamily = com.calmlib.reader.ui.theme.CalmFonts.sans,
                fontSize = 11.sp,
                color = androidx.compose.ui.graphics.Color(0xFF888888),
            ),
        )
    }
}

/**
 * One physical shelf: a row of book covers bottom-aligned (so they all stand on
 * the same baseline) followed by a continuous dark line that runs the full width
 * of the screen — the shelf itself. Title/author appears below each cover so it
 * reads like a museum display.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Shelf(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    selectionMode: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
) {
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            books.forEach { book ->
                ShelfBook(book, onBookClick, onBookLongClick, selectionMode, book.id in selectedIds)
            }
            // Pad the row so books always align left-to-right with the same spacing,
            // even when the final shelf is partial (1 or 2 books).
            repeat(BOOKS_PER_SHELF - books.size) {
                Spacer(Modifier.width(100.dp))
            }
        }
        // The shelf line — continuous, spans full screen width, sits flush under the
        // book covers. 2dp dark grey reads as a wood/metal shelf edge on E-Ink.
        Spacer(Modifier.height(1.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .height(2.dp)
                .background(Color(0xFF2A2A2A))
        )
        Spacer(Modifier.height(6.dp))
        // Title/author label area BELOW the shelf, like a bookstore display tag.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            books.forEach { book ->
                BookLabel(book)
            }
            repeat(BOOKS_PER_SHELF - books.size) {
                Spacer(Modifier.width(100.dp))
            }
        }
        Spacer(Modifier.height(28.dp))   // air between shelves
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ShelfBook(
    book: Book,
    onClick: (Book) -> Unit,
    onLongClick: (Book) -> Unit,
    selectionMode: Boolean = false,
    selected: Boolean = false,
) {
    Box(
        modifier = Modifier
            .combinedClickable(
                onClick = { onClick(book) },
                onLongClick = { onLongClick(book) },
            )
    ) {
        BookCover(
            book = book,
            width = 100.dp,
            height = 150.dp,
            showShelfLine = false,
        )
        if (selectionMode) {
            SelectionMark(
                selected = selected,
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            )
        }
    }
}

@Composable
private fun BookLabel(book: Book) {
    Column(
        modifier = Modifier.width(100.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = book.title,
            style = CalmTypography.bookTitle.copy(fontSize = 11.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        if (book.author.isNotEmpty()) {
            Text(
                text = book.author,
                style = CalmTypography.metadata.copy(fontSize = 9.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
