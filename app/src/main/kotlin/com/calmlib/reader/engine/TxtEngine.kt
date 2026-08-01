package com.calmlib.reader.engine

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import com.calmlib.reader.util.readUpTo

class TxtEngine(file: File) {
    val text: String
    val title: String = file.nameWithoutExtension
    /** True when the file was longer than MAX_CHARS and has been truncated. */
    val truncated: Boolean

    companion object {
        /** A plain-text book past this length is either a log dump or an attack;
         *  either way the whole-file-in-memory reader would OOM the 3GB device. */
        private const val MAX_CHARS = 2_000_000
        private const val SNIFF_BYTES = 4096
    }

    init {
        val charset = detectCharset(file)
        val buf = CharArray(MAX_CHARS)
        var filled = 0
        InputStreamReader(FileInputStream(file), charset).use { reader ->
            while (filled < MAX_CHARS) {
                val n = reader.read(buf, filled, MAX_CHARS - filled)
                if (n < 0) break
                filled += n
            }
            truncated = filled >= MAX_CHARS && reader.read() >= 0
        }
        text = String(buf, 0, filled)
    }

    fun toHtmlPages(): String {
        // One pass, one buffer. The old chained replace() built five more full
        // copies of the text — enough to OOM on a large file.
        val sb = StringBuilder(text.length + 64)
        sb.append("<p>")
        var i = 0
        while (i < text.length) {
            when (val c = text[i]) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\n' -> {
                    if (i + 1 < text.length && text[i + 1] == '\n') {
                        sb.append("</p><p>")
                        i++
                        // Collapse longer runs of blank lines into one break.
                        while (i + 1 < text.length && text[i + 1] == '\n') i++
                    } else {
                        sb.append("<br>")
                    }
                }
                '\r' -> {}
                else -> sb.append(c)
            }
            i++
        }
        sb.append("</p>")
        if (truncated) {
            sb.append("<p><em>[This file is very large — CalmLib is showing the first part of it.]</em></p>")
        }
        return sb.toString()
    }

    private fun detectCharset(file: File): Charset {
        // NB: InputStream.readNBytes is API 33+; this app targets API 31
        // devices, where it throws NoSuchMethodError and takes the process
        // down. Read the sniff buffer manually.
        val bytes = file.inputStream().use { it.readUpTo(SNIFF_BYTES) }
        // BOM detection
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return Charsets.UTF_8
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return Charsets.UTF_16LE
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return Charsets.UTF_16BE

        // Heuristic: check if valid UTF-8
        return try {
            val decoded = String(bytes, Charsets.UTF_8)
            if (decoded.contains('�')) Charsets.ISO_8859_1 else Charsets.UTF_8
        } catch (_: Exception) {
            Charsets.ISO_8859_1
        }
    }
}
