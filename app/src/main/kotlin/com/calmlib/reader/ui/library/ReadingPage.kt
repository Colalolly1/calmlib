package com.calmlib.reader.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.ui.theme.CalmFonts

/**
 * Every book you're partway through, most recent first: the cover, how far
 * in, and when you last read it. Tap to carry on; hold for the usual choices
 * (finished, take it off this list).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReadingPage(
    books: List<Book>,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
    onGoToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (books.isEmpty()) {
        Column(
            modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "Nothing being read yet.\nOpen a book and it waits for you here.",
                style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 15.sp, lineHeight = 22.sp, color = Color(0xFF555555)),
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                "Go to Library",
                style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Color.Black),
                modifier = Modifier.clickable(onClick = onGoToLibrary).padding(12.dp),
            )
        }
        return
    }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp)) {
        items(books, key = { it.id }) { book ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(onClick = { onBookClick(book) }, onLongClick = { onBookLongClick(book) })
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box {
                    Box(Modifier.padding(start = 3.dp, top = 3.dp).width(58.dp).height(87.dp).background(Color(0xFFB8B8B8)))
                    BookCover(book = book, width = 58.dp, height = 87.dp)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(book.displayTitle, style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 17.sp, lineHeight = 21.sp, color = Color.Black), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (book.author.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(book.author, style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 12.sp, color = Color(0xFF555555)), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.height(10.dp))
                    val pct = (book.progress * 100).toInt().coerceIn(0, 100)
                    // How far in, as a line: black for read, grey for the rest.
                    Row(Modifier.fillMaxWidth().height(3.dp)) {
                        if (pct > 0) Box(Modifier.weight(pct.toFloat()).fillMaxHeight().background(Color.Black))
                        if (pct < 100) Box(Modifier.weight((100 - pct).toFloat()).fillMaxHeight().background(Color(0xFFCFCFCF)))
                    }
                    Spacer(Modifier.height(6.dp))
                    val where = if (book.totalPages > 1 && book.currentPage > 0) "Page ${book.currentPage} of ${book.totalPages} · $pct%" else "$pct%"
                    Text("$where · ${lastReadLabel(book.lastRead)}", style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 12.sp, color = Color(0xFF555555)))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFDDDDDD)))
        }
    }
}
