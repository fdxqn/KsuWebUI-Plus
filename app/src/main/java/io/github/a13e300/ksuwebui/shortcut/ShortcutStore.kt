package io.github.a13e300.ksuwebui.shortcut

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.util.Log
import androidx.annotation.WorkerThread
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

class ShortcutStore private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val filesDir = appContext.filesDir
    private val configFile = File(filesDir, CONFIG_NAME)
    private val backupFile = File(filesDir, "$CONFIG_NAME.bak")
    private val iconDir = File(filesDir, ICON_DIR)
    private val sourceDir = File(filesDir, SOURCE_DIR)
    private val lock = ReentrantLock()
    private var cache: MutableMap<String, ShortcutConfig>? = null

    @WorkerThread
    fun load(): Map<String, ShortcutConfig> = lock.withLock {
        ensureLoaded()
        cache!!.toMap()
    }

    @WorkerThread
    fun get(shortcutId: String): ShortcutConfig? = lock.withLock {
        ensureLoaded()
        cache!![shortcutId]
    }

    @WorkerThread
    fun listForModule(moduleId: String): List<ShortcutConfig> = lock.withLock {
        ensureLoaded()
        cache!!.values.filter { it.moduleId == moduleId }
    }

    @WorkerThread
    fun upsert(config: ShortcutConfig): Boolean = lock.withLock {
        ensureLoaded()
        val next = cache!!.toMutableMap()
        next[config.shortcutId] = config
        if (!write(next)) return@withLock false
        cache = next
        true
    }

    @WorkerThread
    fun delete(shortcutId: String): Boolean = lock.withLock {
        ensureLoaded()
        val existing = cache!![shortcutId] ?: return@withLock true
        val next = cache!!.toMutableMap()
        next.remove(shortcutId)
        if (!write(next)) return@withLock false
        cache = next
        deleteFiles(existing)
        true
    }

    @WorkerThread
    fun copyImage(uri: Uri): String? {
        sourceDir.mkdirs()
        val type = appContext.contentResolver.getType(uri)
        val extension = when (type) {
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/jpeg", "image/jpg" -> "jpg"
            else -> "png"
        }
        val name = "${UUID.randomUUID()}.$extension"
        val tmp = File(sourceDir, "$name.tmp")
        val dest = File(sourceDir, name)
        return try {
            val input = appContext.contentResolver.openInputStream(uri) ?: return null
            input.use { source ->
                FileOutputStream(tmp).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_SOURCE_BYTES) {
                            tmp.delete()
                            return null
                        }
                        output.write(buffer, 0, read)
                    }
                    output.fd.sync()
                }
            }
            if (!tmp.renameTo(dest)) {
                tmp.delete()
                return null
            }
            name
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy image", e)
            tmp.delete()
            null
        }
    }

    @WorkerThread
    fun saveSourceBitmap(bitmap: Bitmap): String? {
        sourceDir.mkdirs()
        val name = "${UUID.randomUUID()}.png"
        val file = File(sourceDir, name)
        return try {
            FileOutputStream(file).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    file.delete()
                    return null
                }
                output.fd.sync()
            }
            name
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save cropped image", e)
            file.delete()
            null
        }
    }

    @WorkerThread
    fun sourceFile(name: String): File? {
        if (name.isEmpty() || name.contains('/') || name.contains('\\') || name == "." || name == "..") {
            return null
        }
        val file = File(sourceDir, name)
        return file.takeIf { it.isFile }
    }

    @WorkerThread
    fun saveIcon(config: ShortcutConfig, bitmap: Bitmap): File? {
        iconDir.mkdirs()
        val file = iconFile(config)
        val tmp = File(iconDir, "${file.name}.tmp")
        return try {
            FileOutputStream(tmp).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    tmp.delete()
                    return null
                }
                output.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                tmp.delete()
                return null
            }
            pruneIcons(config.shortcutId, file.name)
            file
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save icon", e)
            tmp.delete()
            null
        }
    }

    @WorkerThread
    fun loadIcon(config: ShortcutConfig): Bitmap? {
        val file = iconFile(config)
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load icon", e)
            null
        }
    }

    @WorkerThread
    fun deleteIcon(config: ShortcutConfig) {
        iconDir.listFiles()
            ?.filter { it.name.startsWith(iconPrefix(config.shortcutId)) }
            ?.forEach { it.delete() }
        val imageFile = config.icon.imageFile ?: return
        lock.withLock {
            ensureLoaded()
            val stillUsed = cache!!.values.any {
                it.shortcutId != config.shortcutId && it.icon.imageFile == imageFile
            }
            if (!stillUsed) sourceFile(imageFile)?.delete()
        }
    }

    private fun sameFile(left: ShortcutConfig, right: ShortcutConfig): Boolean =
        left.shortcutId == right.shortcutId

    private fun deleteFiles(config: ShortcutConfig) {
        iconDir.listFiles()
            ?.filter { it.name.startsWith(iconPrefix(config.shortcutId)) }
            ?.forEach { it.delete() }
        val imageFile = config.icon.imageFile ?: return
        val stillUsed = cache!!.values.any {
            !sameFile(it, config) && it.icon.imageFile == imageFile
        }
        if (!stillUsed) sourceFile(imageFile)?.delete()
    }

    private fun pruneIcons(shortcutId: String, keepName: String) {
        val prefix = iconPrefix(shortcutId)
        iconDir.listFiles()
            ?.filter { it.name.startsWith(prefix) && it.name != keepName }
            ?.forEach { it.delete() }
    }

    private fun iconFile(config: ShortcutConfig): File {
        return File(iconDir, "${iconPrefix(config.shortcutId)}${config.updatedAt}.png")
    }

    private fun iconPrefix(shortcutId: String): String = "${sha256(shortcutId).take(32)}-"

    private fun ensureLoaded() {
        if (cache != null) return
        val primary = parseFrom(configFile)
        cache = primary ?: parseFrom(backupFile) ?: mutableMapOf()
    }

    private fun parseFrom(file: File): MutableMap<String, ShortcutConfig>? {
        if (!file.isFile) return null
        return try {
            parse(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $file", e)
            null
        }
    }

    private fun parse(root: JSONObject): MutableMap<String, ShortcutConfig> {
        if (root.optInt("version", 0) > VERSION) {
            Log.w(TAG, "Unsupported config version ${root.optInt("version", 0)}")
            return mutableMapOf()
        }
        val shortcuts = root.optJSONObject("shortcuts") ?: return mutableMapOf()
        val result = mutableMapOf<String, ShortcutConfig>()
        shortcuts.keys().forEach { key ->
            val item = shortcuts.optJSONObject(key) ?: return@forEach
            try {
                result[key] = parseConfig(key, item)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse shortcut $key", e)
            }
        }
        return result
    }

    private fun parseConfig(key: String, item: JSONObject): ShortcutConfig {
        val iconObject = item.optJSONObject("icon") ?: JSONObject()
        val icon = IconSpec(
            source = runCatching {
                IconSource.valueOf(iconObject.optString("source", IconSource.FOLLOW_MODULE.name))
            }.getOrDefault(IconSource.FOLLOW_MODULE),
            imageFile = iconObject.optString("imageFile", "")
                .takeIf { it.isNotBlank() && it != "null" },
            background = parseColor(iconObject.optString("background", ""), 0xFF111827.toInt()),
            foreground = parseColor(iconObject.optString("foreground", ""), 0xFFFFFFFF.toInt()),
            text = iconObject.optString("text", ""),
            textScale = iconObject.optDouble("textScale", 0.8).toFloat().coerceIn(0.5f, 1f)
        )
        return ShortcutConfig(
            shortcutId = item.optString("shortcutId", key).ifBlank { key },
            moduleId = item.optString("moduleId", ""),
            slug = item.optString("slug", "default").ifBlank { "default" },
            label = item.optString("label", ""),
            labelMode = runCatching {
                LabelMode.valueOf(item.optString("labelMode", LabelMode.FOLLOW_MODULE.name))
            }.getOrDefault(LabelMode.FOLLOW_MODULE),
            icon = icon,
            pinned = item.optBoolean("pinned", false),
            createdAt = item.optLong("createdAt", 0L),
            updatedAt = item.optLong("updatedAt", 0L)
        )
    }

    private fun write(configs: Map<String, ShortcutConfig>): Boolean {
        val shortcuts = JSONObject()
        configs.values.forEach { config ->
            shortcuts.put(config.shortcutId, toJson(config))
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("shortcuts", shortcuts)
        val tmp = File(filesDir, "$CONFIG_NAME.tmp")
        return try {
            FileOutputStream(tmp).use { output ->
                output.write(root.toString(2).toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            if (configFile.isFile) {
                runCatching { configFile.copyTo(backupFile, overwrite = true) }
            }
            if (!tmp.renameTo(configFile)) {
                tmp.delete()
                false
            } else {
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write config", e)
            tmp.delete()
            false
        }
    }

    private fun toJson(config: ShortcutConfig): JSONObject {
        val icon = JSONObject()
            .put("source", config.icon.source.name)
            .put("imageFile", config.icon.imageFile ?: JSONObject.NULL)
            .put("background", colorToHex(config.icon.background))
            .put("foreground", colorToHex(config.icon.foreground))
            .put("text", config.icon.text)
            .put("textScale", config.icon.textScale.toDouble())
        return JSONObject()
            .put("shortcutId", config.shortcutId)
            .put("moduleId", config.moduleId)
            .put("slug", config.slug)
            .put("label", config.label)
            .put("labelMode", config.labelMode.name)
            .put("icon", icon)
            .put("pinned", config.pinned)
            .put("createdAt", config.createdAt)
            .put("updatedAt", config.updatedAt)
    }

    private fun parseColor(value: String, fallback: Int): Int {
        return runCatching { Color.parseColor(value) }.getOrDefault(fallback)
    }

    private fun colorToHex(color: Int): String {
        val value = color.toLong() and 0xFFFFFFFFL
        return "#" + value.toString(16).padStart(8, '0')
    }

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val v = byte.toInt() and 0xFF
                append(HEX[v ushr 4])
                append(HEX[v and 0x0F])
            }
        }
    }

    companion object {
        @Volatile
        private var instance: ShortcutStore? = null

        fun get(context: Context): ShortcutStore {
            return instance ?: synchronized(this) {
                instance ?: ShortcutStore(context.applicationContext).also { instance = it }
            }
        }

        private const val TAG = "ShortcutStore"
        private const val VERSION = 1
        private const val CONFIG_NAME = "shortcuts.json"
        private const val ICON_DIR = "shortcut_icons"
        private const val SOURCE_DIR = "shortcut_sources"
        private const val MAX_SOURCE_BYTES = 8L * 1024 * 1024
        private const val HEX = "0123456789abcdef"
    }
}
