package com.calmlib.reader.engine

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Convert a text-based PDF to a valid EPUB 2.0 file so the user can apply text
 * settings (font size, margins, justify, etc.) to it. Image-only / scanned PDFs
 * will produce an empty EPUB — those need OCR which we don't ship.
 *
 * Heuristics:
 *   - Pull text per page via PDFBox-Android's PDFTextStripper.
 *   - Look for chapter markers in the extracted text (Chapter 1, Part II, etc).
 *   - Fall back to one chapter per ~20 PDF pages when no markers are found.
 */
class PdfToEpubConverter(private val context: Context) {

    data class Progress(val pagesProcessed: Int, val totalPages: Int, val phase: String)

    private val chapterMarker = Regex(
        pattern = """^\s*(chapter|part|book|prologue|epilogue|introduction|preface|foreword)\s*[ivxlcdm\d]*\.?:?\s*.*$""",
        option = RegexOption.IGNORE_CASE,
    )

    suspend fun convert(
        pdfFile: File,
        outputDir: File,
        onProgress: (Progress) -> Unit = {},
    ): Result = withContext(Dispatchers.IO) {
        val document = PDDocument.load(pdfFile)
        try {
            val pageCount = document.numberOfPages
            if (pageCount == 0) return@withContext Result.Failure("PDF has no pages")

            val info = document.documentInformation
            val title = (info?.title?.takeIf { it.isNotBlank() }
                ?: pdfFile.nameWithoutExtension).trim()
            val author = (info?.author?.takeIf { it.isNotBlank() } ?: "").trim()

            // Extract page by page so we keep memory usage reasonable on the Mudita.
            val stripper = PDFTextStripper()
            stripper.lineSeparator = "\n"
            stripper.paragraphStart = "\n"
            val pageTexts = ArrayList<String>(pageCount)
            for (page in 1..pageCount) {
                onProgress(Progress(page, pageCount, "Extracting text"))
                stripper.startPage = page
                stripper.endPage = page
                pageTexts.add(stripper.getText(document).trim())
            }

            val fullText = pageTexts.joinToString("\n\n")
            if (fullText.isBlank()) {
                return@withContext Result.Failure(
                    "No text in this PDF — it's probably scanned images. OCR isn't supported."
                )
            }

            val chapters = splitIntoChapters(fullText, pageTexts, title)

            onProgress(Progress(pageCount, pageCount, "Building EPUB"))
            val safeName = sanitize(title.ifBlank { pdfFile.nameWithoutExtension })
            val output = File(outputDir, "$safeName.epub")
            writeEpub(output, title, author, chapters)

            Result.Success(output)
        } catch (e: Exception) {
            Result.Failure("Conversion failed: ${e.message ?: e.javaClass.simpleName}")
        } finally {
            try { document.close() } catch (_: Exception) {}
        }
    }

    sealed class Result {
        data class Success(val epub: File) : Result()
        data class Failure(val message: String) : Result()
    }

    private data class Chapter(val title: String, val paragraphs: List<String>)

    private fun splitIntoChapters(fullText: String, pageTexts: List<String>, bookTitle: String): List<Chapter> {
        val lines = fullText.lines()
        // Locate plausible chapter-heading lines: short, match the regex, surrounded by blanks.
        val boundaries = mutableListOf<Int>()
        for ((i, raw) in lines.withIndex()) {
            val line = raw.trim()
            if (line.isEmpty() || line.length > 60) continue
            if (!chapterMarker.matches(line)) continue
            val prevBlank = i == 0 || lines[i - 1].isBlank()
            val nextNonBlank = (i + 1 until lines.size).any { lines[it].isNotBlank() }
            if (prevBlank && nextNonBlank) boundaries.add(i)
        }

        // If we found plausible boundaries, slice between them.
        if (boundaries.size >= 2) {
            val chapters = mutableListOf<Chapter>()
            for (idx in boundaries.indices) {
                val start = boundaries[idx]
                val end = if (idx + 1 < boundaries.size) boundaries[idx + 1] else lines.size
                val title = lines[start].trim()
                val content = lines.subList(start + 1, end).joinToString("\n").trim()
                chapters.add(Chapter(title, paragraphsOf(content)))
            }
            // If everything before the first boundary contains text (front matter), add it as Chapter 0.
            if (boundaries.first() > 0) {
                val pre = lines.subList(0, boundaries.first()).joinToString("\n").trim()
                if (pre.length > 100) {
                    return listOf(Chapter("Front matter", paragraphsOf(pre))) + chapters
                }
            }
            return chapters
        }

        // Fall back: group every 20 PDF pages into one chapter so the EPUB has at least
        // a usable spine. Title each chapter "Pages X–Y".
        val perChapter = 20
        val total = pageTexts.size
        if (total <= perChapter) {
            return listOf(Chapter(bookTitle.ifBlank { "Book" }, paragraphsOf(fullText)))
        }
        return (0 until total step perChapter).map { start ->
            val end = (start + perChapter).coerceAtMost(total)
            val title = "Pages ${start + 1}–$end"
            val text = pageTexts.subList(start, end).joinToString("\n\n")
            Chapter(title, paragraphsOf(text))
        }
    }

    /**
     * Split a chunk of extracted text into paragraphs. PDF text extraction often
     * inserts hard-wrap newlines mid-sentence, so we treat a blank line as a real
     * paragraph break and join everything else with spaces.
     */
    private fun paragraphsOf(text: String): List<String> {
        val cleaned = text
            .replace("­", "")        // soft hyphens
            .replace("\r", "")
        val blocks = cleaned.split(Regex("\n\\s*\n+"))
        return blocks.mapNotNull { block ->
            val joined = block.lines()
                .joinToString(" ") { it.trim() }
                .replace(Regex("\\s+"), " ")
                .trim()
            if (joined.isBlank()) null else joined
        }
    }

    // ====== EPUB ZIP WRITER ======

    private fun writeEpub(file: File, title: String, author: String, chapters: List<Chapter>) {
        val bookId = "urn:uuid:${UUID.randomUUID()}"
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            // mimetype MUST be first entry, STORED (uncompressed), no extras. Required by spec.
            val mimetype = "application/epub+zip"
            val mimetypeBytes = mimetype.toByteArray(Charsets.UTF_8)
            val mimetypeEntry = ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mimetypeBytes.size.toLong()
                compressedSize = mimetypeBytes.size.toLong()
                crc = CRC32().apply { update(mimetypeBytes) }.value
            }
            zip.putNextEntry(mimetypeEntry)
            zip.write(mimetypeBytes)
            zip.closeEntry()

            // META-INF/container.xml
            zip.writeEntry("META-INF/container.xml", containerXml())
            // OEBPS/content.opf
            zip.writeEntry("OEBPS/content.opf", contentOpf(title, author, bookId, chapters))
            // OEBPS/toc.ncx
            zip.writeEntry("OEBPS/toc.ncx", tocNcx(title, bookId, chapters))
            // Chapters
            chapters.forEachIndexed { i, ch ->
                zip.writeEntry("OEBPS/chapter${i + 1}.xhtml", chapterXhtml(ch))
            }
        }
    }

    private fun ZipOutputStream.writeEntry(path: String, content: String) {
        val entry = ZipEntry(path)
        putNextEntry(entry)
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun containerXml(): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>"""

    private fun contentOpf(title: String, author: String, bookId: String, chapters: List<Chapter>): String {
        val manifestItems = chapters.indices.joinToString("\n    ") { i ->
            """<item id="chapter${i + 1}" href="chapter${i + 1}.xhtml" media-type="application/xhtml+xml"/>"""
        }
        val spineItems = chapters.indices.joinToString("\n    ") { i ->
            """<itemref idref="chapter${i + 1}"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:title>${title.escapeXml()}</dc:title>
    <dc:creator opf:role="aut">${author.escapeXml()}</dc:creator>
    <dc:identifier id="bookid">$bookId</dc:identifier>
    <dc:language>en</dc:language>
    <meta name="generator" content="CalmLib PDF→EPUB"/>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    $manifestItems
  </manifest>
  <spine toc="ncx">
    $spineItems
  </spine>
</package>"""
    }

    private fun tocNcx(title: String, bookId: String, chapters: List<Chapter>): String {
        val navPoints = chapters.mapIndexed { i, ch ->
            """  <navPoint id="np${i + 1}" playOrder="${i + 1}">
    <navLabel><text>${ch.title.escapeXml()}</text></navLabel>
    <content src="chapter${i + 1}.xhtml"/>
  </navPoint>"""
        }.joinToString("\n")
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE ncx PUBLIC "-//NISO//DTD ncx 2005-1//EN" "http://www.daisy.org/z3986/2005/ncx-2005-1.dtd">
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="$bookId"/>
    <meta name="dtb:depth" content="1"/>
    <meta name="dtb:totalPageCount" content="0"/>
    <meta name="dtb:maxPageNumber" content="0"/>
  </head>
  <docTitle><text>${title.escapeXml()}</text></docTitle>
  <navMap>
$navPoints
  </navMap>
</ncx>"""
    }

    private fun chapterXhtml(ch: Chapter): String {
        val body = ch.paragraphs.joinToString("\n") { "    <p>${it.escapeXml()}</p>" }
        return """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE html PUBLIC "-//W3C//DTD XHTML 1.1//EN" "http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd">
<html xmlns="http://www.w3.org/1999/xhtml">
<head>
  <title>${ch.title.escapeXml()}</title>
</head>
<body>
  <h1>${ch.title.escapeXml()}</h1>
$body
</body>
</html>"""
    }

    private fun String.escapeXml(): String = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    private fun sanitize(name: String): String =
        name.replace(Regex("[^a-zA-Z0-9._ -]"), "_").take(60).ifBlank { "converted" }
}
