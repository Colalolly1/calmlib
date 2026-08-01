package com.calmlib.reader.engine

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream

data class DictEntry(val word: String, val definition: String, val source: String = "")

class DictionaryEngine private constructor(private val context: Context) {
    companion object {
        // Singleton: the parsed index is a ~150k-entry map (tens of MB). One
        // per activity, rebuilt every onResume, was both a resume stall and
        // steady memory pressure on a 3GB device.
        @Volatile private var instance: DictionaryEngine? = null
        fun get(context: Context): DictionaryEngine =
            instance ?: synchronized(DictionaryEngine::class.java) {
                instance ?: DictionaryEngine(context.applicationContext).also { instance = it }
            }
    }

    private val dictionaries = mutableListOf<StarDict>()
    private var loadedFingerprint: String? = null

    private val dictDir: File by lazy {
        File(context.filesDir, "dictionaries").also { it.mkdirs() }
    }

    val loaded: List<String> get() = synchronized(dictionaries) { dictionaries.map { it.name } }
    val hasDictionaries: Boolean get() = synchronized(dictionaries) { dictionaries.isNotEmpty() }

    /** Install-only, no index parse. The reader calls this at startup: parsing
     *  the multi-MB index there allocates a ~150k-entry map at exactly the
     *  moment the chapter is loading — memory pressure that gets the WebView
     *  renderer killed on this 3GB device. Parsing waits for the first lookup. */
    @Synchronized
    fun ensureInstalled() {
        installBuiltInDictionaryIfMissing()
    }

    @Synchronized
    fun loadBundledDict() {
        // On first call (or after a clean install), copy the WordNet dictionary
        // bundled in /assets/dictionary into the app's filesDir. After that
        // initial copy, this is a no-op for subsequent calls — the user has a
        // real StarDict on disk that the parser can mmap-style read.
        installBuiltInDictionaryIfMissing()

        // Re-parsing the multi-MB .idx on every resume is seconds of CPU on
        // this device — skip unless the dictionary files actually changed.
        val fp = dictDir.listFiles().orEmpty()
            .sortedBy { it.name }
            .joinToString("|") { "${it.name}:${it.length()}:${it.lastModified()}" }
        synchronized(dictionaries) {
            if (fp == loadedFingerprint && dictionaries.isNotEmpty()) return
            dictionaries.clear()
            dictDir.listFiles()?.filter { it.name.endsWith(".ifo") }?.forEach { ifo ->
                try { dictionaries.add(StarDict(ifo)) } catch (_: Exception) { }
            }
            loadedFingerprint = fp
        }
    }

    private fun installBuiltInDictionaryIfMissing() {
        val ifoTarget = File(dictDir, "dictd_www.dict.org_wn.ifo")
        val idxTarget = File(dictDir, "dictd_www.dict.org_wn.idx")
        val dictTarget = File(dictDir, "dictd_www.dict.org_wn.dict")
        if (ifoTarget.exists() && idxTarget.exists() && dictTarget.exists()) return

        // Stage under temp names, rename at the end: a process kill mid-copy
        // must never leave a truncated .dict that passes the exists() check.
        val tmpIfo = File(dictDir, "wn.ifo.tmp")
        val tmpIdx = File(dictDir, "wn.idx.tmp")
        val tmpDict = File(dictDir, "wn.dict.tmp")
        try {
            val am = context.assets
            am.open("dictionary/dictd_www.dict.org_wn.ifo").use { input ->
                tmpIfo.outputStream().use { input.copyTo(it) }
            }
            am.open("dictionary/dictd_www.dict.org_wn.idx").use { input ->
                tmpIdx.outputStream().use { input.copyTo(it) }
            }
            // AAPT2 pre-decompresses .gz assets and STRIPS the extension, so
            // the bundled file ships inside the APK as a plain ".dict".
            // Opening the ".dict.gz" name threw FileNotFound, the catch below
            // swallowed it, and the dictionary silently never installed at
            // all — the root cause of "the dictionary doesn't work".
            try {
                am.open("dictionary/dictd_www.dict.org_wn.dict").use { input ->
                    tmpDict.outputStream().use { input.copyTo(it) }
                }
            } catch (_: java.io.FileNotFoundException) {
                am.open("dictionary/dictd_www.dict.org_wn.dict.gz").use { gz ->
                    java.util.zip.GZIPInputStream(gz).use { input ->
                        tmpDict.outputStream().use { input.copyTo(it) }
                    }
                }
            }
            if (!tmpIfo.renameTo(ifoTarget) || !tmpIdx.renameTo(idxTarget) || !tmpDict.renameTo(dictTarget)) {
                throw java.io.IOException("rename into place failed")
            }
            android.util.Log.d("CalmNav", "bundled dictionary installed (${dictTarget.length()} bytes)")
        } catch (e: Exception) {
            android.util.Log.d("CalmNav", "bundled dictionary install FAILED: $e")
            listOf(tmpIfo, tmpIdx, tmpDict, ifoTarget, idxTarget, dictTarget).forEach {
                try { it.delete() } catch (_: Exception) {}
            }
        }
    }

    /**
     * Import a user-picked StarDict file. Supports two source shapes:
     *   - A ZIP containing the .ifo + .idx + .dict[.dz] trio (the common
     *     distribution format on sites like huzheng.org).
     *   - A bare .ifo file when the user also has .idx and .dict in the same
     *     directory accessible via SAF. We only do the ZIP case here because
     *     SAF doesn't reliably give us sibling files.
     *
     * Returns the names of the dictionaries that were added, or empty + error
     * message on failure.
     */
    fun importFromUri(uri: Uri): ImportResult {
        return try {
            val resolver = context.contentResolver
            val mime = resolver.getType(uri) ?: ""
            val name = queryDisplayName(uri) ?: "imported"
            val before = dictionaries.size

            if (mime == "application/zip" || name.endsWith(".zip", true)) {
                resolver.openInputStream(uri)?.use { stream ->
                    extractZip(stream, dictDir)
                } ?: return ImportResult(emptyList(), "Couldn't open the file.")
            } else if (name.endsWith(".ifo", true)) {
                // Single .ifo — copy in, but warn user we need .idx + .dict too
                val outFile = File(dictDir, name)
                resolver.openInputStream(uri)?.use { input ->
                    outFile.outputStream().use { input.copyTo(it) }
                } ?: return ImportResult(emptyList(), "Couldn't open the file.")
                // Check if the matching .idx and .dict were already there
                val base = outFile.absolutePath.removeSuffix(".ifo")
                if (!File("$base.idx").exists()) {
                    return ImportResult(emptyList(),
                        "Got the .ifo but not .idx or .dict — please import the dictionary as a ZIP containing all files together.")
                }
            } else {
                return ImportResult(emptyList(),
                    "Unsupported file. Pick a StarDict ZIP (containing .ifo + .idx + .dict[.dz]) or use a known dictionary package.")
            }

            loadBundledDict()
            val added = dictionaries.size - before
            if (added == 0) {
                ImportResult(emptyList(), "No StarDict files found inside the import.")
            } else {
                ImportResult(dictionaries.takeLast(added).map { it.name }, null)
            }
        } catch (e: Exception) {
            ImportResult(emptyList(), "Import failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    fun removeDictionary(name: String) {
        synchronized(dictionaries) {
            val dict = dictionaries.find { it.name == name } ?: return
            dict.delete()
            dictionaries.remove(dict)
        }
    }

    fun lookup(word: String): List<DictEntry> {
        val dicts = synchronized(dictionaries) { dictionaries.toList() }
        for (candidate in candidatesFor(word)) {
            val results = mutableListOf<DictEntry>()
            for (dict in dicts) {
                dict.lookup(candidate)?.let { def ->
                    results.add(DictEntry(word = candidate, definition = def, source = dict.name))
                }
            }
            if (results.isNotEmpty()) return results
        }
        return emptyList()
    }

    /**
     * Text selected in a book is messy — curly quotes, trailing commas,
     * inflected forms — while WordNet only indexes base forms. Build a
     * candidate chain from most to least faithful and return the first hit:
     * "Running," → running → run; "trees" → tree; "deepest" → deep.
     */
    private fun candidatesFor(raw: String): List<String> {
        val cleaned = raw.trim()
            .replace('‘', '\'').replace('’', '\'')
            .trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
            .lowercase()
        if (cleaned.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        out.add(cleaned)
        // A phrase selection: try the whole thing (WordNet has multi-word
        // entries), then fall back to its first word.
        val first = cleaned.split(Regex("\\s+")).first()
            .trim { !it.isLetterOrDigit() && it != '\'' && it != '-' }
        if (first.isNotEmpty()) out.add(first)
        for (w in out.toList()) {
            if (w.endsWith("'s")) out.add(w.dropLast(2))
            if (w.endsWith("'")) out.add(w.dropLast(1))
        }
        for (w in out.toList()) out.addAll(inflectionsOf(w))
        return out.filter { it.length >= 2 }
    }

    private fun inflectionsOf(w: String): List<String> {
        val c = mutableListOf<String>()
        fun doubled(stem: String) =
            stem.length >= 2 && stem[stem.length - 1] == stem[stem.length - 2]
        if (w.endsWith("ies")) c.add(w.dropLast(3) + "y")            // ladies → lady
        if (w.endsWith("es")) { c.add(w.dropLast(2)); c.add(w.dropLast(1)) }  // boxes → box
        if (w.endsWith("s") && !w.endsWith("ss")) c.add(w.dropLast(1))        // trees → tree
        if (w.endsWith("ing")) {
            val stem = w.dropLast(3)
            c.add(stem)                                              // walking → walk
            c.add(stem + "e")                                        // making → make
            if (doubled(stem)) c.add(stem.dropLast(1))               // running → run
        }
        if (w.endsWith("ied")) c.add(w.dropLast(3) + "y")            // carried → carry
        if (w.endsWith("ed")) {
            val stem = w.dropLast(2)
            c.add(stem)                                              // walked → walk
            c.add(w.dropLast(1))                                     // loved → love
            if (doubled(stem)) c.add(stem.dropLast(1))               // stopped → stop
        }
        if (w.endsWith("er")) { c.add(w.dropLast(2)); c.add(w.dropLast(1)) }   // deeper → deep
        if (w.endsWith("est")) { c.add(w.dropLast(3)); c.add(w.dropLast(2)) }  // deepest → deep
        if (w.endsWith("ly")) c.add(w.dropLast(2))                   // quickly → quick
        return c
    }

    private fun queryDisplayName(uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        } catch (_: Exception) { null }
    }

    private fun extractZip(input: java.io.InputStream, destDir: File) {
        ZipInputStream(input).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    val name = entry.name.substringAfterLast('/')
                    val lower = name.lowercase()
                    if (lower.endsWith(".ifo") || lower.endsWith(".idx") || lower.endsWith(".dict") || lower.endsWith(".dict.dz") || lower.endsWith(".syn")) {
                        File(destDir, name).outputStream().use { out -> zis.copyTo(out) }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    data class ImportResult(val added: List<String>, val error: String?)
}

class StarDict(ifoFile: File) {
    val name: String
    private val ifoFileRef = ifoFile
    private val idxFile: File
    private val dictFile: File
    private val index: Map<String, Pair<Long, Int>>

    init {
        val base = ifoFile.absolutePath.removeSuffix(".ifo")
        idxFile = File("$base.idx")
        // .dict.dz is dictzip (gzip-framed). Random-access reads into the
        // compressed file return garbage bytes, so definitions from any .dz
        // dictionary rendered as mojibake. Inflate it once to a plain .dict.
        val plain = File("$base.dict")
        val dz = File("$base.dict.dz")
        if (!plain.exists() && dz.exists()) {
            try {
                java.util.zip.GZIPInputStream(dz.inputStream()).use { input ->
                    plain.outputStream().use { input.copyTo(it) }
                }
                dz.delete()
            } catch (e: Exception) {
                try { plain.delete() } catch (_: Exception) {}
                throw IllegalStateException("Couldn't decompress ${dz.name}")
            }
        }
        dictFile = plain.takeIf { it.exists() }
            ?: throw IllegalStateException("No .dict file for ${ifoFile.name}")

        val ifoContent = ifoFile.readText()
        name = ifoContent.lines().find { it.startsWith("bookname=") }
            ?.substringAfter("=")?.trim() ?: ifoFile.nameWithoutExtension

        index = parseIndex()
    }

    fun lookup(word: String): String? {
        val (offset, size) = index[word] ?: index[word.lowercase()] ?: return null
        return try {
            RandomAccessFile(dictFile, "r").use { raf ->
                raf.seek(offset)
                val bytes = ByteArray(size)
                raf.readFully(bytes)
                String(bytes, StandardCharsets.UTF_8).cleanDefinition(word)
            }
        } catch (_: Exception) { null }
    }

    fun delete() {
        try { ifoFileRef.delete() } catch (_: Exception) {}
        try { idxFile.delete() } catch (_: Exception) {}
        try { dictFile.delete() } catch (_: Exception) {}
    }

    private fun parseIndex(): Map<String, Pair<Long, Int>> {
        val map = mutableMapOf<String, Pair<Long, Int>>()
        try {
            val bytes = idxFile.readBytes()
            var pos = 0
            while (pos < bytes.size) {
                val wordEnd = bytes.indexOf(0.toByte(), pos)
                if (wordEnd < 0) break
                val word = String(bytes, pos, wordEnd - pos, StandardCharsets.UTF_8)
                pos = wordEnd + 1
                if (pos + 8 > bytes.size) break
                val offset = readInt(bytes, pos).toLong() and 0xFFFFFFFFL
                val size = readInt(bytes, pos + 4)
                map[word.lowercase()] = Pair(offset, size)
                pos += 8
            }
        } catch (_: Exception) { }
        return map
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
                ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
                ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
                (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun ByteArray.indexOf(byte: Byte, start: Int): Int {
        for (i in start until size) {
            if (this[i] == byte) return i
        }
        return -1
    }

    private fun String.cleanDefinition(word: String): String {
        val stripped = replace(Regex("<[^>]+>"), "")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .trim()
        return formatWordNet(stripped, word)
    }

    /**
     * Raw dictd/WordNet entries are hard-wrapped at ~68 columns with indented
     * continuations, terse POS codes ("adj 1:", bare "n :"), repeated
     * headwords, and {brace}/[syn: …] markup — unreadable on a 480px page.
     * Reflow into one paragraph per sense with spelled-out parts of speech.
     * Entries that don't look like WordNet pass through mostly untouched
     * (imported dictionaries keep their own shape, just unwrapped).
     */
    private fun formatWordNet(raw: String, word: String): String {
        val posNames = mapOf("n" to "noun", "v" to "verb", "adj" to "adjective", "adv" to "adverb")
        val numbered = Regex("^(n|v|adj|adv)\\s+(\\d+):\\s*(.*)$")
        val bare = Regex("^(n|v|adj|adv)\\s*:\\s*(.*)$")
        val senseOnly = Regex("^(\\d+):\\s*(.*)$")
        val paras = mutableListOf<StringBuilder>()
        var sawSense = false
        for (line in raw.lines()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            if (t.equals(word, ignoreCase = true)) continue   // headword-only line
            // "unconscious n : …" — strip the headword prefix ONLY when a POS
            // marker follows (never inside example sentences).
            val candidates = buildList {
                add(t)
                if (t.lowercase().startsWith(word.lowercase() + " ")) add(t.substring(word.length).trim())
            }
            val hit = candidates.firstNotNullOfOrNull { c ->
                numbered.find(c)?.let { m ->
                    "${posNames[m.groupValues[1]]} ${m.groupValues[2]}. ${m.groupValues[3]}"
                } ?: bare.find(c)?.let { m ->
                    "${posNames[m.groupValues[1]]} — ${m.groupValues[2]}"
                }
            }
            when {
                hit != null -> { paras.add(StringBuilder(hit)); sawSense = true }
                sawSense && senseOnly.matches(t) -> {
                    val m = senseOnly.find(t)!!
                    paras.add(StringBuilder("${m.groupValues[1]}. ${m.groupValues[2]}"))
                }
                paras.isNotEmpty() && line.firstOrNull()?.isWhitespace() == true ->
                    paras.last().append(' ').append(t)   // unwrap hard line breaks
                else -> paras.add(StringBuilder(t))
            }
        }
        if (paras.isEmpty()) return raw
        return paras.joinToString("\n\n") { it.toString() }
            .replace(Regex("\\{([^}]*)\\}"), "$1")
            .replace(Regex("\\[\\s*syn:\\s*([^\\]]*)\\]"), "· similar: $1")
            .replace(Regex("\\[\\s*ant:\\s*([^\\]]*)\\]"), "· opposite: $1")
            .replace(Regex(" {2,}"), " ")
            .trim()
    }
}
