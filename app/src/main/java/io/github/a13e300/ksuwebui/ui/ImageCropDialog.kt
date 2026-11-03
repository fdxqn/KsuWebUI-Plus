package io.github.a13e300.ksuwebui.ui

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import io.github.a13e300.ksuwebui.R
import kotlin.math.min

class ImageCropDialog(
    context: Context,
    private val source: Bitmap,
    private val onCropped: (Bitmap) -> Unit
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(28).toFloat()
                setColor(context.getColor(R.color.editor_card))
            }
        }

        val title = TextView(context).apply {
            text = context.getString(R.string.image_crop_title)
            textSize = 20f
            setTextColor(context.getColor(R.color.editor_text))
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)

        val size = min(
            context.resources.displayMetrics.widthPixels - dp(72),
            dp(320)
        ).coerceAtLeast(dp(200))
        val cropView = CropImageView(context).apply {
            setBitmap(source)
        }
        root.addView(
            cropView,
            LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(16)
            }
        )

        val hint = TextView(context).apply {
            text = context.getString(R.string.image_crop_hint)
            textSize = 12f
            setTextColor(context.getColor(R.color.editor_label))
            gravity = Gravity.CENTER
            setPadding(0, dp(8), 0, dp(16))
        }
        root.addView(hint)

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END
        }
        val cancel = MaterialButton(context).apply {
            text = context.getString(R.string.image_crop_cancel)
            setTextColor(context.getColor(R.color.editor_label))
            cornerRadius = dp(24)
            backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
            setInsetTop(0)
            setInsetBottom(0)
            setOnClickListener { dismiss() }
        }
        val confirm = MaterialButton(context).apply {
            text = context.getString(R.string.image_crop_confirm)
            setTextColor(Color.WHITE)
            cornerRadius = dp(24)
            backgroundTintList = ColorStateList.valueOf(context.getColor(R.color.oneui_primary))
            setInsetTop(0)
            setInsetBottom(0)
            setOnClickListener {
                val cropped = cropView.crop(432)
                if (cropped != null) onCropped(cropped)
                dismiss()
            }
        }
        buttons.addView(
            cancel,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                dp(48)
            )
        )
        buttons.addView(
            confirm,
            LinearLayout.LayoutParams(0, dp(48), 1f).apply {
                marginStart = dp(8)
            }
        )
        root.addView(
            buttons,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )

        setContentView(root)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                context.resources.displayMetrics.widthPixels - dp(32),
                WindowManager.LayoutParams.WRAP_CONTENT
            )
        }
    }
}
