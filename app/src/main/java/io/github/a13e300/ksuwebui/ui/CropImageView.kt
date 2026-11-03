package io.github.a13e300.ksuwebui.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max

class CropImageView(context: Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var baseScale = 1f
    private var userScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val next = (userScale * detector.scaleFactor).coerceIn(1f, 5f)
                val factor = next / userScale
                offsetX = detector.focusX - (detector.focusX - offsetX) * factor
                offsetY = detector.focusY - (detector.focusY - offsetY) * factor
                userScale = next
                clamp()
                invalidate()
                return true
            }
        }
    )

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float
            ): Boolean {
                offsetX -= distanceX
                offsetY -= distanceY
                clamp()
                invalidate()
                return true
            }
        }
    )

    init {
        isClickable = true
    }

    fun setBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        reset()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        reset()
    }

    private fun reset() {
        val b = bitmap ?: return
        if (width == 0 || height == 0) return
        baseScale = max(width.toFloat() / b.width, height.toFloat() / b.height)
        userScale = 1f
        offsetX = (width - b.width * baseScale) / 2f
        offsetY = (height - b.height * baseScale) / 2f
        clamp()
    }

    private fun clamp() {
        val b = bitmap ?: return
        val scale = baseScale * userScale
        val scaledWidth = b.width * scale
        val scaledHeight = b.height * scale
        offsetX = if (scaledWidth <= width) {
            (width - scaledWidth) / 2f
        } else {
            offsetX.coerceIn(width - scaledWidth, 0f)
        }
        offsetY = if (scaledHeight <= height) {
            (height - scaledHeight) / 2f
        } else {
            offsetY.coerceIn(height - scaledHeight, 0f)
        }
    }

    private fun imageMatrix(): Matrix {
        return Matrix().apply {
            setScale(baseScale * userScale, baseScale * userScale)
            postTranslate(offsetX, offsetY)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        val radius = 28f * resources.displayMetrics.density
        val path = Path().apply {
            addRoundRect(
                0f,
                0f,
                width.toFloat(),
                height.toFloat(),
                radius,
                radius,
                Path.Direction.CW
            )
        }
        canvas.save()
        canvas.clipPath(path)
        canvas.drawBitmap(b, imageMatrix(), paint)
        canvas.restore()

        val borderWidth = resources.displayMetrics.density * 1.2f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = borderWidth
            color = 0x73000000
        }
        val inset = borderWidth / 2f
        canvas.drawRoundRect(
            inset,
            inset,
            width - inset,
            height - inset,
            radius,
            radius,
            border
        )
    }

    fun crop(sizePx: Int): Bitmap? {
        val b = bitmap ?: return null
        if (width <= 0 || height <= 0) return null
        val output = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val scale = sizePx.toFloat() / width
        canvas.scale(scale, scale)
        canvas.drawBitmap(b, imageMatrix(), paint)
        return output
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) {
            gestureDetector.onTouchEvent(event)
        }
        return true
    }
}
