package com.calmlib.reader.ui.library

import com.calmlib.reader.data.model.Book
import com.calmlib.reader.data.model.BookFormat

/**
 * Title as shown on the shelves. Titles that still look like file names
 * ("328186006-Secret-of-…", "1918__gewurz___hidden_treasures") are tidied for
 * display only — the stored title is untouched so search and rename keep working.
 */
val Book.displayTitle: String
    get() {
        // Some EPUBs carry only an ISBN as their title; the file name is the
        // better label then ("Dreamcatcher by Stephen King", not 9782226131904).
        if (isbnLike.matches(title.trim())) {
            val stem = java.io.File(filePath).nameWithoutExtension
            if (stem.isNotBlank() && !isbnLike.matches(stem)) return prettifyTitle(stem)
        }
        return prettifyTitle(title)
    }

/** Author for display; "Unknown" placeholders from bad metadata are blank. */
val Book.displayAuthor: String
    get() = if (author.equals("unknown", ignoreCase = true) || author.equals("unknown author", ignoreCase = true)) "" else author

private val isbnLike = Regex("""^(97[89])?\d{9}[\dXx]$""")

/** Short format word for cover tags and counts. */
val BookFormat.label: String get() = name

// A leading run of 4+ digits (store IDs, Scribd/Z-Library numbers) with an optional
// short sub-number, followed by a separator.
private val leadingId = Regex("""^\d{4,}(?:[._-]\d{1,3})?[\s._-]+""")
// Trailing download-manager and version junk: " (1)", "_v7", "(z-lib.org)", "[Z-Library]".
private val trailingJunk = Regex(
    """(\s*\(\d+\)|\s*\(z-lib(?:\.org)?\)|\s*\[[^\]]*(?:z-lib|library)[^\]]*\]|[\s._-]+v\d+)+$""",
    RegexOption.IGNORE_CASE,
)

internal fun prettifyTitle(raw: String): String {
    var t = raw.trim()
    if (t.isEmpty()) return t
    val looksLikeFile = !t.contains(' ') || t.contains('_') || leadingId.containsMatchIn(t) || trailingJunk.containsMatchIn(t)
    if (!looksLikeFile) return t
    t = t.replace(leadingId, "")
    t = t.replace(trailingJunk, "")
    if (!t.contains(' ')) t = t.replace('-', ' ')
    // "AnatomyOfThePsyche" → "Anatomy Of The Psyche"
    if (!t.contains(' ')) t = t.replace(Regex("""(?<=[a-z])(?=[A-Z])|(?<=[A-Za-z])(?=\d)"""), " ")
    t = t.replace('_', ' ').replace(Regex("""\s+"""), " ").trim()
    if (t.isEmpty()) return raw.trim()
    if (t == t.lowercase() || t == t.uppercase()) {
        t = t.split(' ').joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.titlecase() } }
    }
    return t
}
