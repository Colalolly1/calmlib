package com.calmlib.reader.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

data class TocEntry(val title: String, val href: String, val depth: Int = 0)
data class SpineItem(val id: String, val href: String, val mediaType: String)

class EpubParser(private val file: File) : Closeable {
    private val zip = ZipFile(file)
    private val opfPath: String
    private val opfDir: String
    private val manifest = mutableMapOf<String, Pair<String, String>>() // id -> (href, mediaType)
    private val spine = mutableListOf<SpineItem>()
    private val toc = mutableListOf<TocEntry>()

    var title: String = ""
        private set
    var author: String = ""
        private set
    var language: String = ""
        private set
    private var coverId: String? = null

    val chapters: List<SpineItem> get() = spine
    val tableOfContents: List<TocEntry> get() = toc

    /** Directory of the OPF inside the zip (e.g. "OEBPS/"), which spine hrefs are
     *  relative to. Callers locating extracted chapter files must prepend this —
     *  resolving from the zip root instead silently breaks relative image paths. */
    val contentDir: String get() = opfDir

    init {
        opfPath = findOpfPath()
        opfDir = opfPath.substringBeforeLast("/", "").let { if (it.isEmpty()) "" else "$it/" }
        parseOpf()
        // The TOC is a convenience, not a requirement — one malformed entity in
        // toc.ncx must not reject the whole book ("This file could not be opened").
        try { parseToc() } catch (_: Exception) { toc.clear() }
    }

    fun extractToDir(cacheDir: File): File {
        val name = "epub_${file.nameWithoutExtension.take(60)}_${file.lastModified()}"
        val dir = File(cacheDir, name)
        // A ".done" marker makes completion explicit. Presence of files is NOT
        // completion: an extraction interrupted by a process kill used to leave
        // a half-written directory that was trusted forever after, so the book
        // was permanently missing chapters/images.
        val done = File(dir, ".calm_complete")
        if (done.exists()) return dir
        if (dir.exists()) dir.deleteRecursively()

        val staging = File(cacheDir, "$name.tmp")
        if (staging.exists()) staging.deleteRecursively()
        staging.mkdirs()
        val rootPath = staging.canonicalPath + File.separator
        var totalBytes = 0L
        var entryCount = 0
        try {
            zip.entries().asSequence().forEach { entry ->
                // Zip-bomb bound: a few-hundred-KB EPUB can expand to gigabytes
                // and fill a user's device. Count what we actually write —
                // entry.size is attacker-controlled metadata.
                if (++entryCount > MAX_ENTRIES) throw IllegalStateException("too many entries")
                val target = File(staging, entry.name)
                // Zip-slip guard: a malicious EPUB can carry entries named "../../x"
                // that would otherwise be written outside the extraction directory.
                if (!target.canonicalPath.startsWith(rootPath)) return@forEach
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                totalBytes += n
                                if (totalBytes > MAX_EXTRACT_BYTES) {
                                    throw IllegalStateException("extraction exceeds ${MAX_EXTRACT_BYTES / 1024 / 1024}MB")
                                }
                                output.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            done.parentFile?.mkdirs()
            File(staging, ".calm_complete").writeText("ok")
            if (!staging.renameTo(dir)) throw IllegalStateException("could not finalise extraction")
        } catch (t: Throwable) {
            staging.deleteRecursively()
            throw t
        }
        pruneOldExtractions(cacheDir, keep = dir.name)
        return dir
    }

    /**
     * Extractions are keyed by file mtime, so re-saving a book (or importing a
     * new edition) orphans the old directory. Unbounded, this quietly eats a
     * user's storage — keep only the most recent few.
     */
    private fun pruneOldExtractions(cacheDir: File, keep: String) {
        try {
            cacheDir.listFiles()
                ?.filter { it.isDirectory && it.name.startsWith("epub_") && it.name != keep }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CACHED_BOOKS)
                ?.forEach { it.deleteRecursively() }
        } catch (_: Exception) { }
    }

    companion object {
        private const val MAX_EXTRACT_BYTES = 400L * 1024 * 1024
        private const val MAX_ENTRIES = 5000
        private const val MAX_CACHED_BOOKS = 4
    }

    fun extractCover(): Bitmap? {
        val coverHref = coverId?.let { manifest[it]?.first }
            ?: manifest.values.firstOrNull { it.second.startsWith("image/") && it.first.contains("cover", true) }?.first
            ?: return null

        val entryPath = opfDir + coverHref
        val entry = zip.getEntry(entryPath) ?: zip.getEntry(coverHref) ?: return null
        return zip.getInputStream(entry).use { BitmapFactory.decodeStream(it) }
    }

    /**
     * Uncompressed byte size of each spine item, used as a weight for whole-book
     * progress estimation ("chapter 3 of 12" says nothing when chapter sizes vary
     * by 50×; bytes of XHTML track reading length closely enough).
     */
    fun chapterSizes(): List<Long> = spine.map { item ->
        val entry = zip.getEntry(opfDir + item.href) ?: zip.getEntry(item.href)
        val size = entry?.size ?: -1L
        if (size > 0) size else 1L
    }

    fun getChapterContent(index: Int): String? {
        if (index !in spine.indices) return null
        val href = opfDir + spine[index].href
        val entry = zip.getEntry(href) ?: zip.getEntry(spine[index].href) ?: return null
        return zip.getInputStream(entry).bufferedReader().readText()
    }

    override fun close() {
        zip.close()
    }

    private fun findOpfPath(): String {
        val container = zip.getEntry("META-INF/container.xml")
            ?: throw IllegalStateException("Not a valid EPUB: missing container.xml")
        val parser = newParser(zip.getInputStream(container))
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG && parser.name == "rootfile") {
                return parser.getAttributeValue(null, "full-path")
                    ?: throw IllegalStateException("No OPF path in container.xml")
            }
        }
        throw IllegalStateException("No rootfile in container.xml")
    }

    private fun parseOpf() {
        val entry = zip.getEntry(opfPath) ?: return
        val parser = newParser(zip.getInputStream(entry))
        var inMetadata = false
        var inManifest = false
        var inSpine = false
        var currentTag = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    when {
                        currentTag == "metadata" -> inMetadata = true
                        currentTag == "manifest" -> inManifest = true
                        currentTag == "spine" -> inSpine = true
                        inMetadata && (currentTag == "meta") -> {
                            val name = parser.getAttributeValue(null, "name")
                            if (name == "cover") {
                                coverId = parser.getAttributeValue(null, "content")
                            }
                        }
                        inManifest && currentTag == "item" -> {
                            val id = parser.getAttributeValue(null, "id") ?: ""
                            val href = parser.getAttributeValue(null, "href") ?: ""
                            val mediaType = parser.getAttributeValue(null, "media-type") ?: ""
                            val properties = parser.getAttributeValue(null, "properties") ?: ""
                            manifest[id] = Pair(href, mediaType)
                            if (properties.contains("cover-image")) coverId = id
                        }
                        inSpine && currentTag == "itemref" -> {
                            val idref = parser.getAttributeValue(null, "idref") ?: ""
                            manifest[idref]?.let { (href, mediaType) ->
                                spine.add(SpineItem(idref, href, mediaType))
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inMetadata) {
                        val text = parser.text?.trim() ?: ""
                        when {
                            currentTag.endsWith("title") && title.isEmpty() -> title = text
                            currentTag.endsWith("creator") && author.isEmpty() -> author = text
                            currentTag.endsWith("language") && language.isEmpty() -> language = text
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "metadata" -> inMetadata = false
                        "manifest" -> inManifest = false
                        "spine" -> inSpine = false
                    }
                    currentTag = ""
                }
            }
        }
    }

    private fun parseToc() {
        val tocId = manifest.entries.find { it.value.second == "application/x-dtbncx+xml" }?.key
            ?: manifest.entries.find { it.value.first.endsWith(".ncx", true) }?.key
        val navId = manifest.entries.find {
            val (_, mediaType) = it.value
            mediaType == "application/xhtml+xml" && manifest[it.key]?.first?.contains("nav", true) == true
        }?.key

        if (tocId != null) {
            parseTocNcx(tocId)
        } else if (navId != null) {
            parseTocNav(navId)
        }
    }

    private fun parseTocNcx(tocId: String) {
        val href = manifest[tocId]?.first ?: return
        val entryPath = opfDir + href
        val entry = zip.getEntry(entryPath) ?: zip.getEntry(href) ?: return
        val parser = newParser(zip.getInputStream(entry))

        var depth = 0
        var inNavPoint = false
        var inText = false
        var inContent = false
        var currentTitle = ""
        var currentHref = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "navPoint" -> { depth++; inNavPoint = true; currentTitle = ""; currentHref = "" }
                    "text" -> if (inNavPoint) inText = true
                    "content" -> if (inNavPoint) {
                        currentHref = parser.getAttributeValue(null, "src") ?: ""
                        inContent = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inText) currentTitle = parser.text?.trim() ?: ""
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "navPoint" -> {
                        if (currentTitle.isNotEmpty()) {
                            toc.add(TocEntry(currentTitle, currentHref, depth))
                        }
                        depth--
                        inNavPoint = depth > 0
                    }
                    "text" -> inText = false
                    "content" -> inContent = false
                }
            }
        }
    }

    private fun parseTocNav(navId: String) {
        val href = manifest[navId]?.first ?: return
        val entryPath = opfDir + href
        val entry = zip.getEntry(entryPath) ?: zip.getEntry(href) ?: return
        val parser = newParser(zip.getInputStream(entry))

        var inNav = false
        var inLink = false
        var depth = 0
        var currentHref = ""

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "nav" -> {
                        val type = parser.getAttributeValue(null, "epub:type")
                            ?: parser.getAttributeValue("http://www.idpf.org/2007/ops", "type")
                        if (type == "toc") inNav = true
                    }
                    "ol" -> if (inNav) depth++
                    "a" -> if (inNav) {
                        currentHref = parser.getAttributeValue(null, "href") ?: ""
                        inLink = true
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inLink) {
                        val title = parser.text?.trim() ?: ""
                        if (title.isNotEmpty()) {
                            toc.add(TocEntry(title, currentHref, depth))
                        }
                    }
                }
                XmlPullParser.END_TAG -> when (parser.name) {
                    "nav" -> inNav = false
                    "ol" -> if (inNav) depth--
                    "a" -> inLink = false
                }
            }
        }
    }

    private fun newParser(input: InputStream): XmlPullParser {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()
        parser.setInput(input.bufferedReader())
        return parser
    }
}
