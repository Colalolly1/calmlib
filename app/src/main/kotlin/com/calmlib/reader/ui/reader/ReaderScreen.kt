package com.calmlib.reader.ui.reader

import android.content.Context
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.calmlib.reader.data.model.AutoRefresh
import com.calmlib.reader.data.model.Bookmark
import com.calmlib.reader.data.model.Highlight
import com.calmlib.reader.data.model.ReadingSettings
import com.calmlib.reader.engine.TocEntry
import com.calmlib.reader.ui.theme.CalmFonts
import com.calmlib.reader.ui.theme.CalmTypography

@Composable
fun ReaderScreenFull(
    isPdf: Boolean,
    webViewFactory: (Context) -> WebView,
    pdfViewFactory: (Context) -> PdfReaderView,
    currentPage: Int,
    totalPages: Int,
    showControls: Boolean,
    showToc: Boolean,
    showBookmarks: Boolean,
    showSearch: Boolean,
    showHighlights: Boolean,
    showDictionary: Boolean,
    settings: ReadingSettings,
    bookmarks: List<Bookmark>,
    highlights: List<Highlight>,
    tocEntries: List<TocEntry>,
    errorMessage: String?,
    noticeMessage: String? = null,
    onDismissNotice: () -> Unit = {},
    dictWord: String,
    dictResult: String?,
    searchResults: List<Int>,
    onToggleControls: () -> Unit,
    onToggleToc: () -> Unit,
    onToggleBookmarks: () -> Unit,
    onToggleSearch: () -> Unit,
    onToggleHighlights: () -> Unit,
    onDismissDictionary: () -> Unit,
    onSettingsChanged: (ReadingSettings) -> Unit,
    onRefresh: () -> Unit,
    onTocNavigate: (TocEntry) -> Unit,
    onAddBookmark: () -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onBookmarkNavigate: (Bookmark) -> Unit,
    onSearch: (String) -> Unit,
    onSearchNavigate: (Int) -> Unit,
    onAddHighlight: (text: String, note: String) -> Unit,
    onDeleteHighlight: (Highlight) -> Unit,
    onHighlightNavigate: (Highlight) -> Unit,
    onDictLookup: (String) -> Unit,
    onExportHighlights: () -> Unit,
    selectedText: String?,
    onSelectionDismiss: () -> Unit,
    onSelectionLookup: (String) -> Unit,
    onSelectionHighlight: (String) -> Unit,
    onSelectionSearch: (String) -> Unit,
    fullscreenImageUrl: String?,
    onDismissImage: () -> Unit,
    pagesPerMinute: Float,
    bookProgress: Float,
    estimatedBookPages: Int,
    isMultiChapter: Boolean,
    onSeekPage: (Int) -> Unit,
    onToggleTts: () -> Unit,
    isSpeaking: Boolean,
    searchSnippets: List<Pair<Int, String>>,
    isGuestPreview: Boolean = false,
    onAddToLibrary: () -> Unit = {},
    webViewEpoch: Int = 0,
) {
    Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
        if (errorMessage != null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(errorMessage, style = CalmTypography.emptyTitle, textAlign = TextAlign.Center, modifier = Modifier.padding(48.dp))
            }
        } else if (isPdf) {
            AndroidView(
                factory = { ctx ->
                    pdfViewFactory(ctx).apply {
                        onPageChanged = { page, total ->
                            // handled by activity
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            // Keyed on the epoch: when the activity replaces a WebView whose
            // renderer process died, bumping the epoch disposes this AndroidView
            // and runs the factory again with a fresh WebView.
            androidx.compose.runtime.key(webViewEpoch) {
                AndroidView(factory = webViewFactory, modifier = Modifier.fillMaxSize())
            }
        }

        val anyOverlay = showControls || showToc || showBookmarks || showSearch || showHighlights || showDictionary || fullscreenImageUrl != null

        // Guest-preview banner: this file is being READ, not imported. One tap
        // adds it to the library; otherwise it vanishes without a trace.
        if (isGuestPreview && !anyOverlay && errorMessage == null) {
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Color(0xFFF4F4F4))
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Preview — not in your library",
                    style = CalmTypography.metadata.copy(fontSize = 10.sp, color = Color(0xFF555555)),
                    modifier = Modifier.weight(1f),
                )
                Box(
                    Modifier
                        .background(Color.Black)
                        .clickable(onClick = onAddToLibrary)
                        .padding(horizontal = 12.dp, vertical = 5.dp),
                ) {
                    Text(
                        "Add to library",
                        style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 11.sp),
                    )
                }
            }
        }

        // Transient notice: informs without destroying the reading session.
        if (noticeMessage != null && errorMessage == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 28.dp)
                    .background(Color(0xFFF2F2F2))
                    .clickable(onClick = onDismissNotice)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    noticeMessage,
                    style = CalmTypography.metadata.copy(fontSize = 11.sp, color = Color(0xFF333333)),
                )
            }
        }

        // Minimal footer
        if (!anyOverlay && errorMessage == null) {
            Row(
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                // Left: chapter-local page, plus whole-book percentage for
                // multi-chapter books (EPUB/FB2). For PDFs/TXT the pages ARE the book.
                val pct = (bookProgress * 100).toInt()
                val pageLabel = if (isMultiChapter && pct in 1..99) {
                    "${currentPage + 1} of $totalPages · $pct%"
                } else {
                    "${currentPage + 1} of $totalPages"
                }
                Text(pageLabel, style = CalmTypography.metadata.copy(fontSize = 10.sp))
                // Right: time remaining in the WHOLE book. estimatedBookPages == 0
                // means the extrapolation is low-confidence (tiny chapter) — show
                // nothing rather than a wild guess.
                if (pagesPerMinute > 0f && bookProgress < 1f && estimatedBookPages > 0) {
                    val pagesLeft = ((1f - bookProgress) * estimatedBookPages).toInt()
                    val minutes = (pagesLeft.toFloat() / pagesPerMinute).toInt()
                    val label = when {
                        minutes < 1 -> "almost done"
                        minutes < 60 -> "${minutes}m left"
                        else -> "${minutes / 60}h ${minutes % 60}m left"
                    }
                    Text(label, style = CalmTypography.metadata.copy(fontSize = 10.sp))
                }
            }
        }

        if (showControls) {
            // Bottom-sheet style: the real book stays visible in the top half so the
            // user sees changes apply live. Tapping the visible book area dismisses.
            Column(Modifier.fillMaxSize()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(0.42f)
                        .clickable(onClick = onToggleControls),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // Tiny banner pinned to the bottom of the preview area, right above
                    // the panel — makes the connection obvious. Pale grey so it doesn't
                    // disturb the reading.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .background(Color(0xFFF2F2F2))
                            .padding(vertical = 4.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "↑ Your book — settings update it live · tap to close",
                            style = CalmTypography.metadata.copy(fontSize = 9.sp, color = Color(0xFF555555)),
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.Black))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(0.58f)
                        .background(Color.White),
                ) {
                    ControlsOverlay(
                        settings = settings,
                        onSettingsChanged = onSettingsChanged,
                        onRefresh = onRefresh,
                        onToc = onToggleToc,
                        onBookmarks = onToggleBookmarks,
                        onSearch = onToggleSearch,
                        onHighlights = onToggleHighlights,
                        onDismiss = onToggleControls,
                        currentPage = currentPage,
                        totalPages = totalPages,
                        onSeekPage = onSeekPage,
                        onToggleTts = onToggleTts,
                        isSpeaking = isSpeaking,
                        isGuestPreview = isGuestPreview,
                        onAddToLibrary = onAddToLibrary,
                    )
                }
            }
        }
        if (showToc) TocOverlay(tocEntries, onTocNavigate, onToggleToc)
        if (showBookmarks) BookmarksOverlay(bookmarks, currentPage, onBookmarkNavigate, onAddBookmark, onDeleteBookmark, onToggleBookmarks)
        if (showSearch) SearchOverlay(onSearch, searchResults, searchSnippets, onSearchNavigate, onToggleSearch)
        if (showHighlights) HighlightsOverlay(highlights, onAddHighlight, onDeleteHighlight, onHighlightNavigate, onExportHighlights, onToggleHighlights)
        if (showDictionary) DictionaryPopup(dictWord, dictResult, onDismissDictionary)
        selectedText?.let { txt ->
            SelectionActionSheet(
                text = txt,
                onLookup = { onSelectionLookup(txt) },
                onHighlight = { onSelectionHighlight(txt) },
                onSearch = { onSelectionSearch(txt) },
                onDismiss = onSelectionDismiss,
            )
        }
        fullscreenImageUrl?.let { url ->
            FullscreenImageViewer(url = url, onDismiss = onDismissImage)
        }
    }
}

@Composable
private fun FullscreenImageViewer(url: String, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        AndroidView(
            factory = { ctx ->
                android.widget.ImageView(ctx).apply {
                    setBackgroundColor(android.graphics.Color.WHITE)
                    scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                    // Decode via WebView resource fetcher — supports file:// and data: URIs
                    try {
                        when {
                            url.startsWith("data:") -> {
                                val b64 = url.substringAfter("base64,", "")
                                if (b64.isNotEmpty()) {
                                    val bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT)
                                    setImageBitmap(android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
                                }
                            }
                            url.startsWith("file://") -> {
                                // The DOM hands back percent-encoded URLs; decodeFile
                                // needs the real filesystem path ("%20" → space).
                                val raw = url.removePrefix("file://")
                                val decoded = try {
                                    java.net.URLDecoder.decode(raw, "UTF-8")
                                } catch (_: Exception) { raw }
                                val bmp = android.graphics.BitmapFactory.decodeFile(decoded)
                                    ?: android.graphics.BitmapFactory.decodeFile(raw)
                                setImageBitmap(bmp)
                            }
                        }
                    } catch (_: Exception) { }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(16.dp)
                .background(Color.White)
                .clickable(onClick = onDismiss)
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text("Close", style = CalmTypography.controlLabel)
        }
    }
}

@Composable
private fun SelectionActionSheet(
    text: String,
    onLookup: () -> Unit,
    onHighlight: () -> Unit,
    onSearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier.fillMaxSize().clickable(onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(20.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color.Black))
            Spacer(Modifier.height(16.dp))
            Text(
                text = "\"" + text.take(140) + (if (text.length > 140) "…" else "") + "\"",
                style = CalmTypography.body.copy(fontSize = 13.sp, lineHeight = 18.sp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionButton("Highlight", onHighlight, Modifier.weight(1f))
                ActionButton("Look up", onLookup, Modifier.weight(1f))
                ActionButton("Search", onSearch, Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Cancel", style = CalmTypography.controlLabel.copy(color = Color(0xFF999999)))
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Color.Black)
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 14.sp))
    }
}

/**
 * Reader controls as a compact THREE-TAB sheet instead of one endless scroll:
 *   Go     — page slider, contents, bookmarks, highlights, search, read aloud
 *   Type   — size, spacing, margins, font, text-style toggles, reset
 *   Screen — E-Ink options (contrast, bold, anti-aliasing, auto-refresh)
 * On a 4.3" E-Ink panel, less scrolling = fewer full-screen refreshes = calmer.
 */
@Composable
private fun ControlsOverlay(
    settings: ReadingSettings,
    onSettingsChanged: (ReadingSettings) -> Unit,
    onRefresh: () -> Unit,
    onToc: () -> Unit,
    onBookmarks: () -> Unit,
    onSearch: () -> Unit,
    onHighlights: () -> Unit,
    onDismiss: () -> Unit,
    currentPage: Int,
    totalPages: Int,
    onSeekPage: (Int) -> Unit,
    onToggleTts: () -> Unit,
    isSpeaking: Boolean,
    isGuestPreview: Boolean = false,
    onAddToLibrary: () -> Unit = {},
) {
    var tab by remember { mutableStateOf(0) }   // 0 = Go, 1 = Type, 2 = Screen

    Column(Modifier.fillMaxSize().background(Color.White)) {
        // Header + tab bar (fixed)
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Reading", style = CalmTypography.libraryTitle.copy(fontSize = 20.sp))
                Box(
                    Modifier
                        .background(Color.Black)
                        .clickable { onDismiss() }
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text("Done", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 14.sp))
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Go", "Type", "Screen").forEachIndexed { i, label ->
                    val active = tab == i
                    Box(
                        Modifier
                            .weight(1f)
                            .background(if (active) Color.Black else Color(0xFFF0F0F0))
                            .clickable { tab = i }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = CalmTypography.controlLabel.copy(
                            color = if (active) Color.White else Color.Black,
                            fontSize = 13.sp,
                        ))
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFDDDDDD)))

        // Tab content. key(tab) recreates the LazyColumn per tab so each opens
        // scrolled to the top — otherwise all three tabs share one scroll state
        // and switching mid-scroll hides the new tab's top rows.
        androidx.compose.runtime.key(tab) {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp)) {
                when (tab) {
                    0 -> goTabContent(
                        currentPage, totalPages, onSeekPage,
                        onToc, onBookmarks, onSearch, onHighlights, onToggleTts, isSpeaking, onRefresh,
                        isGuestPreview, onAddToLibrary,
                    )
                    1 -> typeTabContent(settings, onSettingsChanged)
                    else -> screenTabContent(settings, onSettingsChanged, onRefresh)
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.goTabContent(
    currentPage: Int,
    totalPages: Int,
    onSeekPage: (Int) -> Unit,
    onToc: () -> Unit,
    onBookmarks: () -> Unit,
    onSearch: () -> Unit,
    onHighlights: () -> Unit,
    onToggleTts: () -> Unit,
    isSpeaking: Boolean,
    onRefresh: () -> Unit,
    isGuestPreview: Boolean,
    onAddToLibrary: () -> Unit,
) {
    item {
        Spacer(Modifier.height(14.dp))
        var draggingValue by remember(currentPage) { mutableStateOf(currentPage.toFloat()) }
        val shown = if (totalPages > 0) draggingValue.toInt() + 1 else 1
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Page", style = CalmTypography.controlLabel)
            Text("$shown of $totalPages", style = CalmTypography.controlValue.copy(fontSize = 12.sp))
        }
        androidx.compose.material3.Slider(
            value = draggingValue.coerceIn(0f, (totalPages - 1).coerceAtLeast(0).toFloat()),
            onValueChange = { draggingValue = it },
            onValueChangeFinished = { onSeekPage(draggingValue.toInt().coerceIn(0, (totalPages - 1).coerceAtLeast(0))) },
            valueRange = 0f..(totalPages - 1).coerceAtLeast(0).toFloat(),
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = Color.Black,
                activeTrackColor = Color.Black,
                inactiveTrackColor = Color(0xFFCCCCCC),
            ),
        )
        Spacer(Modifier.height(10.dp))
    }
    item {
        val ttsLabel = if (isSpeaking) "Stop reading aloud" else "Read aloud"
        ActionItem("Contents", onToc)
        ActionItem("Bookmarks", onBookmarks)
        ActionItem("Highlights", onHighlights)
        ActionItem("Search this book", onSearch)
        ActionItem(ttsLabel, onToggleTts)
        ActionItem("Refresh screen", onRefresh)
        if (isGuestPreview) {
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .clickable(onClick = onAddToLibrary)
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Add this book to my library", style = CalmTypography.controlLabel.copy(color = Color.White, fontSize = 14.sp))
            }
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.typeTabContent(
    settings: ReadingSettings,
    onSettingsChanged: (ReadingSettings) -> Unit,
) {
    // Text size
    item {
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("TEXT SIZE")
            Text(sizeLabel(settings.fontSize), style = CalmTypography.controlValue.copy(fontSize = 12.sp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("A", style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 14.sp))
            androidx.compose.material3.Slider(
                value = settings.fontSize.coerceIn(10f, 40f),
                onValueChange = { onSettingsChanged(settings.copy(fontSize = it)) },
                valueRange = 10f..40f,
                steps = 14,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.Black,
                    activeTrackColor = Color.Black,
                    inactiveTrackColor = Color(0xFFCCCCCC),
                ),
            )
            Text("A", style = TextStyle(fontFamily = CalmFonts.serif, fontSize = 24.sp))
        }
        Text("Pinch the page with two fingers to resize too.",
            style = CalmTypography.metadata.copy(fontSize = 10.sp))
        Spacer(Modifier.height(14.dp))
    }
    // Line spacing
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("LINE SPACING")
            Text(spacingLabel(settings.lineSpacing), style = CalmTypography.controlValue.copy(fontSize = 12.sp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                repeat(3) { Box(Modifier.width(14.dp).height(1.dp).background(Color.Black)) }
            }
            androidx.compose.material3.Slider(
                value = settings.lineSpacing.coerceIn(1.0f, 2.4f),
                onValueChange = { onSettingsChanged(settings.copy(lineSpacing = it)) },
                valueRange = 1.0f..2.4f,
                steps = 13,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.Black,
                    activeTrackColor = Color.Black,
                    inactiveTrackColor = Color(0xFFCCCCCC),
                ),
            )
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(3) { Box(Modifier.width(14.dp).height(1.dp).background(Color.Black)) }
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    // Margins
    item {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SectionLabel("MARGINS")
            Text(marginLabel(settings.marginHorizontal), style = CalmTypography.controlValue.copy(fontSize = 12.sp))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(22.dp).height(14.dp).border(0.5.dp, Color.Black)) {
                Box(Modifier.padding(1.dp).fillMaxSize().background(Color(0xFFCCCCCC)))
            }
            androidx.compose.material3.Slider(
                value = settings.marginHorizontal.toFloat().coerceIn(0f, 64f),
                onValueChange = { onSettingsChanged(settings.copy(marginHorizontal = it.toInt())) },
                valueRange = 0f..64f,
                steps = 15,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                colors = androidx.compose.material3.SliderDefaults.colors(
                    thumbColor = Color.Black,
                    activeTrackColor = Color.Black,
                    inactiveTrackColor = Color(0xFFCCCCCC),
                ),
            )
            Box(Modifier.width(22.dp).height(14.dp).border(0.5.dp, Color.Black)) {
                Box(Modifier.padding(5.dp).fillMaxSize().background(Color(0xFFCCCCCC)))
            }
        }
        Spacer(Modifier.height(14.dp))
    }
    // Font
    item {
        SectionLabel("FONT")
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple("literata", "Aa", "Serif"),
                Triple("atkinson", "Aa", "Sans"),
                Triple("jetbrains_mono", "Aa", "Mono"),
            ).forEach { (key, glyph, label) ->
                val active = settings.fontFamily == key
                val family = when (key) {
                    "atkinson" -> CalmFonts.sans
                    "jetbrains_mono" -> CalmFonts.mono
                    else -> CalmFonts.serif
                }
                Column(
                    Modifier
                        .weight(1f)
                        .background(if (active) Color.Black else Color(0xFFF6F6F6))
                        .clickable { onSettingsChanged(settings.copy(fontFamily = key)) }
                        .padding(vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(glyph, style = TextStyle(
                        fontFamily = family,
                        fontSize = 22.sp,
                        color = if (active) Color.White else Color.Black,
                    ))
                    Spacer(Modifier.height(2.dp))
                    Text(label, style = CalmTypography.metadata.copy(
                        fontSize = 11.sp,
                        color = if (active) Color.White else Color(0xFF666666),
                    ))
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    // Text style toggles
    item {
        SectionLabel("TEXT STYLE")
        ToggleRow("Justify (smooth right edge)", settings.justify) { onSettingsChanged(settings.copy(justify = it)) }; Divider()
        ToggleRow("Hyphenate long words", settings.hyphenation) { onSettingsChanged(settings.copy(hyphenation = it)) }; Divider()
        ToggleRow("Bionic reading (bold first half)", settings.bionicReading) { onSettingsChanged(settings.copy(bionicReading = it)) }; Divider()
        Spacer(Modifier.height(20.dp))
    }
    // Reset
    item {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8F8F8))
                .clickable { onSettingsChanged(ReadingSettings()) }
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Reset to defaults", style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)))
        }
        Spacer(Modifier.height(32.dp))
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.screenTabContent(
    settings: ReadingSettings,
    onSettingsChanged: (ReadingSettings) -> Unit,
    onRefresh: () -> Unit,
) {
    item {
        Spacer(Modifier.height(14.dp))
        SectionLabel("E-INK DISPLAY")
        ToggleRow("Boost contrast", settings.contrastBoost) { onSettingsChanged(settings.copy(contrastBoost = it)) }; Divider()
        ToggleRow("Bold text", settings.boldMode) { onSettingsChanged(settings.copy(boldMode = it)) }; Divider()
        ToggleRow("Anti-aliasing", settings.antiAliasing) { onSettingsChanged(settings.copy(antiAliasing = it)) }; Divider()
        Spacer(Modifier.height(16.dp))
    }
    item {
        Text("Auto-refresh", style = CalmTypography.controlLabel)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AutoRefresh.entries.forEach { ar ->
                val active = settings.autoRefreshInterval == ar.pages
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (active) Color.Black else Color(0xFFF0F0F0))
                        .clickable { onSettingsChanged(settings.copy(autoRefreshInterval = ar.pages)) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(ar.label, style = CalmTypography.controlLabel.copy(
                        color = if (active) Color.White else Color.Black,
                        fontSize = 11.sp,
                    ))
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
    item {
        Box(
            Modifier
                .fillMaxWidth()
                .background(Color(0xFFF8F8F8))
                .clickable(onClick = onRefresh)
                .padding(vertical = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Refresh screen now", style = CalmTypography.controlLabel)
        }
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = CalmTypography.sectionHeader, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun ActionItem(label: String, onClick: () -> Unit) {
    Text(label, style = CalmTypography.controlLabel.copy(fontSize = 16.sp),
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 14.dp))
    Divider()
}

private fun sizeLabel(px: Float): String = when {
    px < 13f -> "Smallest"
    px < 16f -> "Small"
    px < 19f -> "Medium"
    px < 22f -> "Large"
    px < 26f -> "Larger"
    else -> "Largest"
}

private fun spacingLabel(v: Float): String = when {
    v < 1.2f -> "Tight"
    v < 1.5f -> "Compact"
    v < 1.7f -> "Medium"
    v < 2.0f -> "Loose"
    else -> "Loosest"
}

private fun marginLabel(m: Int): String = when {
    m <= 8 -> "Edge to edge"
    m <= 20 -> "Narrow"
    m <= 32 -> "Medium"
    m <= 48 -> "Wide"
    else -> "Widest"
}

@Composable
private fun SearchOverlay(
    onSearch: (String) -> Unit,
    results: List<Int>,
    snippets: List<Pair<Int, String>>,
    onNavigate: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Search", style = CalmTypography.libraryTitle.copy(fontSize = 22.sp))
            Text("Done", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onDismiss() })
        }
        Spacer(Modifier.height(16.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = query, onValueChange = { query = it },
                textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 15.sp, color = Color.Black),
                cursorBrush = SolidColor(Color.Black), singleLine = true,
                modifier = Modifier.weight(1f),
                decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search text...", style = CalmTypography.controlValue); inner() } },
            )
            Spacer(Modifier.width(12.dp))
            Text("Find", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onSearch(query) })
        }
        Spacer(Modifier.height(4.dp)); Divider(); Spacer(Modifier.height(12.dp))

        val display = if (snippets.isNotEmpty()) snippets else results.map { it to "" }
        if (display.isEmpty() && query.isNotEmpty()) {
            Text("No results", style = CalmTypography.emptyBody)
        } else {
            LazyColumn {
                items(display) { (page, snippet) ->
                    Column(
                        Modifier.fillMaxWidth().clickable { onNavigate(page); onDismiss() }.padding(vertical = 10.dp)
                    ) {
                        Text("Page ${page + 1}", style = CalmTypography.metadata.copy(fontSize = 11.sp))
                        if (snippet.isNotEmpty()) {
                            Spacer(Modifier.height(4.dp))
                            Text(snippet, style = CalmTypography.body.copy(fontSize = 13.sp, lineHeight = 18.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun HighlightsOverlay(
    highlights: List<Highlight>,
    onAdd: (text: String, note: String) -> Unit,
    onDelete: (Highlight) -> Unit,
    onNavigate: (Highlight) -> Unit,
    onExport: () -> Unit,
    onDismiss: () -> Unit,
) {
    var newText by remember { mutableStateOf("") }
    var newNote by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Highlights", style = CalmTypography.libraryTitle.copy(fontSize = 22.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                if (highlights.isNotEmpty()) Text("Export", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onExport() })
                Text("Done", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onDismiss() })
            }
        }
        Spacer(Modifier.height(16.dp))

        // Add highlight manually
        Text("ADD HIGHLIGHT", style = CalmTypography.sectionHeader); Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = newText, onValueChange = { newText = it },
            textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 14.sp, color = Color.Black),
            cursorBrush = SolidColor(Color.Black), singleLine = false, maxLines = 3,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> Box { if (newText.isEmpty()) Text("Highlighted text", style = CalmTypography.controlValue); inner() } },
        )
        Spacer(Modifier.height(4.dp))
        BasicTextField(
            value = newNote, onValueChange = { newNote = it },
            textStyle = TextStyle(fontFamily = CalmFonts.sans, fontSize = 13.sp, color = Color(0xFF666666)),
            cursorBrush = SolidColor(Color.Black), singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner -> Box { if (newNote.isEmpty()) Text("Note (optional)", style = CalmTypography.controlValue); inner() } },
        )
        Spacer(Modifier.height(8.dp))
        Text("Save highlight", style = CalmTypography.controlLabel, modifier = Modifier.clickable {
            if (newText.isNotBlank()) { onAdd(newText.trim(), newNote.trim()); newText = ""; newNote = "" }
        })
        Spacer(Modifier.height(16.dp)); Divider(); Spacer(Modifier.height(12.dp))

        if (highlights.isEmpty()) {
            Text("No highlights yet", style = CalmTypography.emptyBody)
        } else {
            LazyColumn {
                items(highlights) { h ->
                    Column(Modifier.padding(vertical = 8.dp)) {
                        // Tapping the quoted text jumps to where the highlight lives.
                        Text(
                            "\"${h.text}\"",
                            style = CalmTypography.body.copy(fontSize = 13.sp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().clickable { onNavigate(h) },
                        )
                        if (h.note.isNotEmpty()) {
                            Text(h.note, style = CalmTypography.metadata.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 2.dp))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                "Go to page ${h.page + 1} ›",
                                style = CalmTypography.metadata,
                                modifier = Modifier.clickable { onNavigate(h) },
                            )
                            Text("Remove", style = CalmTypography.metadata, modifier = Modifier.clickable { onDelete(h) })
                        }
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun DictionaryPopup(word: String, result: String?, onDismiss: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().clickable { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .background(Color.White)
                .padding(24.dp)
                .heightIn(max = 560.dp),
        ) {
            Text(word, style = CalmTypography.bookTitle.copy(fontSize = 20.sp))
            Spacer(Modifier.height(4.dp))
            Box(Modifier.width(40.dp).height(0.5.dp).background(Color.Black))
            Spacer(Modifier.height(12.dp))
            // Long entries (common words carry many senses) must scroll inside
            // the card, not push the buttons off-screen.
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(androidx.compose.foundation.rememberScrollState()),
            ) {
                Text(
                    text = result ?: "Looking up...",
                    style = CalmTypography.body.copy(fontSize = 14.sp, lineHeight = 22.sp),
                )
            }
            Spacer(Modifier.height(20.dp))
            // Fully offline by design: the Kompakt has no browser, so web
            // links here were dead ends. The definition comes from the bundled
            // WordNet; Copy is the only side door.
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Copy", style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                    modifier = Modifier.clickable {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
                            as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("word", "$word — ${result ?: ""}"))
                    })
                Text("Dismiss", style = CalmTypography.controlLabel.copy(color = Color(0xFF999999)),
                    modifier = Modifier.clickable { onDismiss() })
            }
        }
    }
}

@Composable
private fun TocOverlay(entries: List<TocEntry>, onNavigate: (TocEntry) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Contents", style = CalmTypography.libraryTitle.copy(fontSize = 22.sp))
            Text("Done", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onDismiss() })
        }
        Spacer(Modifier.height(16.dp))
        if (entries.isEmpty()) { Text("No table of contents available", style = CalmTypography.emptyBody) }
        else {
            LazyColumn {
                items(entries) { entry ->
                    Text(entry.title, style = CalmTypography.bookTitle, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onNavigate(entry) }.padding(start = (entry.depth * 16).dp, top = 10.dp, bottom = 10.dp))
                    Divider()
                }
            }
        }
    }
}

@Composable
private fun BookmarksOverlay(bookmarks: List<Bookmark>, currentPage: Int, onNavigate: (Bookmark) -> Unit, onAdd: () -> Unit, onDelete: (Bookmark) -> Unit, onDismiss: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White).padding(20.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Bookmarks", style = CalmTypography.libraryTitle.copy(fontSize = 22.sp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Add current page", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onAdd() })
                Text("Done", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onDismiss() })
            }
        }
        Spacer(Modifier.height(16.dp))
        if (bookmarks.isEmpty()) { Text("No bookmarks yet", style = CalmTypography.emptyBody) }
        else {
            LazyColumn {
                items(bookmarks) { bm ->
                    Row(Modifier.fillMaxWidth().clickable { onNavigate(bm) }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(bm.label.ifEmpty { "Page ${bm.page + 1}" }, style = CalmTypography.bookTitle)
                        Text("Remove", style = CalmTypography.metadata, modifier = Modifier.clickable { onDelete(bm) })
                    }
                    Divider()
                }
            }
        }
    }
}

@Composable private fun ControlRow(label: String, value: String, controls: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(label, style = CalmTypography.controlLabel); Text(value, style = CalmTypography.controlValue) }
        controls()
    }
}

@Composable private fun ToggleRow(label: String, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onToggle(!enabled) }.padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = CalmTypography.controlLabel); Text(if (enabled) "On" else "Off", style = CalmTypography.controlValue)
    }
}

@Composable private fun PlusMinus(onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Text("−", style = CalmTypography.controlLabel.copy(fontSize = 20.sp), modifier = Modifier.clickable { onMinus() })
        Text("+", style = CalmTypography.controlLabel.copy(fontSize = 20.sp), modifier = Modifier.clickable { onPlus() })
    }
}

@Composable private fun Divider() { Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFEEEEEE))) }
