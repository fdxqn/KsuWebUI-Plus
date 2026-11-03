package io.github.a13e300.ksuwebui.shortcut

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.github.a13e300.ksuwebui.WebUIActivity

object ModuleLaunchContract {
    const val EXTRA_MODULE_ID = "module_id"
    const val EXTRA_FROM_SHORTCUT = "from_shortcut"

    fun documentUri(moduleId: String): Uri {
        return Uri.Builder()
            .scheme("ksuwebui")
            .authority("module")
            .appendPath(moduleId)
            .build()
    }

    fun webUiIntent(context: Context, moduleId: String, fromShortcut: Boolean): Intent {
        return Intent(context, WebUIActivity::class.java)
            .putExtra(EXTRA_MODULE_ID, moduleId)
            .putExtra(EXTRA_FROM_SHORTCUT, fromShortcut)
    }

    fun shortcutIntent(context: Context, moduleId: String): Intent {
        return webUiIntent(context, moduleId, true)
            .setAction(Intent.ACTION_VIEW)
            .setData(documentUri(moduleId))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
    }
}
