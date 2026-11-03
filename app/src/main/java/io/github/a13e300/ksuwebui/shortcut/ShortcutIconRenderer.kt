package io.github.a13e300.ksuwebui.shortcut

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import java.security.MessageDigest
import java.text.BreakIterator
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ShortcutIconRenderer {
    fun render(config: ShortcutConfig, moduleIcon: Bitmap?, sourceImage: Bitmap?): Bitmap {
        val bitmap = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val icon = config.icon
        val fallbackColor = hashColor(config.moduleId)

        if (icon.source == IconSource.SOLID) {
            canvas.drawColor(icon.background)
        } else {
            canvas.drawColor(fallbackColor)
            val image = if (icon.source == IconSource.IMAGE) sourceImage else moduleIcon
            if (image != null) drawCover(canvas, image)
        }

        val text = when {
            icon.text.isNotEmpty() -> takeGraphemes(icon.text, 4)
            icon.source == IconSource.FOLLOW_MODULE && moduleIcon == null ->
                firstGrapheme(config.label)
            icon.source == IconSource.SOLID -> firstGrapheme(config.label)
            icon.source == IconSource.IMAGE && sourceImage == null ->
                firstGrapheme(config.label)
            else -> ""
        }
        if (text.isEmpty()) return bitmap

        val background = when {
            icon.source == IconSource.SOLID -> icon.background
            icon.source == IconSource.FOLLOW_MODULE && moduleIcon == null -> fallbackColor
            else -> null
        }
        val textColor = background?.let { contrastColor(it, icon.foreground) } ?: icon.foreground
        val shadowColor = when {
            background != null -> Color.argb(160, 0, 0, 0)
            luminance(textColor) < 0.5 -> Color.argb(150, 255, 255, 255)
            else -> Color.argb(150, 0, 0, 0)
        }
        drawCenteredText(
            canvas = canvas,
            text = text,
            color = textColor,
            centerX = ICON_SIZE / 2f,
            centerY = ICON_SIZE / 2f,
            maxWidth = SAFE_SIZE * 0.8f * icon.textScale,
            maxTextSize = 96f * icon.textScale,
            minTextSize = 24f * icon.textScale,
            shadowColor = shadowColor
        )
        return bitmap
    }

    fun renderPreview(
        config: ShortcutConfig,
        moduleIcon: Bitmap?,
        sourceImage: Bitmap?,
        sizePx: Int
    ): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        return Bitmap.createScaledBitmap(render(config, moduleIcon, sourceImage), size, size, true)
    }

    fun monogram(label: String, moduleId: String, sizePx: Int = ICON_SIZE): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val background = hashColor(moduleId)
        canvas.drawColor(background)
        val text = firstGrapheme(label).uppercase()
        if (text.isEmpty()) return bitmap
        drawCenteredText(
            canvas = canvas,
            text = text,
            color = contrastColor(background, Color.WHITE),
            centerX = size / 2f,
            centerY = size / 2f,
            maxWidth = size * 0.5f,
            maxTextSize = size * 0.6f,
            minTextSize = size * 0.2f,
            shadowColor = null
        )
        return bitmap
    }

    private fun drawCenteredText(
        canvas: Canvas,
        text: String,
        color: Int,
        centerX: Float,
        centerY: Float,
        maxWidth: Float,
        maxTextSize: Float,
        minTextSize: Float,
        shadowColor: Int?
    ) {
        if (text.isEmpty() || maxWidth <= 0f) return
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            this.color = color
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            if (shadowColor != null) {
                setShadowLayer(5f, 0f, 1f, shadowColor)
            }
        }

        var low = minTextSize.coerceAtLeast(1f)
        var high = maxTextSize.coerceAtLeast(low)
        repeat(18) {
            val mid = (low + high) / 2f
            paint.textSize = mid
            if (paint.measureText(text) <= maxWidth) low = mid else high = mid
        }
        paint.textSize = low

        val metrics = paint.fontMetrics
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, centerX, baseline, paint)
    }

    private fun drawCover(canvas: Canvas, bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val scale = max(
            ICON_SIZE.toFloat() / bitmap.width,
            ICON_SIZE.toFloat() / bitmap.height
        )
        val width = bitmap.width * scale
        val height = bitmap.height * scale
        val left = (ICON_SIZE - width) / 2f
        val top = (ICON_SIZE - height) / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(
            bitmap,
            Rect(0, 0, bitmap.width, bitmap.height),
            RectF(left, top, left + width, top + height),
            paint
        )
    }

    private fun hashColor(moduleId: String): Int {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(moduleId.toByteArray(Charsets.UTF_8))
        val hueBits = ((digest[0].toInt() and 0xFF) shl 8) or (digest[1].toInt() and 0xFF)
        val hue = hueBits * 360f / 65535f
        return Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.75f))
    }

    private fun contrastColor(background: Int, preferred: Int): Int {
        if (contrastRatio(background, preferred) >= 4.5) return preferred
        val black = contrastRatio(background, Color.BLACK)
        val white = contrastRatio(background, Color.WHITE)
        return if (black >= white) Color.BLACK else Color.WHITE
    }

    private fun contrastRatio(left: Int, right: Int): Double {
        val l1 = luminance(left)
        val l2 = luminance(right)
        return (max(l1, l2) + 0.05) / (min(l1, l2) + 0.05)
    }

    private fun luminance(color: Int): Double {
        fun channel(value: Int): Double {
            val s = value / 255.0
            return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
        }
        return 0.2126 * channel(Color.red(color)) +
            0.7152 * channel(Color.green(color)) +
            0.0722 * channel(Color.blue(color))
    }

    private fun firstGrapheme(text: String): String = takeGraphemes(text, 1)

    private fun takeGraphemes(text: String, maxCount: Int): String {
        if (text.isEmpty() || maxCount <= 0) return ""
        val iterator = BreakIterator.getCharacterInstance()
        iterator.setText(text)
        var end = iterator.first()
        var count = 0
        while (end != BreakIterator.DONE && count < maxCount) {
            end = iterator.next()
            count++
        }
        return if (end == BreakIterator.DONE) text else text.substring(0, end)
    }

    companion object {
        const val ICON_SIZE = 432
        const val SAFE_SIZE = 264

        /**
         * The adaptive-icon canvas is 108dp; launchers display its central 72dp and mask it,
         * so artwork authored edge to edge would be cropped by exactly this fraction.
         */
        const val LAUNCHER_VISIBLE_FRACTION = 72f / 108f

        /**
         * Composes an authored square icon so a launcher's masked 72/108 window shows it 1:1.
         * The ring outside that window is bleed and never visible.
         */
        fun adaptiveArtwork(artwork: Bitmap): Bitmap {
            val out = Bitmap.createBitmap(ICON_SIZE, ICON_SIZE, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(
                artwork,
                null,
                RectF(0f, 0f, ICON_SIZE.toFloat(), ICON_SIZE.toFloat()),
                paint
            )
            val visible = ICON_SIZE * LAUNCHER_VISIBLE_FRACTION
            val left = (ICON_SIZE - visible) / 2f
            canvas.drawBitmap(
                artwork,
                null,
                RectF(left, left, left + visible, left + visible),
                paint
            )
            return out
        }
    }
}
