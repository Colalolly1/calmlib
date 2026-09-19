package com.calmlib.reader.ui.theme

import android.content.Context
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import java.io.File

object CalmFonts {
    private var serifFamily: FontFamily = FontFamily.Serif
    private var sansFamily: FontFamily = FontFamily.SansSerif
    private var monoFamily: FontFamily = FontFamily.Monospace

    val serif: FontFamily get() = serifFamily
    val sans: FontFamily get() = sansFamily
    val mono: FontFamily get() = monoFamily

    fun init(context: Context) {
        serifFamily = loadFontFamily(context, "literata", FontFamily.Serif)
        sansFamily = loadFontFamily(context, "atkinson", FontFamily.SansSerif)
        monoFamily = loadFontFamily(context, "jetbrains_mono", FontFamily.Monospace)
    }

    private fun loadFontFamily(context: Context, prefix: String, fallback: FontFamily): FontFamily {
        return try {
            val assets = context.assets.list("fonts") ?: emptyArray()
            val fonts = assets.filter { it.startsWith(prefix) && it.endsWith(".ttf") }
            if (fonts.isEmpty()) return fallback
            val fontList = fonts.mapNotNull { filename ->
                val weight = when {
                    filename.contains("bold", true) -> FontWeight.Bold
                    filename.contains("regular", true) -> FontWeight.Normal
                    else -> FontWeight.Normal
                }
                try {
                    Font(path = "fonts/$filename", assetManager = context.assets, weight = weight)
                } catch (_: Exception) { null }
            }
            if (fontList.isEmpty()) fallback else FontFamily(fontList)
        } catch (_: Exception) {
            fallback
        }
    }

    fun familyByName(name: String): FontFamily = when (name.lowercase()) {
        "literata", "serif" -> serif
        "atkinson", "sans" -> sans
        "jetbrains_mono", "mono" -> mono
        else -> serif
    }
}

object CalmTypography {
    val libraryTitle = TextStyle(
        fontFamily = CalmFonts.serif,
        fontWeight = FontWeight.Normal,
        fontSize = 28.sp,
        letterSpacing = (-0.5).sp,
    )

    val sectionHeader = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        letterSpacing = 1.5.sp,
    )

    val bookTitle = TextStyle(
        fontFamily = CalmFonts.serif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        letterSpacing = (-0.2).sp,
    )

    val bookAuthor = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = androidx.compose.ui.graphics.Color(0xFF666666),
    )

    val metadata = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        color = androidx.compose.ui.graphics.Color(0xFF777777),
    )

    val body = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
    )

    val controlLabel = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
    )

    val controlValue = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        color = androidx.compose.ui.graphics.Color(0xFF666666),
    )

    val emptyTitle = TextStyle(
        fontFamily = CalmFonts.serif,
        fontWeight = FontWeight.Normal,
        fontSize = 20.sp,
    )

    val emptyBody = TextStyle(
        fontFamily = CalmFonts.sans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = androidx.compose.ui.graphics.Color(0xFF777777),
    )
}
