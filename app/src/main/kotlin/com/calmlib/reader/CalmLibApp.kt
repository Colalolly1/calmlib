package com.calmlib.reader

import android.app.Application
import com.calmlib.reader.ui.theme.CalmFonts
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class CalmLibApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CalmFonts.init(this)
        // PDFBox needs to be told where to load its bundled font fallbacks from.
        // Calling this before any PDDocument.load() avoids "no fonts available" errors
        // during text extraction in the PDF→EPUB converter.
        PDFBoxResourceLoader.init(applicationContext)
    }
}
