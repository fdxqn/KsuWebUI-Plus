package io.github.a13e300.ksuwebui.ui

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.WindowManager
import androidx.core.view.isVisible
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.github.a13e300.ksuwebui.R
import io.github.a13e300.ksuwebui.data.ModuleInfo
import io.github.a13e300.ksuwebui.databinding.SheetModuleActionsBinding
import io.github.a13e300.ksuwebui.shortcut.ShortcutConfig
import io.github.a13e300.ksuwebui.shortcut.ShortcutIconRenderer
import kotlin.math.min

class ModuleActionsSheet(
    context: Context,
    private val module: ModuleInfo,
    moduleIcon: Bitmap?,
    private val shortcut: ShortcutConfig?,
    private val listener: Listener
) : Dialog(context) {

    interface Listener {
        fun onOpen(module: ModuleInfo)
        fun onAddShortcut(module: ModuleInfo)
        fun onEditShortcut(module: ModuleInfo, shortcut: ShortcutConfig)
        fun onRemoveShortcut(module: ModuleInfo, shortcut: ShortcutConfig)
    }

    init {
        val binding = SheetModuleActionsBinding.inflate(LayoutInflater.from(context))
        val card = MaterialCardView(context).apply {
            radius = dp(context, 28).toFloat()
            cardElevation = 0f
            strokeWidth = dp(context, 1)
            strokeColor = context.getColor(R.color.editor_field)
            setCardBackgroundColor(context.getColor(R.color.editor_card))
            clipToOutline = true
            addView(binding.root)
        }
        setContentView(card)

        val icon = moduleIcon ?: ShortcutIconRenderer().monogram(
            module.name,
            module.id,
            dp(context, 56)
        )
        binding.sheetIcon.setImageBitmap(icon)
        binding.sheetName.text = module.name
        binding.sheetDisabled.isVisible = !module.enabled

        binding.actionOpen.setOnClickListener {
            dismiss()
            listener.onOpen(module)
        }
        binding.actionAdd.isVisible = module.enabled && shortcut == null
        binding.actionEdit.isVisible = module.enabled && shortcut != null
        binding.actionRemove.isVisible = shortcut?.pinned == true

        binding.actionAdd.setOnClickListener {
            dismiss()
            listener.onAddShortcut(module)
        }
        binding.actionEdit.setOnClickListener {
            shortcut?.let {
                dismiss()
                listener.onEditShortcut(module, it)
            }
        }
        binding.actionRemove.setOnClickListener {
            shortcut?.let {
                dismiss()
                listener.onRemoveShortcut(module, it)
            }
        }
        binding.actionInfo.setOnClickListener {
            showInfo(context)
        }
        binding.actionCancel.setOnClickListener {
            dismiss()
        }

        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.2f)
        }
    }

    override fun onStart() {
        super.onStart()
        val width = min(
            context.resources.displayMetrics.widthPixels - dp(context, 32),
            dp(context, 480)
        )
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            val attrs = attributes
            attrs.y = dp(context, 24)
            attributes = attrs
        }
    }

    private fun showInfo(context: Context) {
        val message = context.getString(
            R.string.module_info_format,
            module.author,
            module.version,
            module.id,
            module.description
        )
        MaterialAlertDialogBuilder(context)
            .setTitle(module.name)
            .setMessage(message)
            .setPositiveButton(R.string.close, null)
            .show()
    }

    private fun dp(context: Context, value: Int): Int {
        return (value * context.resources.displayMetrics.density).toInt()
    }
}
