package com.calmlib.reader.engine

import android.content.Context
import android.os.Environment
import com.calmlib.reader.data.repository.BookRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

class BookScanner(
    private val context: Context,
    private val repository: BookRepository,
) {
    data class ScanResult(val imported: Int, val skipped: Int, val scanned: Int)

    private val supportedExtensions = setOf("epub", "pdf", "txt", "fb2")

    private val skippedDirNames = setOf(
        "android", "thumbnails", ".thumbnails", "cache", ".cache",
        "obb", "data", "system", ".trash", "lost+found",
    )

    suspend fun scan(): ScanResult = withContext(Dispatchers.IO) {
        val roots = candidateRoots()
        val candidates = mutableListOf<File>()
        for (root in roots) {
            if (!root.exists() || !root.canRead()) continue
            collectCandidates(root, 0, candidates)
        }

        // Books the user deliberately deleted from the library stay deleted —
        // without this, the quiet auto-rescan would re-import them on the very
        // next app resume.
        val ignored = try {
            com.calmlib.reader.data.repository.SettingsRepository(context)
                .ignoredScanPaths.first()
        } catch (_: Exception) {
            emptySet()
        }

        var imported = 0
        var skipped = 0

        for (file in candidates) {
            if (file.absolutePath in ignored) {
                skipped++
                continue
            }
            if (file.length() < 1024) {
                skipped++
                continue
            }
            val existing = repository.bookByPath(file.absolutePath)
            if (existing != null) {
                skipped++
                continue
            }
            val book = repository.importFromFile(file)
            if (book != null) {
                imported++
                // If KOReader (or another reader) left a sidecar with progress, pull it in.
                val progress = readKoreaderProgress(file)
                if (progress != null) {
                    val pages = (book.totalPages.coerceAtLeast(1) * progress).toInt()
                    // Seed bookProgress directly with KOReader's fraction — page
                    // counts are unknown until the book is first opened, but the
                    // fraction is exactly what KOReader recorded.
                    repository.updateProgress(book.id, pages, 0, 0f, bookProgress = progress)
                }
            } else skipped++
        }
        ScanResult(imported, skipped, candidates.size)
    }

    /**
     * KOReader stores per-book metadata as `metadata.{ext}.lua` next to the file
     * (with a copy in `.sdr/` for some platforms). We don't parse the full Lua
     * spec — just yank `percent_finished` if present.
     */
    private fun readKoreaderProgress(file: File): Float? {
        val candidates = listOf(
            File(file.parentFile, "metadata.${file.extension.lowercase()}.lua"),
            File(file.parentFile, "${file.nameWithoutExtension}.sdr/metadata.${file.extension.lowercase()}.lua"),
        )
        for (c in candidates) {
            if (!c.exists() || !c.canRead()) continue
            try {
                val text = c.readText(Charsets.UTF_8).take(64 * 1024)
                // Two common shapes:
                //   ["percent_finished"] = 0.42
                //   percent_finished = 0.42
                val m = Regex("""(?:\[?"percent_finished"\]?|percent_finished)\s*=\s*([0-9.]+)""")
                    .find(text) ?: continue
                val p = m.groupValues[1].toFloatOrNull() ?: continue
                if (p in 0f..1f) return p
            } catch (_: Exception) { }
        }
        return null
    }

    private fun candidateRoots(): List<File> {
        // Only scan *dedicated book folders*. Downloads and Documents were here
        // historically but they're full of work PDFs, contracts, scans and other
        // non-book files the user doesn't want in their library. If someone keeps
        // their books in Downloads, they can move them to a Books folder or use
        // the manual "Add a book" picker.
        val roots = mutableListOf<File>()
        val external = Environment.getExternalStorageDirectory()
        listOf(
            "Books", "books",
            "Ebooks", "ebooks", "eBooks",
            "Reading", "reading",
            "KOReader", "FBReader", "Kindle",
        ).forEach { name ->
            val dir = File(external, name)
            if (dir.exists() && dir.isDirectory) roots.add(dir)
        }
        return roots.distinct()
    }

    private fun collectCandidates(root: File, depth: Int, into: MutableList<File>) {
        if (depth > 5) return
        val children = root.listFiles() ?: return
        for (child in children) {
            if (child.isDirectory) {
                if (child.name.startsWith(".")) continue
                if (child.name.lowercase() in skippedDirNames) continue
                collectCandidates(child, depth + 1, into)
            } else if (child.isFile) {
                val ext = child.extension.lowercase()
                if (ext in supportedExtensions) into.add(child)
            }
        }
    }
}
