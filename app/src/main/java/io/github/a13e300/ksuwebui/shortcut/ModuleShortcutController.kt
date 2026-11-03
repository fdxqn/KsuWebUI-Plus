package io.github.a13e300.ksuwebui.shortcut

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import io.github.a13e300.ksuwebui.R
import io.github.a13e300.ksuwebui.ShortcutPinReceiver

sealed interface PinResult {
    data object Requested : PinResult
    data object Restored : PinResult
    data object Unsupported : PinResult
    data class Failed(val message: String) : PinResult
}

class ModuleShortcutController(private val context: Context) {
    fun isPinSupported(): Boolean {
        return ShortcutManagerCompat.isRequestPinShortcutSupported(context)
    }

    fun pin(config: ShortcutConfig, icon: Bitmap): PinResult {
        if (!isPinSupported()) return PinResult.Unsupported
        return try {
            val manager = context.getSystemService(ShortcutManager::class.java)
            val existing = manager?.pinnedShortcuts?.firstOrNull { it.id == config.shortcutId }
            if (existing != null) {
                if (!existing.isEnabled) {
                    manager?.enableShortcuts(listOf(config.shortcutId))
                }
                ShortcutManagerCompat.updateShortcuts(context, listOf(buildInfo(config, icon)))
                return PinResult.Restored
            }

            val callback = PendingIntent.getBroadcast(
                context,
                config.shortcutId.hashCode(),
                Intent(context, ShortcutPinReceiver::class.java)
                    .putExtra(EXTRA_SHORTCUT_ID, config.shortcutId),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            ).intentSender
            val requested = ShortcutManagerCompat.requestPinShortcut(
                context,
                buildInfo(config, icon),
                callback
            )
            if (requested) PinResult.Requested else PinResult.Failed(
                context.getString(R.string.shortcut_pin_failed_generic)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pin shortcut", e)
            PinResult.Failed(context.getString(R.string.shortcut_pin_failed_generic))
        }
    }

    fun update(config: ShortcutConfig, icon: Bitmap): Boolean {
        return try {
            ShortcutManagerCompat.updateShortcuts(context, listOf(buildInfo(config, icon)))
        } catch (_: Exception) {
            false
        }
    }

    fun disable(shortcutId: String, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                context.getSystemService(ShortcutManager::class.java)
                    ?.removeLongLivedShortcuts(listOf(shortcutId))
            } catch (_: Exception) {
                // fall through to disable
            }
        }
        try {
            ShortcutManagerCompat.disableShortcuts(context, listOf(shortcutId), message)
        } catch (_: Exception) {
            // nothing else to do
        }
    }

    fun pinnedIds(): Set<String> {
        return try {
            context.getSystemService(ShortcutManager::class.java)
                ?.pinnedShortcuts
                ?.filter { it.isEnabled }
                ?.map { it.id }
                ?.toSet()
                ?: emptySet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun buildInfo(config: ShortcutConfig, icon: Bitmap): ShortcutInfoCompat {
        val intent = ModuleLaunchContract.shortcutIntent(context, config.moduleId)
        val builder = ShortcutInfoCompat.Builder(context, config.shortcutId)
            .setShortLabel(config.label.take(12))
            .setLongLabel(config.label)
            .setIcon(IconCompat.createWithAdaptiveBitmap(ShortcutIconRenderer.adaptiveArtwork(icon)))
            .setIntent(intent)
            .setRank(0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setLongLived(true)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "ModuleShortcut"
        const val EXTRA_SHORTCUT_ID = "shortcut_id"
    }
}
