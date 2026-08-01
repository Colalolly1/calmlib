package com.calmlib.reader.engine

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.File
import java.io.FileInputStream

data class Fb2Section(val title: String, val html: String)

class Fb2Parser(private val file: File) {
    var title: String = ""
        private set
    var author: String = ""
        private set
    val sections = mutableListOf<Fb2Section>()
    private val binaries = mutableMapOf<String, String>() // id -> base64

    init { parse() }

    fun extractCover(): Bitmap? {
        val coverId = binaries.keys.firstOrNull { it.contains("cover", ignoreCase = true) }
            ?: binaries.keys.firstOrNull()
            ?: return null
        val data = binaries[coverId] ?: return null
        return try {
            val bytes = Base64.decode(data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) { null }
    }

    fun toHtml(): String {
        val sb = StringBuilder()
        sections.forEach { section ->
            if (section.title.isNotEmpty()) sb.append("<h2>${section.title}</h2>")
            sb.append(section.html)
        }
        return sb.toString()
    }

    fun sectionCount(): Int = sections.size

    /** HTML length per section — weight source for whole-book progress. */
    fun sectionSizes(): List<Long> = sections.map { it.html.length.toLong().coerceAtLeast(1L) }

    fun sectionHtml(index: Int): String {
        if (index !in sections.indices) return ""
        val s = sections[index]
        val html = StringBuilder()
        if (s.title.isNotEmpty()) html.append("<h2>${s.title}</h2>")
        html.append(s.html)
        return html.toString()
    }

    val tableOfContents: List<TocEntry>
        get() = sections.mapIndexedNotNull { i, s ->
            if (s.title.isNotEmpty()) TocEntry(s.title, i.toString(), 1) else null
        }

    private fun parse() {
        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = false
        val parser = factory.newPullParser()
        val input = FileInputStream(file)
        parser.setInput(input, null)
        try {
            parseInternal(parser)
        } finally {
            try { input.close() } catch (_: Exception) {}
        }
    }

    private fun parseInternal(parser: org.xmlpull.v1.XmlPullParser) {

        var inDescription = false
        var inTitleInfo = false
        var inBody = false
        var inSection = false
        var inBinary = false
        var binaryId = ""
        var sectionTitle = ""
        var sectionHtml = StringBuilder()
        var currentTag = ""
        var depth = 0
        val textBuffer = StringBuilder()

        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    currentTag = parser.name ?: ""
                    when {
                        currentTag == "description" -> inDescription = true
                        currentTag == "title-info" && inDescription -> inTitleInfo = true
                        currentTag == "body" -> inBody = true
                        currentTag == "section" && inBody -> {
                            if (inSection && sectionHtml.isNotEmpty()) {
                                sections.add(Fb2Section(sectionTitle, sectionHtml.toString()))
                            }
                            inSection = true
                            sectionTitle = ""
                            sectionHtml = StringBuilder()
                            depth++
                        }
                        currentTag == "binary" -> {
                            binaryId = parser.getAttributeValue(null, "id") ?: ""
                            inBinary = true
                            textBuffer.clear()
                        }
                        inSection -> {
                            when (currentTag) {
                                "p" -> sectionHtml.append("<p>")
                                "emphasis" -> sectionHtml.append("<em>")
                                "strong" -> sectionHtml.append("<strong>")
                                "title" -> {} // collect text for section title
                                "epigraph" -> sectionHtml.append("<blockquote>")
                                "poem" -> sectionHtml.append("<div class=\"poem\">")
                                "stanza" -> sectionHtml.append("<div class=\"stanza\">")
                                "v" -> sectionHtml.append("<p class=\"verse\">")
                                "subtitle" -> sectionHtml.append("<h3>")
                                "image" -> {
                                    val href = parser.getAttributeValue(null, "l:href")
                                        ?: parser.getAttributeValue(null, "xlink:href") ?: ""
                                    val id = href.removePrefix("#")
                                    // Payload is untrusted: anything outside the
                                    // base64 alphabet could close the attribute
                                    // and inject markup. Filter, don't escape.
                                    val b64 = (binaries[id] ?: "").filter {
                                        it.isLetterOrDigit() || it == '+' || it == '/' || it == '='
                                    }
                                    if (b64.isNotEmpty()) {
                                        sectionHtml.append("<img src=\"data:image/png;base64,$b64\" />")
                                    }
                                }
                            }
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    val text = parser.text ?: ""
                    when {
                        inBinary -> textBuffer.append(text.trim())
                        inTitleInfo && currentTag == "book-title" && title.isEmpty() -> title = text.trim()
                        inTitleInfo && (currentTag == "first-name" || currentTag == "last-name") -> {
                            author = if (author.isEmpty()) text.trim() else "$author ${text.trim()}"
                        }
                        inSection -> sectionHtml.append(text.escapeHtml())
                    }
                }
                XmlPullParser.END_TAG -> {
                    val tag = parser.name ?: ""
                    when {
                        tag == "description" -> inDescription = false
                        tag == "title-info" -> inTitleInfo = false
                        tag == "body" -> {
                            if (inSection && sectionHtml.isNotEmpty()) {
                                sections.add(Fb2Section(sectionTitle, sectionHtml.toString()))
                            }
                            inBody = false
                        }
                        tag == "section" && inBody -> {
                            depth--
                            if (depth <= 0) inSection = false
                        }
                        tag == "binary" && inBinary -> {
                            binaries[binaryId] = textBuffer.toString()
                            inBinary = false
                        }
                        inSection -> when (tag) {
                            "p" -> sectionHtml.append("</p>")
                            "emphasis" -> sectionHtml.append("</em>")
                            "strong" -> sectionHtml.append("</strong>")
                            "title" -> {
                                // The text collected inside <title><p>...</p></title> becomes section title
                                val html = sectionHtml.toString()
                                val titleText = html.substringAfterLast("<p>").substringBefore("</p>")
                                if (sectionTitle.isEmpty() && titleText.isNotEmpty()) {
                                    sectionTitle = titleText.stripHtml()
                                }
                            }
                            "epigraph" -> sectionHtml.append("</blockquote>")
                            "poem" -> sectionHtml.append("</div>")
                            "stanza" -> sectionHtml.append("</div>")
                            "v" -> sectionHtml.append("</p>")
                            "subtitle" -> sectionHtml.append("</h3>")
                        }
                    }
                    currentTag = ""
                }
            }
        }
    }

    private fun String.escapeHtml() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private fun String.stripHtml() = this.replace(Regex("<[^>]+>"), "").trim()
}
