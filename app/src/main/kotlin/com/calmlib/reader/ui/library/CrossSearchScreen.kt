package com.calmlib.reader.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.data.model.Book
import com.calmlib.reader.engine.CrossBookSearch
import com.calmlib.reader.ui.theme.CalmFonts
import com.calmlib.reader.ui.theme.CalmTypography
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun CrossSearchScreen(
    onBack: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { CrossBookSearch(context) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<CrossBookSearch.Hit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }

    LaunchedEffect(query) {
        job?.cancel()
        if (query.length < 2) {
            results = emptyList(); searching = false
            return@LaunchedEffect
        }
        job = scope.launch {
            delay(200)
            searching = true
            results = engine.search(query)
            searching = false
        }
    }

    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Search everywhere", style = CalmTypography.libraryTitle.copy(fontSize = 22.sp))
            Text("Done", style = CalmTypography.controlLabel, modifier = Modifier.clickable(onClick = onBack))
        }
        Spacer(Modifier.height(16.dp))
        BasicTextField(
            value = query,
            onValueChange = { query = it },
            textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 15.sp, color = Color.Black),
            cursorBrush = SolidColor(Color.Black), singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                Box {
                    if (query.isEmpty()) Text("Search titles, highlights, bookmarks, words…", style = CalmTypography.controlValue)
                    inner()
                }
            },
        )
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.Black))
        Spacer(Modifier.height(12.dp))

        when {
            searching -> Text("Searching…", style = CalmTypography.metadata)
            query.length < 2 -> Text("Type at least 2 characters", style = CalmTypography.emptyBody)
            results.isEmpty() -> Text("No matches", style = CalmTypography.emptyBody)
            else -> {
                LazyColumn {
                    items(results) { hit ->
                        HitRow(hit, onOpenBook)
                        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFEEEEEE)))
                    }
                }
            }
        }
    }
}

@Composable
private fun HitRow(hit: CrossBookSearch.Hit, onOpenBook: (Book) -> Unit) {
    val typeLabel = when (hit.type) {
        CrossBookSearch.HitType.BOOK_TITLE -> "TITLE"
        CrossBookSearch.HitType.BOOK_AUTHOR -> "AUTHOR"
        CrossBookSearch.HitType.HIGHLIGHT -> "HIGHLIGHT"
        CrossBookSearch.HitType.BOOKMARK -> "BOOKMARK"
        CrossBookSearch.HitType.VOCABULARY -> "WORD"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .let { mod -> hit.book?.let { b -> mod.clickable { onOpenBook(b) } } ?: mod }
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(typeLabel, style = CalmTypography.metadata.copy(fontSize = 10.sp, color = Color(0xFF666666)))
            if (hit.page != null) {
                Spacer(Modifier.width(8.dp))
                Text("p${hit.page + 1}", style = CalmTypography.metadata.copy(fontSize = 10.sp))
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(hit.snippet, style = CalmTypography.body.copy(fontSize = 14.sp, lineHeight = 18.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
        hit.book?.let { b ->
            Spacer(Modifier.height(2.dp))
            Text("${b.title}${if (b.author.isNotEmpty()) " · ${b.author}" else ""}",
                style = CalmTypography.metadata.copy(fontSize = 11.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
