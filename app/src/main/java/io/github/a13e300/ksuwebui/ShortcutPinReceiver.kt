package io.github.a13e300.ksuwebui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.a13e300.ksuwebui.shortcut.ModuleShortcutController
import io.github.a13e300.ksuwebui.shortcut.ShortcutStore

class ShortcutPinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val shortcutId = intent.getStringExtra(ModuleShortcutController.EXTRA_SHORTCUT_ID) ?: return
        val pending = goAsync()
        App.executor.execute {
            try {
                val store = ShortcutStore.get(context)
                val config = store.get(shortcutId)
                if (config != null) {
                    store.upsert(config.copy(pinned = true))
                }
            } finally {
                pending.finish()
            }
        }
    }
}
