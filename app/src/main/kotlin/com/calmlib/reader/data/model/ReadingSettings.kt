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
    val autoRefreshInterval: Int = 0,
    val justify: Boolean = true,
    val hyphenation: Boolean = true,
    val bionicReading: Boolean = false,
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
        put("justify", justify)
        put("hyphenation", hyphenation)
        put("bionicReading", bionicReading)
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
                    autoRefreshInterval = obj.optInt("autoRefreshInterval", d.autoRefreshInterval),
                    justify = obj.optBoolean("justify", d.justify),
                    hyphenation = obj.optBoolean("hyphenation", d.hyphenation),
                    bionicReading = obj.optBoolean("bionicReading", d.bionicReading),
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
