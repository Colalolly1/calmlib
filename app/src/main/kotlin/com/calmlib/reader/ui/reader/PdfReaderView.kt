package com.calmlib.reader.ui.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.calmlib.reader.engine.PdfEngine
import kotlin.math.max

class PdfReaderView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private var engine: PdfEngine? = null
    private var currentBitmap: Bitmap? = null
    private var bitmapBaseWidth: Int = 0      // width the bitmap was rendered for (display px)
    private var _currentPage: Int = 0
    private var contrastBoost: Boolean = false
    private var boldMode: Boolean = false
    var onPageChanged: ((page: Int, total: Int) -> Unit)? = null
    var onTapZone: ((zone: TapZone) -> Unit)? = null

    enum class TapZone { LEFT, RIGHT, CENTER }

    val currentPage: Int get() = _currentPage
    val totalPages: Int get() = engine?.pageCount ?: 0

    private var scale: Float = 1f
    private val minScale: Float = 1f
    private val maxScale: Float = 4f
    private var translateX: Float = 0f
    private var translateY: Float = 0f
    private var lastTouchX: Float = 0f
    private var lastTouchY: Float = 0f
    private var isScaling: Boolean = false
    private var rerenderJob: Runnable? = null

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            return true
        }
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val newScale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val factor = newScale / scale
            scale = newScale
            // Zoom around focal point so it stays under the user's fingers
            translateX = detector.focusX - (detector.focusX - translateX) * factor
            translateY = detector.focusY - (detector.focusY - translateY) * factor
            clampTranslate()
            invalidate()
            return true
        }
        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
            scheduleRerender()
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val w = width.toFloat()
            // When zoomed, left/right taps would conflict with panning so we suppress
            // them, but the CENTER tap always opens the controls so the user can
            // change text settings or reset zoom.
            val zone = when {
                e.x < w * 0.40f -> TapZone.LEFT
                e.x > w * 0.60f -> TapZone.RIGHT
                else -> TapZone.CENTER
            }
            if (scale > 1.05f && zone != TapZone.CENTER) return false
            onTapZone?.invoke(zone)
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.05f) {
                scale = 1f
                translateX = 0f
                translateY = 0f
            } else {
                scale = 2f
                // Zoom so the tap point stays under the finger
                translateX = -(e.x * (scale - 1f))
                translateY = -(e.y * (scale - 1f))
                clampTranslate()
            }
            invalidate()
            scheduleRerender()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        setBackgroundColor(Color.WHITE)
    }

    fun loadPdf(pdfEngine: PdfEngine) {
        engine = pdfEngine
        _currentPage = 0
        resetZoom()
        renderCurrent()
    }

    fun goToPage(page: Int) {
        val eng = engine ?: return
        _currentPage = page.coerceIn(0, eng.pageCount - 1)
        resetZoom()
        renderCurrent()
    }

    fun nextPage(): Boolean {
        val eng = engine ?: return false
        if (_currentPage < eng.pageCount - 1) {
            _currentPage++
            resetZoom()
            renderCurrent()
            return true
        }
        return false
    }

    fun prevPage(): Boolean {
        if (_currentPage > 0) {
            _currentPage--
            resetZoom()
            renderCurrent()
            return true
        }
        return false
    }

    fun setDisplayMode(contrast: Boolean, bold: Boolean) {
        contrastBoost = contrast
        boldMode = bold
        renderCurrent()
    }

    fun resetZoom() {
        scale = 1f
        translateX = 0f
        translateY = 0f
    }

    private fun bitmapDisplayWidth(): Float = (currentBitmap?.width?.toFloat() ?: width.toFloat()) * scaleVsBaseRender()

    private fun scaleVsBaseRender(): Float {
        // The bitmap was rendered for `bitmapBaseWidth` view-pixels. If view is the same size,
        // we draw the bitmap as-is at 1.0 scale (before the user's zoom).
        val view = if (width > 0) width else 800
        val base = if (bitmapBaseWidth > 0) bitmapBaseWidth else view
        return view.toFloat() / base.toFloat() * scale
    }

    private fun clampTranslate() {
        val bmp = currentBitmap ?: return
        val drawW = bmp.width * scaleVsBaseRender()
        val drawH = bmp.height * scaleVsBaseRender()
        val maxOffsetX = max(0f, drawW - width)
        val maxOffsetY = max(0f, drawH - height)
        translateX = translateX.coerceIn(-maxOffsetX, 0f)
        translateY = translateY.coerceIn(-maxOffsetY, 0f)
    }

    private fun scheduleRerender() {
        rerenderJob?.let { removeCallbacks(it) }
        if (scale <= 1.05f) return  // no need for hi-res render at base zoom
        rerenderJob = Runnable { renderCurrent() }
        postDelayed(rerenderJob!!, 200)
    }

    @Volatile private var renderToken: Long = 0

    private fun renderCurrent() {
        val baseW = if (width > 0) width else 800
        val targetW = if (scale > 1.05f && !isScaling) {
            (baseW * scale).toInt().coerceAtMost(baseW * 3)
        } else baseW

        val engine = this.engine ?: return
        val page = _currentPage
        val contrast = contrastBoost
        val bold = boldMode

        onPageChanged?.invoke(page, totalPages)

        // Render on a background thread — PDFBox fallback is slow (1–3 s/page) and
        // would freeze the UI thread otherwise. Token guards against piling up renders
        // when the user flips pages quickly: only the most recent token wins.
        val token = ++renderToken
        kotlin.concurrent.thread(name = "PdfRender", isDaemon = true) {
            val bmp = try {
                engine.renderPage(page, targetW, contrast, bold)
            } catch (_: Throwable) { null }
            post {
                if (token == renderToken) {
                    currentBitmap = bmp
                    bitmapBaseWidth = targetW
                    invalidate()
                }
            }
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (engine != null) renderCurrent()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)

        if (!isScaling && scale > 1.05f) {
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastTouchX = event.x
                    lastTouchY = event.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - lastTouchX
                    val dy = event.y - lastTouchY
                    translateX += dx
                    translateY += dy
                    clampTranslate()
                    lastTouchX = event.x
                    lastTouchY = event.y
                    invalidate()
                }
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bmp = currentBitmap ?: return
        val drawScale = scaleVsBaseRender()
        if (scale <= 1.0001f && drawScale in 0.999f..1.001f) {
            val left = (width - bmp.width) / 2f
            canvas.drawBitmap(bmp, left, 0f, null)
        } else {
            canvas.save()
            canvas.translate(translateX, translateY)
            canvas.scale(drawScale, drawScale)
            canvas.drawBitmap(bmp, 0f, 0f, null)
            canvas.restore()
        }
    }
}
