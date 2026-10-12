package com.calmlib.reader.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
import kotlin.math.sin

private const val PER_SHELF = 3
private const val MIN_SHELVES = 3
private val CASE_MARGIN = 16.dp
private val SIDE_BOARD = 9.dp
private val INNER_PAD = 12.dp
private val BackPanel = Color(0xFFF1F1F1)
private val Bead = Color(0xFFE2E2E2)
private val Wood = Color(0xFF2B2B2B)
private val Grain = Color(0xFF474747)

/**
 * The home page: a bookcase holding only the books you've chosen to read.
 * Three to a shelf, standing on planks between two side boards, with room
 * left over so it always looks like a piece of furniture rather than a grid.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BookcaseScreen(
    books: List<Book>,
    reading: List<Book>,
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
            if (reading.isNotEmpty()) {
                item { CurrentlyReading(reading, onBookClick) }
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
        Crown()
        // IntrinsicSize.Min lets the side boards stretch to the full height of the shelves.
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            SideBoard()
            Column(Modifier.weight(1f)) {
                repeat(shelves) { i ->
                    val row = rows.getOrNull(i) ?: emptyList()
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(coverHeight + 26.dp)
                            .drawBehind { backPanel(lights = i == 0) }
                            .padding(horizontal = INNER_PAD),
                        contentAlignment = Alignment.BottomStart,
                    ) {
                        if (row.isEmpty()) {
                            EmptyShelfDressing(
                                showHint = i == rows.size,
                                shelf = i,
                                coverWidth = coverWidth,
                                coverHeight = coverHeight,
                                onGoToLibrary = onGoToLibrary,
                            )
                        }
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            row.forEach { book -> StandingBook(book, coverWidth, coverHeight, onBookClick, onBookLongClick) }
                            // A part-filled shelf gets one small thing in the gap, like a real one.
                            if (row.isNotEmpty() && row.size < PER_SHELF) {
                                Spacer(Modifier.weight(1f))
                                Ornament(kind = i + row.size, width = coverWidth * 0.7f, height = coverHeight * 0.6f)
                            }
                        }
                    }
                    Plank()
                }
            }
            SideBoard()
        }
        Plinth()
        Rug()
    }
}

/**
 * A book standing on the plank. Real shelves never hold identical heights,
 * so each book is between 88% and 100% tall, decided by its title, and it
 * throws a small shadow onto the back panel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StandingBook(
    book: Book,
    coverWidth: Dp,
    coverHeight: Dp,
    onBookClick: (Book) -> Unit,
    onBookLongClick: (Book) -> Unit,
) {
    val factor = 0.88f + (kotlin.math.abs(book.displayTitle.hashCode()) % 13) / 100f
    val h = coverHeight * factor
    val w = coverWidth * (0.94f + (kotlin.math.abs(book.id.hashCode()) % 7) / 100f)
    Box(
        Modifier.combinedClickable(
            onClick = { onBookClick(book) },
            onLongClick = { onBookLongClick(book) },
        ),
    ) {
        Box(Modifier.padding(start = 3.dp, top = 3.dp).width(w).height(h).background(Color(0xFFB8B8B8)))
        BookCover(book = book, width = w, height = h)
    }
}

/** An empty shelf still looks lived in: a couple of small things, and one hint. */
@Composable
private fun EmptyShelfDressing(
    showHint: Boolean,
    shelf: Int,
    coverWidth: Dp,
    coverHeight: Dp,
    onGoToLibrary: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(bottom = 1.dp),
        verticalArrangement = Arrangement.Bottom,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showHint) {
            Text(
                text = "Room for more.\nIn Library, long-press a book and choose “Put on my shelf”.",
                style = TextStyle(fontFamily = CalmFonts.serif, fontStyle = FontStyle.Italic, fontSize = 12.sp, lineHeight = 17.sp, color = Color(0xFF6E6E6E)),
                textAlign = TextAlign.Center,
                modifier = Modifier.clickable(onClick = onGoToLibrary).padding(horizontal = 12.dp, vertical = 10.dp),
            )
            Spacer(Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Ornament(kind = shelf * 2, width = coverWidth * 0.7f, height = coverHeight * 0.6f)
            Ornament(kind = shelf * 2 + 3, width = coverWidth * 0.7f, height = coverHeight * 0.6f)
        }
    }
}

// ─── The furniture ───────────────────────────────────────────────────────────

/** Fine wavy grain lines across (or down) a dark board. */
private fun DrawScope.woodGrain(vertical: Boolean, lines: Int, seed: Int) {
    val long = if (vertical) size.height else size.width
    val short = if (vertical) size.width else size.height
    for (n in 1..lines) {
        val base = short * n / (lines + 1)
        val path = Path()
        val steps = 24
        for (k in 0..steps) {
            val t = long * k / steps
            val wobble = sin((k + seed * 3 + n * 5) * 0.55f) * short * 0.08f
            val x = if (vertical) base + wobble else t
            val y = if (vertical) t else base + wobble
            if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, Grain, style = Stroke(width = 0.8.dp.toPx()))
    }
}

/**
 * Behind the books: tongue-and-groove boards, a soft shadow under the plank
 * above, and on the top shelf a string of little lights.
 */
private fun DrawScope.backPanel(lights: Boolean) {
    drawRect(BackPanel)
    val gap = 18.dp.toPx()
    var x = gap
    while (x < size.width) {
        drawLine(Bead, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        x += gap
    }
    drawRect(
        brush = Brush.verticalGradient(listOf(Color(0xFFCFCFCF), BackPanel), startY = 0f, endY = 10.dp.toPx()),
        size = Size(size.width, 10.dp.toPx()),
    )
    if (lights) fairyLights()
}

/** A loose string of bulbs sagging across the top shelf. */
private fun DrawScope.fairyLights() {
    val top = 3.dp.toPx()
    val sag = 9.dp.toPx()
    val wire = Path().apply {
        moveTo(0f, top)
        quadraticBezierTo(size.width / 2, top + sag * 2, size.width, top)
    }
    drawPath(wire, Color(0xFF6E6E6E), style = Stroke(width = 0.8.dp.toPx()))
    val bulbs = 9
    for (i in 1 until bulbs) {
        val t = i / bulbs.toFloat()
        val px = size.width * t
        val py = top + sag * 2 * 2 * t * (1 - t) // the curve's height at t
        drawCircle(Color(0xFFDADADA), radius = 4.5.dp.toPx(), center = Offset(px, py + 4.dp.toPx())) // glow
        drawCircle(Color(0xFF1A1A1A), radius = 2.2.dp.toPx(), center = Offset(px, py + 4.dp.toPx()))
        drawLine(Color(0xFF6E6E6E), Offset(px, py), Offset(px, py + 2.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
}

/** A shelf board: a pale top edge catching the light, grain, and a dark lip. */
@Composable
private fun Plank() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFF8A8A8A)))
        Box(Modifier.fillMaxWidth().height(8.dp).background(Wood).drawBehind { woodGrain(vertical = false, lines = 2, seed = 1) })
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFF9C9C9C)))
    }
}

/** Crown moulding with a stepped edge and a small engraved plate. */
@Composable
private fun Crown() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp).height(3.dp).background(Wood))
        Box(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(1.5.dp).background(Color(0xFFBDBDBD)))
        Box(Modifier.fillMaxWidth().padding(horizontal = 2.dp).height(3.dp).background(Wood))
        Box(
            Modifier.fillMaxWidth().height(22.dp).background(Wood).drawBehind { woodGrain(vertical = false, lines = 3, seed = 2) },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.background(Wood).border(0.8.dp, Color(0xFFBDBDBD)).padding(horizontal = 10.dp, vertical = 2.dp),
            ) {
                Text(
                    "EX LIBRIS",
                    style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 9.sp, letterSpacing = 3.sp, color = Color(0xFFE6E6E6)),
                )
            }
        }
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFFBDBDBD)))
    }
}

/** The base: a heavier grained board on two shaped feet. */
@Composable
private fun Plinth() {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(12.dp).background(Wood).drawBehind { woodGrain(vertical = false, lines = 2, seed = 3) })
        Box(Modifier.fillMaxWidth().height(1.5.dp).background(Color(0xFFBDBDBD)))
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Foot()
            Foot()
        }
    }
}

/** A bun foot: narrower at the floor. */
@Composable
private fun Foot() {
    Canvas(Modifier.width(26.dp).height(8.dp)) {
        val p = Path().apply {
            moveTo(0f, 0f); lineTo(size.width, 0f)
            lineTo(size.width * 0.82f, size.height); lineTo(size.width * 0.18f, size.height); close()
        }
        drawPath(p, Wood)
    }
}

/** A striped rug under the case, with fringe at the ends. */
@Composable
private fun Rug() {
    Canvas(Modifier.fillMaxWidth().height(16.dp).padding(horizontal = 28.dp)) {
        val top = 4.dp.toPx()
        val h = 8.dp.toPx()
        val fringe = 4.dp.toPx()
        val left = fringe
        val right = size.width - fringe
        drawRoundRect(Color(0xFFD6D6D6), Offset(left, top), Size(right - left, h), CornerRadius(1.5.dp.toPx()))
        val bands = 5
        for (b in 1 until bands) {
            val x = left + (right - left) * b / bands
            drawLine(Color(0xFF9C9C9C), Offset(x, top + 1.dp.toPx()), Offset(x, top + h - 1.dp.toPx()), strokeWidth = 1.5.dp.toPx())
        }
        drawRoundRect(Color(0xFF6E6E6E), Offset(left, top), Size(right - left, h), CornerRadius(1.5.dp.toPx()), style = Stroke(width = 1.dp.toPx()))
        var y = top + 1.dp.toPx()
        while (y < top + h) {
            drawLine(Color(0xFF6E6E6E), Offset(0f, y), Offset(left, y), strokeWidth = 0.8.dp.toPx())
            drawLine(Color(0xFF6E6E6E), Offset(right, y), Offset(size.width, y), strokeWidth = 0.8.dp.toPx())
            y += 2.dp.toPx()
        }
    }
}

@Composable
private fun SideBoard() {
    Row(Modifier.width(SIDE_BOARD).fillMaxHeight()) {
        Box(Modifier.width(SIDE_BOARD - 1.5.dp).fillMaxHeight().background(Wood).drawBehind { woodGrain(vertical = true, lines = 2, seed = 4) })
        Box(Modifier.width(1.5.dp).fillMaxHeight().background(Color(0xFFBDBDBD)))
    }
}

// ─── Things on the shelves ───────────────────────────────────────────────────

/**
 * Small things that make a shelf feel lived in: a plant, a candle, a mug, a
 * stack of books lying down, a little framed picture. Which one is decided
 * by [kind], so a shelf always shows the same thing.
 */
@Composable
private fun Ornament(kind: Int, width: Dp, height: Dp) {
    Canvas(Modifier.width(width).height(height)) {
        when (((kind % 5) + 5) % 5) {
            0 -> plant()
            1 -> candle()
            2 -> bookStack()
            3 -> mug()
            else -> framedPicture()
        }
    }
}

private fun DrawScope.plant() {
    val w = size.width; val h = size.height
    val potTop = h * 0.6f
    val pot = Path().apply {
        moveTo(w * 0.28f, potTop); lineTo(w * 0.72f, potTop)
        lineTo(w * 0.66f, h); lineTo(w * 0.34f, h); close()
    }
    drawPath(pot, Wood)
    drawRect(Color(0xFF4A4A4A), Offset(w * 0.25f, potTop - 2.dp.toPx()), Size(w * 0.5f, 3.dp.toPx()))
    val stem = Offset(w * 0.5f, potTop - 1.dp.toPx())
    val leaves = listOf(-62f, -35f, -12f, 12f, 35f, 62f, 0f)
    leaves.forEachIndexed { i, angle ->
        val len = h * (if (i == leaves.lastIndex) 0.55f else 0.42f)
        rotate(angle, pivot = stem) {
            val leaf = Path().apply {
                moveTo(stem.x, stem.y)
                quadraticBezierTo(stem.x + len * 0.28f, stem.y - len * 0.5f, stem.x, stem.y - len)
                quadraticBezierTo(stem.x - len * 0.28f, stem.y - len * 0.5f, stem.x, stem.y)
            }
            drawPath(leaf, Color(0xFF6E6E6E))
            drawPath(leaf, Color(0xFF2B2B2B), style = Stroke(width = 0.8.dp.toPx()))
            drawLine(Color(0xFF2B2B2B), stem, Offset(stem.x, stem.y - len * 0.85f), strokeWidth = 0.6.dp.toPx())
        }
    }
}

private fun DrawScope.candle() {
    val w = size.width; val h = size.height
    val jarW = w * 0.42f; val jarH = h * 0.42f
    val left = (w - jarW) / 2
    val top = h - jarH
    drawCircle(Color(0xFFE2E2E2), radius = w * 0.3f, center = Offset(w / 2, top - h * 0.16f)) // glow
    drawRect(Color(0xFFEDEDED), Offset(left, top), Size(jarW, jarH))
    drawRect(Color(0xFF2B2B2B), Offset(left, top), Size(jarW, jarH), style = Stroke(width = 1.2.dp.toPx()))
    drawRect(Color(0xFF2B2B2B), Offset(left, top + jarH * 0.68f), Size(jarW, jarH * 0.12f))
    val wickTop = top - h * 0.05f
    drawLine(Color(0xFF2B2B2B), Offset(w / 2, top), Offset(w / 2, wickTop), strokeWidth = 1.dp.toPx())
    val flame = Path().apply {
        moveTo(w / 2, wickTop - h * 0.2f)
        quadraticBezierTo(w / 2 + w * 0.09f, wickTop - h * 0.06f, w / 2, wickTop)
        quadraticBezierTo(w / 2 - w * 0.09f, wickTop - h * 0.06f, w / 2, wickTop - h * 0.2f)
    }
    drawPath(flame, Color(0xFF1A1A1A))
}

private fun DrawScope.bookStack() {
    val w = size.width; val h = size.height
    val books = listOf(Triple(0.96f, 0.13f, Wood), Triple(0.84f, 0.11f, Color(0xFF6E6E6E)), Triple(0.9f, 0.12f, Color(0xFF4A4A4A)))
    var bottom = h
    books.forEachIndexed { i, (bw, bh, colour) ->
        val bookW = w * bw; val bookH = h * bh
        val left = if (i % 2 == 0) 0f else w - bookW
        drawRect(colour, Offset(left, bottom - bookH), Size(bookW, bookH))
        drawRect(Color(0xFFE6E6E6), Offset(left + bookW - 3.dp.toPx(), bottom - bookH + 1.dp.toPx()), Size(2.dp.toPx(), bookH - 2.dp.toPx()))
        bottom -= bookH
    }
    // a small round thing on top, like a paperweight or an acorn
    drawCircle(Wood, radius = h * 0.06f, center = Offset(w * 0.45f, bottom - h * 0.06f))
}

private fun DrawScope.mug() {
    val w = size.width; val h = size.height
    val mugW = w * 0.46f; val mugH = h * 0.36f
    val left = w * 0.18f; val top = h - mugH
    drawRoundRect(Wood, Offset(left, top), Size(mugW, mugH), CornerRadius(2.dp.toPx()))
    drawArc(
        Wood, startAngle = -90f, sweepAngle = 180f, useCenter = false,
        topLeft = Offset(left + mugW - mugH * 0.25f, top + mugH * 0.18f), size = Size(mugH * 0.5f, mugH * 0.5f),
        style = Stroke(width = 2.5.dp.toPx()),
    )
    for (k in 0..1) {
        val sx = left + mugW * (0.35f + k * 0.3f)
        val steam = Path().apply {
            moveTo(sx, top - h * 0.04f)
            cubicTo(sx - w * 0.06f, top - h * 0.12f, sx + w * 0.06f, top - h * 0.18f, sx, top - h * 0.28f)
        }
        drawPath(steam, Color(0xFF8A8A8A), style = Stroke(width = 1.dp.toPx()))
    }
}

private fun DrawScope.framedPicture() {
    val w = size.width; val h = size.height
    val fw = w * 0.74f; val fh = h * 0.62f
    val left = (w - fw) / 2; val top = h - fh
    drawRect(Wood, Offset(left, top), Size(fw, fh))
    val inset = 3.dp.toPx()
    drawRect(Color(0xFFEDEDED), Offset(left + inset, top + inset), Size(fw - inset * 2, fh - inset * 2))
    val hills = Path().apply {
        moveTo(left + inset, top + fh - inset)
        lineTo(left + fw * 0.38f, top + fh * 0.48f)
        lineTo(left + fw * 0.56f, top + fh * 0.66f)
        lineTo(left + fw * 0.74f, top + fh * 0.42f)
        lineTo(left + fw - inset, top + fh - inset)
        close()
    }
    drawPath(hills, Color(0xFF6E6E6E))
    drawCircle(Color(0xFF2B2B2B), radius = fh * 0.08f, center = Offset(left + fw * 0.28f, top + fh * 0.3f))
}

/**
 * Above the case: every unfinished book opened in the last two weeks, most
 * recent first. The first one keeps the Resume button.
 */
@Composable
private fun CurrentlyReading(books: List<Book>, onBookClick: (Book) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = CASE_MARGIN + 6.dp).padding(bottom = 14.dp)) {
        Text("CURRENTLY READING", style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 10.sp, letterSpacing = 1.2.sp, color = Color(0xFF777777)))
        Spacer(Modifier.height(2.dp))
        books.forEachIndexed { i, book ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFDDDDDD)))
            ReadingRow(book, resume = i == 0) { onBookClick(book) }
        }
    }
}

@Composable
private fun ReadingRow(book: Book, resume: Boolean, onClick: () -> Unit) {
    val pct = (book.progress * 100).toInt().coerceIn(0, 100)
    val where = when {
        book.totalPages > 1 && book.currentPage > 0 -> "Page ${book.currentPage} of ${book.totalPages}"
        pct > 0 -> "$pct% read"
        else -> "Just started"
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(book.displayTitle, style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 16.sp, color = Color.Black), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(2.dp))
            Text("$where · ${lastReadLabel(book.lastRead)}", style = TextStyle(fontFamily = CalmFonts.sans, fontSize = 11.sp, color = Color(0xFF777777)))
        }
        if (resume) {
            Spacer(Modifier.width(14.dp))
            Box(Modifier.background(Color.Black).padding(horizontal = 20.dp, vertical = 9.dp)) {
                Text("Resume", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 13.sp))
            }
        }
    }
}

/** "today", "yesterday" or "5 days ago", by calendar day. */
private fun lastReadLabel(time: Long): String {
    val zone = java.time.ZoneId.systemDefault()
    val then = java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate()
    val days = java.time.temporal.ChronoUnit.DAYS.between(then, java.time.LocalDate.now(zone)).toInt()
    return when {
        days <= 0 -> "today"
        days == 1 -> "yesterday"
        else -> "$days days ago"
    }
}
