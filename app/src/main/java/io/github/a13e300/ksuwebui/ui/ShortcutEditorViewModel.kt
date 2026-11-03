package io.github.a13e300.ksuwebui.ui

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import io.github.a13e300.ksuwebui.shortcut.IconSource
import io.github.a13e300.ksuwebui.shortcut.IconSpec
import io.github.a13e300.ksuwebui.shortcut.LabelMode
import io.github.a13e300.ksuwebui.shortcut.ShortcutConfig

class ShortcutEditorViewModel : ViewModel() {
    var moduleId: String = ""
    var moduleName: String = ""
    var existing: ShortcutConfig? = null
    var moduleIcon: Bitmap? = null
    var preview: Bitmap? = null

    var label: String = ""
    var labelMode: LabelMode = LabelMode.FOLLOW_MODULE
    var source: IconSource = IconSource.FOLLOW_MODULE
    var background: Int = 0xFF111827.toInt()
    var text: String = ""
    var imageFile: String? = null
    var loaded = false

    fun load(existing: ShortcutConfig?, moduleName: String) {
        if (loaded) return
        loaded = true
        this.existing = existing
        this.moduleName = moduleName
        if (existing != null) {
            label = existing.label
            labelMode = existing.labelMode
            source = existing.icon.source
            background = existing.icon.background
            text = existing.icon.text
            imageFile = existing.icon.imageFile
        } else if (label.isEmpty()) {
            label = moduleName
        }
    }

    fun buildConfig(now: Long): ShortcutConfig {
        val shortcutId = existing?.shortcutId ?: ShortcutConfig.idFor(moduleId)
        val finalLabel = if (labelMode == LabelMode.FOLLOW_MODULE) {
            moduleName
        } else {
            label.trim().take(20).ifEmpty { moduleName }
        }
        return ShortcutConfig(
            shortcutId = shortcutId,
            moduleId = moduleId,
            slug = existing?.slug ?: "default",
            label = finalLabel,
            labelMode = labelMode,
            icon = IconSpec(
                source = source,
                imageFile = imageFile,
                background = background,
                foreground = 0xFF000000.toInt(),
                text = text.take(4),
                textScale = 1f
            ),
            pinned = existing?.pinned ?: false,
            createdAt = existing?.createdAt ?: now,
            updatedAt = now
        )
    }
}
