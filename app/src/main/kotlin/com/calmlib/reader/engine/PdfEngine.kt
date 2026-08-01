package com.calmlib.reader.engine

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer as PdfBoxRenderer
import java.io.Closeable
import java.io.File

/**
 * PDF rendering with a graceful fallback chain.
 *
 *  1. Android's built-in PdfRenderer — native, fast, but rejects encrypted PDFs
 *     unconditionally and chokes on some PDF features.
 *  2. PDFBox-Android renderer — Java-based, slower but FAR more lenient. Handles
 *     view-only encrypted PDFs (empty password), damaged files, and most PDF 2.0
 *     features.
 *
 * We try (1) first. If it fails for ANY reason we silently fall back to (2). The
 * caller never sees the failure; they just get a slightly slower render.
 */
class PdfEngine(file: File) : Closeable {
    enum class Backend { ANDROID, PDFBOX }

    var backend: Backend = Backend.ANDROID
        private set

    @Volatile private var closed: Boolean = false
    private var descriptor: ParcelFileDescriptor? = null
    private var androidRenderer: PdfRenderer? = null

    private var pdfBoxDoc: PDDocument? = null
    private var pdfBoxRenderer: PdfBoxRenderer? = null

    val pageCount: Int
        get() = androidRenderer?.pageCount
            ?: pdfBoxDoc?.numberOfPages
            ?: 0

    init {
        // Pass 1: Android's PdfRenderer.
        var androidOk = false
        try {
            descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            androidRenderer = PdfRenderer(descriptor!!)
            backend = Backend.ANDROID
            androidOk = true
        } catch (e: Throwable) {
            try { descriptor?.close() } catch (_: Throwable) {}
            descriptor = null
            androidRenderer = null
        }

        if (!androidOk) {
            // Pass 2: PDFBox-Android. Empty password first (handles view-only
            // protected PDFs, which are the bulk of "encrypted" PDFs out there).
            pdfBoxDoc = try {
                PDDocument.load(file, "")
            } catch (_: Throwable) {
                PDDocument.load(file)
            }
            pdfBoxRenderer = PdfBoxRenderer(pdfBoxDoc!!)
            backend = Backend.PDFBOX
        }
    }

    /**
     * Synchronized because callers render on ad-hoc background threads and BOTH
     * backends are single-threaded by contract: Android's PdfRenderer throws
     * IllegalStateException if a second page is opened while one is open, and
     * PDFBox's PDDocument corrupts state under concurrent renderImage calls.
     * Rapid page flips used to race here and produce blank pages.
     */
    @Synchronized
    fun renderPage(index: Int, width: Int, contrastBoost: Boolean = false, boldMode: Boolean = false): Bitmap? {
        if (closed) return null
        if (index < 0 || index >= pageCount) return null
        val bmp = when (backend) {
            Backend.ANDROID -> renderViaAndroid(index, width)
            Backend.PDFBOX -> renderViaPdfBox(index, width)
        } ?: return null
        if (contrastBoost || boldMode) boostContrast(bmp)
        return bmp
    }

    private fun renderViaAndroid(index: Int, width: Int): Bitmap? {
        val renderer = androidRenderer ?: return null
        // Cap at ~16 megapixels to dodge OOM on the Mudita.
        var w = width.coerceAtLeast(64)
        val page = renderer.openPage(index)
        var scale = w.toFloat() / page.width
        var h = (page.height * scale).toInt()
        val maxPixels = 16_000_000
        if (w.toLong() * h.toLong() > maxPixels) {
            val factor = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h.toLong()).toDouble()).toFloat()
            w = (w * factor).toInt().coerceAtLeast(64)
            h = (h * factor).toInt().coerceAtLeast(64)
            scale = w.toFloat() / page.width
        }
        val bitmap = try {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        } catch (oom: OutOfMemoryError) {
            page.close()
            return null
        }
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    private fun renderViaPdfBox(index: Int, width: Int): Bitmap? {
        val doc = pdfBoxDoc ?: return null
        val renderer = pdfBoxRenderer ?: return null
        return try {
            val page = doc.getPage(index)
            val pageW = page.mediaBox.width
            // PDFBox's renderImage(index, scale) where scale = output pixels / PDF points.
            // We want width pixels of output, so scale = width / pageW.
            val scale = (width.toFloat() / pageW).coerceAtLeast(0.25f)
            // Cap the scale so we don't allocate gigantic bitmaps.
            val safeScale = scale.coerceAtMost(4f)
            renderer.renderImage(index, safeScale)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Throwable) {
            null
        }
    }

    fun extractPageText(index: Int): String {
        // Android's renderer can't extract text. PDFBox can — useful for the
        // PDF→EPUB converter, but for plain rendering we don't need it.
        return try {
            val doc = pdfBoxDoc ?: return ""
            val stripper = com.tom_roush.pdfbox.text.PDFTextStripper()
            stripper.startPage = index + 1
            stripper.endPage = index + 1
            stripper.getText(doc)
        } catch (_: Throwable) { "" }
    }

    override fun close() {
        if (closed) return
        closed = true
        try { androidRenderer?.close() } catch (_: Throwable) {}
        try { descriptor?.close() } catch (_: Throwable) {}
        try { pdfBoxDoc?.close() } catch (_: Throwable) {}
        androidRenderer = null
        descriptor = null
        pdfBoxDoc = null
        pdfBoxRenderer = null
    }

    private fun boostContrast(bitmap: Bitmap) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        for (i in pixels.indices) {
            val c = pixels[i]
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val b = c and 0xFF
            val lum = (0.299 * r + 0.587 * g + 0.114 * b).toInt()
            val threshold = 180
            if (lum < threshold) {
                val factor = 0.6f
                val nr = (r * factor).toInt().coerceIn(0, 255)
                val ng = (g * factor).toInt().coerceIn(0, 255)
                val nb = (b * factor).toInt().coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (nr shl 16) or (ng shl 8) or nb
            }
        }
        bitmap.setPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }
}
