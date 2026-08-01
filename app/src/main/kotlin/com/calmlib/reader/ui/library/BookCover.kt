package com.calmlib.reader.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.ui.theme.CalmTypography
import java.io.File

@Composable
fun BookCover(
    book: Book,
    width: Dp = 100.dp,
    height: Dp = 150.dp,
    showShelfLine: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val bitmap = remember(book.coverPath) {
        book.coverPath?.let { path ->
            try {
                val opts = BitmapFactory.Options().apply { inSampleSize = 1 }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .width(width)
                .height(height)
                .background(Color.White),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = book.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                FallbackCover(book = book, modifier = Modifier.fillMaxSize())
            }

            // Subtle book-edge frame — top + left + right thin lines, slightly darker
            // bottom line so the book reads as a 3D object sitting on the shelf.
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFAAAAAA)).align(Alignment.TopStart))
            Box(Modifier.fillMaxHeight().width(0.5.dp).background(Color(0xFFAAAAAA)).align(Alignment.TopStart))
            Box(Modifier.fillMaxHeight().width(0.5.dp).background(Color(0xFFAAAAAA)).align(Alignment.TopEnd))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF555555)).align(Alignment.BottomStart))
            // Spine / right-edge shadow — gives the cover a sense of thickness.
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .width(2.dp)
                    .fillMaxHeight()
                    .background(Color(0xFFE0E0E0))
            )

            if (book.progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(Color(0xFFEEEEEE)),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(book.progress)
                            .background(Color.Black),
                    )
                }
            }
        }
        if (showShelfLine) {
            Box(
                Modifier
                    .width(width)
                    .height(1.5.dp)
                    .background(Color(0xFF333333))
            )
        }
    }
}

@Composable
private fun FallbackCover(book: Book, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.White)
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = book.title,
            style = CalmTypography.bookTitle,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Box(
                Modifier
                    .width(24.dp)
                    .height(0.5.dp)
                    .background(Color.Black)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = book.author,
                style = CalmTypography.bookAuthor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
