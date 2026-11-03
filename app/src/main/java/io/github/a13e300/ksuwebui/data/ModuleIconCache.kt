package io.github.a13e300.ksuwebui.data

import android.graphics.Bitmap

object ModuleIconCache {
    private val icons = HashMap<String, Bitmap>()

    @Synchronized
    fun get(moduleId: String): Bitmap? = icons[moduleId]

    @Synchronized
    fun put(moduleId: String, icon: Bitmap) {
        icons[moduleId] = icon
    }

    @Synchronized
    fun remove(moduleId: String) {
        icons.remove(moduleId)
    }
}
