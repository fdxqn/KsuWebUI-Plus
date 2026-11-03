package io.github.a13e300.ksuwebui.ui

import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import io.github.a13e300.ksuwebui.BuildConfig
import io.github.a13e300.ksuwebui.R
import io.github.a13e300.ksuwebui.databinding.SheetSettingsBinding
import kotlin.math.min

class MainSettingsSheet(
    context: Context,
    private val prefs: SharedPreferences,
    private val onConfigChanged: () -> Unit
) : Dialog(context) {

    init {
        val binding = SheetSettingsBinding.inflate(LayoutInflater.from(context))
        val card = MaterialCardView(context).apply {
            radius = dp(28).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = context.getColor(R.color.editor_field)
            setCardBackgroundColor(context.getColor(R.color.editor_card))
            clipToOutline = true
            addView(binding.root)
        }
        setContentView(card)

        binding.settingsAppName.text = context.getString(R.string.app_name)
        binding.settingsVersion.text = context.getString(R.string.settings_version, BuildConfig.VERSION_NAME)

        binding.showDisabledSwitch.isChecked = prefs.getBoolean("show_disabled", false)
        binding.showDisabledSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_disabled", checked).apply()
            onConfigChanged()
        }
        binding.showDisabledRow.setOnClickListener {
            binding.showDisabledSwitch.toggle()
        }

        binding.debugRow.isVisible = BuildConfig.DEBUG
        binding.debugSwitch.isChecked = prefs.getBoolean("enable_web_debugging", true)
        binding.debugSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("enable_web_debugging", checked).apply()
        }
        binding.debugRow.setOnClickListener {
            binding.debugSwitch.toggle()
        }

        val openShortcuts = View.OnClickListener {
            dismiss()
            context.startActivity(Intent(context, ShortcutListActivity::class.java))
        }
        binding.shortcutsRow.isClickable = true
        binding.shortcutsRow.setOnClickListener(openShortcuts)
        for (i in 0 until binding.shortcutsRow.childCount) {
            binding.shortcutsRow.getChildAt(i).setOnClickListener(openShortcuts)
        }

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.2f)
        }
    }

    override fun onStart() {
        super.onStart()
        val width = min(
            context.resources.displayMetrics.widthPixels - dp(32),
            dp(360)
        )
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.TOP or Gravity.END)
            val attrs = attributes
            attrs.x = dp(16)
            attrs.y = dp(72)
            attributes = attrs
        }
    }

    private fun dp(value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
