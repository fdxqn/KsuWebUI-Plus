package io.github.a13e300.ksuwebui.shortcut

import java.security.MessageDigest

enum class LabelMode { FOLLOW_MODULE, CUSTOM }

enum class IconSource { FOLLOW_MODULE, IMAGE, SOLID }

data class IconSpec(
    val source: IconSource = IconSource.FOLLOW_MODULE,
    val imageFile: String? = null,
    val background: Int = 0xFF111827.toInt(),
    val foreground: Int = 0xFF000000.toInt(),
    val text: String = "",
    val textScale: Float = 1f
)

data class ShortcutConfig(
    val shortcutId: String,
    val moduleId: String,
    val slug: String = "default",
    val label: String,
    val labelMode: LabelMode = LabelMode.FOLLOW_MODULE,
    val icon: IconSpec = IconSpec(),
    val pinned: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    companion object {
        private const val HEX = "0123456789abcdef"

        fun idFor(moduleId: String, slug: String = "default"): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$moduleId\u0000$slug".toByteArray(Charsets.UTF_8))
            val hex = buildString(digest.size * 2) {
                digest.forEach { byte ->
                    val value = byte.toInt() and 0xFF
                    append(HEX[value ushr 4])
                    append(HEX[value and 0x0F])
                }
            }
            return "module:" + hex.take(32)
        }
    }
}
