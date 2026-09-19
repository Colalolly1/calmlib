package com.calmlib.reader.data.model

import org.json.JSONObject

data class ReadingSettings(
    val fontSize: Float = 18f,
    val lineSpacing: Float = 1.6f,
    val marginHorizontal: Int = 24,
    val marginVertical: Int = 16,
    val fontFamily: String = "literata",
    val contrastBoost: Boolean = false,
    val boldMode: Boolean = false,
    val antiAliasing: Boolean = true,
    // Default: full e-ink refresh on EVERY page turn. Partial updates leave
    // ghosting and half-painted images on this panel, which reads as the
    // reader misbehaving. The options remain for anyone who prefers fewer
    // flashes, but the sane default is the one that always looks right.
    val autoRefreshInterval: Int = 1,
    val justify: Boolean = true,
    val hyphenation: Boolean = true,
    val bionicReading: Boolean = false,
    /** PDF only: 1 = fit the page width; kept per book so every page opens at your zoom. */
    val pdfZoom: Float = 1f,
    /** PDF only: crop the white border around the printed area so text fills the screen. */
    val pdfTrimMargins: Boolean = false,
) {
    fun toJson(): String = JSONObject().apply {
        put("fontSize", fontSize.toDouble())
        put("lineSpacing", lineSpacing.toDouble())
        put("marginHorizontal", marginHorizontal)
        put("marginVertical", marginVertical)
        put("fontFamily", fontFamily)
        put("contrastBoost", contrastBoost)
        put("boldMode", boldMode)
        put("antiAliasing", antiAliasing)
        put("autoRefreshInterval", autoRefreshInterval)
        put("refreshMigrated", true)
        put("justify", justify)
        put("hyphenation", hyphenation)
        put("bionicReading", bionicReading)
        put("pdfZoom", pdfZoom.toDouble())
        put("pdfTrimMargins", pdfTrimMargins)
    }.toString()

    companion object {
        /** Parse JSON back into a settings object, falling back to defaults for any
         *  missing or malformed fields. Returns the defaults if `json` is null. */
        fun fromJson(json: String?): ReadingSettings {
            if (json.isNullOrBlank()) return ReadingSettings()
            return try {
                val obj = JSONObject(json)
                val d = ReadingSettings()
                ReadingSettings(
                    fontSize = obj.optDouble("fontSize", d.fontSize.toDouble()).toFloat(),
                    lineSpacing = obj.optDouble("lineSpacing", d.lineSpacing.toDouble()).toFloat(),
                    marginHorizontal = obj.optInt("marginHorizontal", d.marginHorizontal),
                    marginVertical = obj.optInt("marginVertical", d.marginVertical),
                    fontFamily = obj.optString("fontFamily", d.fontFamily),
                    contrastBoost = obj.optBoolean("contrastBoost", d.contrastBoost),
                    boldMode = obj.optBoolean("boldMode", d.boldMode),
                    antiAliasing = obj.optBoolean("antiAliasing", d.antiAliasing),
                    // One-time migration: books saved before every-page refresh
                    // became the default carry a 0 nobody actually chose. The
                    // "refreshMigrated" marker is written whenever settings are
                    // saved from now on, so a deliberate Off is respected.
                    autoRefreshInterval = obj.optInt("autoRefreshInterval", d.autoRefreshInterval).let { stored ->
                        if (stored == 0 && !obj.optBoolean("refreshMigrated", false)) d.autoRefreshInterval else stored
                    },
                    justify = obj.optBoolean("justify", d.justify),
                    hyphenation = obj.optBoolean("hyphenation", d.hyphenation),
                    bionicReading = obj.optBoolean("bionicReading", d.bionicReading),
                    pdfZoom = obj.optDouble("pdfZoom", d.pdfZoom.toDouble()).toFloat().coerceIn(1f, 4f),
                    pdfTrimMargins = obj.optBoolean("pdfTrimMargins", d.pdfTrimMargins),
                )
            } catch (_: Exception) {
                ReadingSettings()
            }
        }
    }
}

enum class AutoRefresh(val pages: Int, val label: String) {
    OFF(0, "Off"),
    EVERY_PAGE(1, "Every page"),
    EVERY_3(3, "Every 3 pages"),
    EVERY_5(5, "Every 5 pages");

    companion object {
        fun fromPages(pages: Int) = entries.find { it.pages == pages } ?: OFF
    }
}
