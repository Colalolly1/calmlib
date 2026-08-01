package com.calmlib.reader.ui.library

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.calmlib.reader.data.db.AppDatabase
import com.calmlib.reader.data.model.ReadingStats
import com.calmlib.reader.data.model.VocabularyEntry
import com.calmlib.reader.engine.BackupManager
import com.calmlib.reader.ui.theme.CalmTypography
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onScan: () -> Unit = {},
    isScanning: Boolean = false,
    onResetLibrary: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var stats by remember { mutableStateOf(ReadingStats()) }
    var vocabulary by remember { mutableStateOf<List<VocabularyEntry>>(emptyList()) }
    var showVocab by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var confirmingReset by remember { mutableStateOf(false) }

    // Dictionary state — loaded names + a refresh trigger so the UI updates when
    // the user adds or removes a dictionary.
    val dictEngine = remember { com.calmlib.reader.engine.DictionaryEngine.get(context) }
    var loadedDicts by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(Unit) {
        // First-launch decompresses bundled WordNet (~30MB), so move off main.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            dictEngine.loadBundledDict()
        }
        loadedDicts = dictEngine.loaded
    }

    val dictPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri: android.net.Uri? ->
        if (uri != null) {
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    dictEngine.importFromUri(uri)
                }
                statusMessage = result.error
                    ?: "Loaded ${result.added.size} dictionar${if (result.added.size == 1) "y" else "ies"}: ${result.added.joinToString(", ")}"
                loadedDicts = dictEngine.loaded
            }
        }
    }

    LaunchedEffect(Unit) {
        val db = AppDatabase.get(context)
        val dayStart = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis

        val totalPages = db.statsDao().totalPagesRead().first()
        val totalMinutes = db.statsDao().totalMinutesRead().first()
        val pagesToday = db.statsDao().pagesToday(dayStart).first()
        val minutesToday = db.statsDao().minutesToday(dayStart).first()
        val booksCompleted = db.statsDao().booksCompleted().first()
        stats = ReadingStats(booksCompleted, totalPages, totalMinutes, pagesToday, minutesToday)

        db.highlightDao().allVocabulary().collect { vocabulary = it }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(Color.White).padding(20.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Settings", style = CalmTypography.libraryTitle)
                Text("Done", style = CalmTypography.controlLabel, modifier = Modifier.clickable { onBack() })
            }
            Spacer(Modifier.height(32.dp))
        }

        // Library
        item {
            Text("LIBRARY", style = CalmTypography.sectionHeader)
            Spacer(Modifier.height(16.dp))
        }

        item {
            Text(
                if (isScanning) "Scanning..." else "Scan device for books",
                style = CalmTypography.controlLabel,
                modifier = Modifier
                    .clickable(enabled = !isScanning) { onScan() }
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
            )
            Text(
                "Looks in /Books, /Ebooks, /Reading and other dedicated folders.",
                style = CalmTypography.metadata.copy(fontSize = 11.sp),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Divider()
        }

        // Reset library — the nuclear option for starting fresh after a messy scan
        // pulled in duplicates or random PDFs from Downloads/Documents.
        item {
            if (!confirmingReset) {
                Text(
                    "Reset library…",
                    style = CalmTypography.controlLabel.copy(color = Color(0xFF8B0000)),
                    modifier = Modifier
                        .clickable { confirmingReset = true }
                        .fillMaxWidth()
                        .padding(vertical = 12.dp),
                )
                Text(
                    "Removes all books, highlights, bookmarks, vocabulary, and reading sessions. Collections, dictionary, and app settings are kept. After resetting, tap Scan device to rebuild.",
                    style = CalmTypography.metadata.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            } else {
                Text(
                    "Are you sure? This can't be undone.",
                    style = CalmTypography.body.copy(fontSize = 13.sp),
                    modifier = Modifier.padding(vertical = 12.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 12.dp)) {
                    Text(
                        "Cancel",
                        style = CalmTypography.controlLabel,
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFFF0F0F0))
                            .clickable { confirmingReset = false }
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "Reset library",
                        style = CalmTypography.controlLabel.copy(color = Color.White),
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(0xFF8B0000))
                            .clickable {
                                onResetLibrary()
                                confirmingReset = false
                                statusMessage = "Library reset. Tap Scan device above to rebuild."
                            }
                            .padding(vertical = 12.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            Divider()
        }

        // Reading stats
        item {
            Spacer(Modifier.height(32.dp))
            Text("READING", style = CalmTypography.sectionHeader)
            Spacer(Modifier.height(16.dp))
        }
        item { StatRow("Books completed", "${stats.totalBooksRead}") }
        item { StatRow("Total pages read", "${stats.totalPagesRead}") }
        item { StatRow("Time spent reading", formatMinutes(stats.totalTimeMinutes)) }
        item { StatRow("Pages today", "${stats.pagesToday}") }
        item { StatRow("Today", formatMinutes(stats.minutesToday)) }

        // ====== DICTIONARY ======
        item {
            Spacer(Modifier.height(32.dp))
            Text("DICTIONARY", style = CalmTypography.sectionHeader)
            Spacer(Modifier.height(8.dp))
            if (loadedDicts.isEmpty()) {
                Text(
                    "No dictionaries installed. Long-press a word in any book to look it up — without a dictionary you'll only have Wikipedia / other-app lookup.",
                    style = CalmTypography.metadata.copy(fontSize = 11.sp),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            } else {
                loadedDicts.forEach { name ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("• $name", style = CalmTypography.controlValue.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                        Text("Remove", style = CalmTypography.metadata.copy(fontSize = 11.sp, color = Color(0xFF666666)),
                            modifier = Modifier.clickable {
                                dictEngine.removeDictionary(name)
                                loadedDicts = dictEngine.loaded
                                statusMessage = "Removed dictionary: $name"
                            })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("Add dictionary…", style = CalmTypography.controlLabel,
                modifier = Modifier.clickable {
                    dictPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "*/*"))
                }.fillMaxWidth().padding(vertical = 12.dp))
            Text(
                "Pick a StarDict ZIP containing .ifo + .idx + .dict files. Free downloads: kaikki.org, dict.org, huzheng.org.",
                style = CalmTypography.metadata.copy(fontSize = 11.sp),
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Divider()
        }

        item { Spacer(Modifier.height(32.dp)); Text("DATA", style = CalmTypography.sectionHeader); Spacer(Modifier.height(16.dp)) }

        item {
            Text("Export library backup", style = CalmTypography.controlLabel,
                modifier = Modifier.clickable {
                    scope.launch {
                        val file = BackupManager(context).exportBackup()
                        statusMessage = "Backup saved to ${file.name}"
                        val intent = Intent(Intent.ACTION_SEND).apply {
                            type = "application/json"
                            putExtra(Intent.EXTRA_SUBJECT, "CalmLib Backup")
                            putExtra(Intent.EXTRA_TEXT, file.readText())
                        }
                        context.startActivity(Intent.createChooser(intent, "Share backup"))
                    }
                }.fillMaxWidth().padding(vertical = 12.dp))
            Divider()
        }

        item {
            Text("Vocabulary list (${vocabulary.size} words)", style = CalmTypography.controlLabel,
                modifier = Modifier.clickable { showVocab = !showVocab }.fillMaxWidth().padding(vertical = 12.dp))
            Divider()
        }

        if (showVocab) {
            if (vocabulary.isEmpty()) {
                item { Text("No words looked up yet", style = CalmTypography.emptyBody, modifier = Modifier.padding(vertical = 8.dp)) }
            } else {
                items(vocabulary) { entry ->
                    Column(Modifier.padding(vertical = 8.dp, horizontal = 8.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(entry.word, style = CalmTypography.bookTitle.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                            Text("Wikipedia",
                                style = CalmTypography.metadata.copy(fontSize = 11.sp, color = Color(0xFF666666)),
                                modifier = Modifier.clickable {
                                    val url = "https://en.wikipedia.org/wiki/Special:Search?search=${Uri.encode(entry.word)}"
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                })
                        }
                        Text(entry.definition.take(120), style = CalmTypography.metadata.copy(fontSize = 12.sp), maxLines = 2)
                    }
                    Divider()
                }

                item {
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text("Export TSV", style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                            modifier = Modifier.clickable {
                                val tsv = vocabulary.joinToString("\n") { "${it.word}\t${it.definition}" }
                                shareText(context, tsv, "text/tab-separated-values", "CalmLib Vocabulary (TSV)")
                            }.padding(vertical = 8.dp))
                        Text("Anki CSV", style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                            modifier = Modifier.clickable {
                                val csv = vocabulary.joinToString("\n") {
                                    val word = it.word.replace("\"", "\"\"")
                                    val def = it.definition.replace("\"", "\"\"").replace("\n", " ")
                                    "\"$word\",\"$def\""
                                }
                                shareText(context, csv, "text/csv", "CalmLib Vocabulary (Anki)")
                            }.padding(vertical = 8.dp))
                        Text("BibTeX", style = CalmTypography.controlLabel.copy(color = Color(0xFF666666)),
                            modifier = Modifier.clickable {
                                scope.launch {
                                    val bibtex = buildBibtex(context)
                                    shareText(context, bibtex, "application/x-bibtex", "CalmLib Citations")
                                }
                            }.padding(vertical = 8.dp))
                    }
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)); Text("ABOUT", style = CalmTypography.sectionHeader); Spacer(Modifier.height(16.dp)) }
        item { StatRow("CalmLib", "v0.6.0") }
        item { StatRow("License", "AGPL-3.0") }

        statusMessage?.let { msg ->
            item { Spacer(Modifier.height(16.dp)); Text(msg, style = CalmTypography.metadata) }
        }

        item { Spacer(Modifier.height(48.dp)) }
    }
}

private suspend fun buildBibtex(context: android.content.Context): String {
    val db = AppDatabase.get(context)
    val books = db.bookDao().allByTitle().first()
    return books.joinToString("\n\n") { book ->
        val key = book.title.lowercase().replace(Regex("[^a-z0-9]+"), "_").take(40)
        val author = book.author.ifEmpty { "Unknown" }
        """@book{$key,
  title = {${book.title}},
  author = {$author},
  year = {${java.text.SimpleDateFormat("yyyy", java.util.Locale.US).format(java.util.Date(book.dateAdded))}},
}"""
    }
}

private fun shareText(context: android.content.Context, text: String, mimeType: String, subject: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, subject)
    }
    context.startActivity(Intent.createChooser(intent, subject))
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = CalmTypography.controlLabel)
        Text(value, style = CalmTypography.controlValue)
    }
}

@Composable
private fun Divider() {
    Box(Modifier.fillMaxWidth().height(0.5.dp).background(Color(0xFFEEEEEE)))
}

private fun formatMinutes(minutes: Long): String = when {
    minutes < 60 -> "${minutes}m"
    else -> "${minutes / 60}h ${minutes % 60}m"
}
