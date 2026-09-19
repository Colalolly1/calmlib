package com.calmlib.reader.ui.library

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookFormat
import com.calmlib.reader.ui.theme.CalmFonts
import kotlin.math.abs

/**
 * A book standing on a shelf: the cover image when we have one, otherwise a
 * typeset stand-in that looks like a plain publisher's jacket rather than a
 * file name in a box. A thin spine and edge frame give it a little depth on
 * E-Ink, and non-EPUB books wear a small format tag in the corner.
 */
@Composable
fun BookCover(
    book: Book,
    width: Dp = 100.dp,
    height: Dp = 150.dp,
    showFormatTag: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val targetPx = with(density) { width.roundToPx() }
    val bitmap = remember(book.coverPath, targetPx) {
        book.coverPath?.let { path ->
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(path, bounds)
                var sample = 1
                while (bounds.outWidth / (sample * 2) >= targetPx) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                BitmapFactory.decodeFile(path, opts)?.asImageBitmap()
            } catch (_: Exception) { null }
        }
    }

    Box(
        modifier = modifier
            .width(width)
            .height(height)
            .background(Color.White),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = book.displayTitle,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FallbackCover(book = book, modifier = Modifier.fillMaxSize())
        }

        // Edge frame + spine: top/left hairlines, a slightly darker right edge
        // (the fore-edge of the pages) and a firm bottom line where it meets the shelf.
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFF9A9A9A)).align(Alignment.TopStart))
        Box(Modifier.fillMaxHeight().width(0.5.dp).background(Color(0xFF9A9A9A)).align(Alignment.TopStart))
        Box(Modifier.fillMaxHeight().width(3.dp).background(Color(0xFFD8D8D8)).align(Alignment.TopEnd))
        Box(Modifier.fillMaxHeight().width(0.5.dp).background(Color(0xFF777777)).align(Alignment.TopEnd))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF333333)).align(Alignment.BottomStart))

        if (showFormatTag && book.format != BookFormat.EPUB) {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 7.dp, bottom = 6.dp)
                    .background(Color.Black)
                    .padding(horizontal = 5.dp, vertical = 2.dp),
            ) {
                Text(
                    text = book.format.label,
                    style = TextStyle(
                        fontFamily = CalmFonts.sans,
                        fontSize = 8.sp,
                        letterSpacing = 1.sp,
                        color = Color.White,
                    ),
                )
            }
        }

        if (book.progress > 0f) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Color(0xFFE6E6E6)),
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
}

/**
 * Three quiet jacket designs, chosen by title so a given book always gets the
 * same one and a shelf of coverless books doesn't look like a row of clones.
 */
@Composable
private fun FallbackCover(book: Book, modifier: Modifier = Modifier) {
    val title = book.displayTitle
    when (abs(title.hashCode()) % 3) {
        0 -> BandedJacket(title, book.author, modifier)
        1 -> FramedJacket(title, book.author, modifier)
        else -> MonogramJacket(title, book.author, modifier)
    }
}

private val jacketTitle = TextStyle(
    fontFamily = CalmFonts.serif,
    fontSize = 14.sp,
    lineHeight = 18.sp,
    letterSpacing = (-0.2).sp,
    color = Color.Black,
)

private val jacketAuthor = TextStyle(
    fontFamily = CalmFonts.sans,
    fontSize = 10.sp,
    letterSpacing = 0.8.sp,
    color = Color(0xFF444444),
)

@Composable
private fun BandedJacket(title: String, author: String, modifier: Modifier) {
    Column(modifier.background(Color.White)) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(0.2f).background(Color(0xFF1A1A1A)),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.fillMaxWidth(0.5f).height(0.5.dp).background(Color(0xFFBBBBBB)))
        }
        Column(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(title, style = jacketTitle, textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            if (author.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Box(Modifier.width(20.dp).height(0.5.dp).background(Color.Black))
                Spacer(Modifier.height(8.dp))
                Text(author.uppercase(), style = jacketAuthor, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun FramedJacket(title: String, author: String, modifier: Modifier) {
    Box(modifier.background(Color.White).padding(7.dp)) {
        Box(
            Modifier
                .fillMaxSize()
                .border(0.5.dp, Color.Black)
                .padding(3.dp)
                .border(1.5.dp, Color.Black)
                .padding(horizontal = 10.dp, vertical = 16.dp),
        ) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, style = jacketTitle.copy(fontStyle = FontStyle.Italic), textAlign = TextAlign.Center, maxLines = 5, overflow = TextOverflow.Ellipsis)
            }
            if (author.isNotEmpty()) {
                Text(
                    author.uppercase(),
                    style = jacketAuthor,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

@Composable
private fun MonogramJacket(title: String, author: String, modifier: Modifier) {
    Column(modifier.background(Color.White).padding(horizontal = 12.dp, vertical = 12.dp)) {
        Text(
            text = title.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "•",
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 58.sp,
                lineHeight = 58.sp,
                color = Color(0xFF222222),
            ),
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.Black))
        Spacer(Modifier.weight(1f))
        Text(title, style = jacketTitle, maxLines = 4, overflow = TextOverflow.Ellipsis)
        if (author.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(author, style = jacketAuthor.copy(letterSpacing = 0.sp, fontSize = 10.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}
