package com.calmlib.reader.engine

import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset

class TxtEngine(file: File) {
    val text: String
    val title: String = file.nameWithoutExtension

    init {
        val charset = detectCharset(file)
        text = InputStreamReader(FileInputStream(file), charset).use { it.readText() }
    }

    fun toHtmlPages(): String {
        val escaped = text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n\n", "</p><p>")
            .replace("\n", "<br>")
        return "<p>$escaped</p>"
    }

    private fun detectCharset(file: File): Charset {
        val bytes = file.inputStream().use { it.readNBytes(4096) }
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
