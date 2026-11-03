package io.github.a13e300.ksuwebui.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.ByteArrayOutputStream

class ModuleIconLoader(private val fs: FileSystemManager) {
    private val cache = object : LruCache<String, Bitmap>(cacheSize()) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    @WorkerThread
    fun load(moduleId: String): Bitmap? {
        cache.get(moduleId)?.let { return it }

        return try {
            val webroot = fs.getFile("${ModuleRepository.MODULES_ROOT}/$moduleId/webroot")
            if (!webroot.isDirectory) return null
            for (name in ICON_NAMES) {
                val file = fs.getFile(webroot, name)
                if (!file.isFile) continue
                val bytes = readLimited(file) ?: continue
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) continue

                val options = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight)
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: continue
                cache.put(moduleId, bitmap)
                return bitmap
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    private fun readLimited(file: ExtendedFile): ByteArray? {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        var total = 0
        file.newInputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_BYTES) return null
                out.write(buffer, 0, read)
            }
        }
        return out.toByteArray()
    }

    private fun sampleSize(width: Int, height: Int): Int {
        var sample = 1
        while (width / sample > MAX_SIZE || height / sample > MAX_SIZE) {
            sample *= 2
        }
        return sample
    }

    companion object {
        private val ICON_NAMES = listOf("icon.png", "icon.webp", "icon.jpg")
        private const val MAX_SIZE = 1024
        private const val MAX_BYTES = 8 * 1024 * 1024

        private fun cacheSize(): Int {
            return (Runtime.getRuntime().maxMemory() / 8).toInt().coerceAtLeast(4 * 1024 * 1024)
        }
    }
}
