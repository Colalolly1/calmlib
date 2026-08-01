package com.calmlib.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.ui.theme.CalmFonts
import com.calmlib.reader.ui.theme.CalmTypography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

/**
 * A library waiting to be filled. Designed to feel like the entrance to a small
 * neighbourhood bookshop rather than a phone "no data" screen.
 *
 * Layout (top to bottom):
 *   - Welcome — large Literata serif salutation
 *   - Horizontal rule (the kind you'd see on a vintage book frontispiece)
 *   - One short paragraph in body serif inviting the user
 *   - Two large filled buttons — Add a book, Scan device
 *   - Empty-shelf illustration (just a few horizontal lines — readable on E-Ink)
 *   - A short literary quote, chosen deterministically per day so it changes
 *     but isn't jarringly different on every open
 */
@Composable
fun EmptyLibrary(
    onAddBook: () -> Unit,
    onScanDevice: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Welcome.",
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 38.sp,
                fontWeight = FontWeight.Normal,
                letterSpacing = (-1).sp,
                color = Color.Black,
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Your library is quiet.",
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 18.sp,
                fontStyle = FontStyle.Italic,
                color = Color(0xFF333333),
            ),
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(28.dp))
        Ornament()
        Spacer(Modifier.height(24.dp))

        Text(
            text = "Add a book from your phone, or let CalmLib find the ones already on it. Your shelves will fill up.",
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 14.sp,
                lineHeight = 22.sp,
                color = Color(0xFF555555),
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )

        Spacer(Modifier.height(28.dp))

        PrimaryAction(label = "Add a book", onClick = onAddBook)
        Spacer(Modifier.height(10.dp))
        SecondaryAction(label = "Scan my device", onClick = onScanDevice)

        Spacer(Modifier.height(36.dp))

        // Illustration: three empty shelves
        EmptyShelves()

        Spacer(Modifier.height(28.dp))
        Ornament()
        Spacer(Modifier.height(20.dp))

        val (quote, attribution) = quoteForToday()
        Text(
            text = "“$quote”",
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 13.sp,
                lineHeight = 20.sp,
                fontStyle = FontStyle.Italic,
                color = Color(0xFF666666),
            ),
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "— $attribution",
            style = TextStyle(
                fontFamily = CalmFonts.sans,
                fontSize = 11.sp,
                color = Color(0xFF888888),
            ),
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun PrimaryAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth(0.78f)
            .background(Color.Black)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = CalmFonts.sans,
                fontSize = 14.sp,
                color = Color.White,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

@Composable
private fun SecondaryAction(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth(0.78f)
            .background(Color(0xFFF2F2F2))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = TextStyle(
                fontFamily = CalmFonts.sans,
                fontSize = 14.sp,
                color = Color.Black,
                letterSpacing = 0.5.sp,
            ),
        )
    }
}

/** A small printer's rule with a centred dingbat — adds a touch of book-design feel. */
@Composable
private fun Ornament() {
    Row(
        modifier = Modifier.fillMaxWidth(0.55f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
        Text(
            text = " ❧ ",   // floral dingbat
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 14.sp,
                color = Color(0xFF555555),
            ),
        )
        Box(Modifier.weight(1f).height(0.5.dp).background(Color(0xFF888888)))
    }
}

/** Empty shelf lines — visual cue that the library is a physical space. */
@Composable
private fun EmptyShelves() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        repeat(3) { i ->
            Spacer(Modifier.height(if (i == 0) 0.dp else 26.dp))
            Box(
                Modifier
                    .fillMaxWidth(0.85f)
                    .height(2.dp)
                    .background(Color(0xFF222222))
            )
        }
    }
}

/**
 * Shown when the library has books but the current filter/collection happens to
 * be empty. Keeps the welcoming entrance screen out of the way once the user has
 * built up a library.
 */
@Composable
fun NoMatchesHere(
    formatFilters: Set<String>,
    selectedCollection: com.calmlib.reader.data.model.Collection?,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val filterLabel = formatFilters.sorted().joinToString(" / ")
    val reason = when {
        selectedCollection != null && formatFilters.isNotEmpty() ->
            "Nothing in the collection “${selectedCollection.name}” matches the $filterLabel filter."
        selectedCollection != null ->
            "The collection “${selectedCollection.name}” is empty."
        formatFilters.isNotEmpty() ->
            "No $filterLabel books in your library."
        else -> "Nothing to show here."
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(80.dp))
        Text(
            text = reason,
            style = TextStyle(
                fontFamily = CalmFonts.serif,
                fontSize = 15.sp,
                color = Color(0xFF555555),
            ),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "Clear filters",
            style = TextStyle(
                fontFamily = CalmFonts.sans,
                fontSize = 13.sp,
                color = Color.Black,
            ),
            modifier = Modifier
                .background(Color(0xFFF2F2F2))
                .clickable(onClick = onClearFilters)
                .padding(horizontal = 18.dp, vertical = 10.dp),
        )
    }
}

/**
 * Deterministic quote-of-the-day. Same quote within a calendar day, different the
 * next. Public domain or fair-use literary quotes about books and reading.
 * Made internal so the populated library footer can show one too.
 */
internal fun quoteForToday(): Pair<String, String> {
    val day = (System.currentTimeMillis() / (1000L * 60 * 60 * 24)).toInt()
    val idx = ((day % QUOTES.size) + QUOTES.size) % QUOTES.size
    return QUOTES[idx]
}

// Quotes chosen to do more than say "books are nice" — each one points at something
// the reader can feel: a frozen sea inside, an untold story, a wild and precious life.
private val QUOTES = listOf(
    "A book must be the axe for the frozen sea within us." to "Franz Kafka",
    "What an astonishing thing a book is. One glance at it and you're inside the mind of another person, maybe somebody dead for thousands of years." to "Carl Sagan",
    "There are years that ask questions and years that answer." to "Zora Neale Hurston",
    "Have patience with everything unresolved in your heart and try to love the questions themselves, as if they were locked rooms or books written in a very foreign language." to "Rainer Maria Rilke",
    "Books are mirrors: you only see in them what you already have inside you." to "Carlos Ruiz Zafón",
    "Once you have read a book you care about, some part of it is always with you." to "Louis L'Amour",
    "There is no greater agony than bearing an untold story inside you." to "Maya Angelou",
    "The unread story is not a story; it is little black marks on wood pulp. The reader, reading it, makes it live." to "Ursula K. Le Guin",
    "I lived in books more than I lived anywhere else." to "Neil Gaiman",
    "All sorrows can be borne if you put them into a story or tell a story about them." to "Isak Dinesen",
    "We read to know we are not alone." to "C. S. Lewis",
    "Tell me, what is it you plan to do with your one wild and precious life?" to "Mary Oliver",
    "We are all in the gutter, but some of us are looking at the stars." to "Oscar Wilde",
    "Make voyages. Attempt them. There's nothing else." to "Tennessee Williams",
    "I have always imagined that Paradise will be a kind of library." to "Jorge Luis Borges",
    "Reading was my escape and my comfort, my consolation, my stimulant of choice." to "Paul Auster",
    "If you only read the books that everyone else is reading, you can only think what everyone else is thinking." to "Haruki Murakami",
    "The world breaks everyone, and afterward, many are strong at the broken places." to "Ernest Hemingway",
    "When I have a little money, I buy books; and if I have any left, I buy food and clothes." to "Erasmus",
    "We don't read and write poetry because it's cute. We read and write poetry because we are members of the human race." to "John Keating",
)
