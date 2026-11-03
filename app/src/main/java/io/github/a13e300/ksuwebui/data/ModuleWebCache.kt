package io.github.a13e300.ksuwebui.data

import android.content.Context
import androidx.annotation.WorkerThread
import com.topjohnwu.superuser.nio.ExtendedFile
import com.topjohnwu.superuser.nio.FileSystemManager
import java.io.File
import java.security.MessageDigest

object ModuleWebCache {
    private const val CACHE_DIR = "webroot_cache"
    private const val MAX_FILES = 512
    private const val MAX_BYTES = 64L * 1024 * 1024

    fun webroot(context: Context, moduleId: String): File? {
        val dir = cacheDir(context, moduleId)
        if (!File(dir, ".complete").isFile) return null
        if (!File(dir, "index.html").isFile) return null
        return dir
    }

    @WorkerThread
    fun copy(context: Context, fs: FileSystemManager, moduleId: String): Boolean {
        val source = fs.getFile("${ModuleRepository.MODULES_ROOT}/$moduleId/webroot")
        if (!source.isDirectory) return false

        val target = cacheDir(context, moduleId)
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.deleteRecursively()
        if (!tmp.mkdirs()) return false

        var files = 0
        var bytes = 0L
        fun copyDir(src: ExtendedFile, dst: File): Boolean {
            val children = src.listFiles() ?: return false
            for (child in children) {
                if (child.isSymlink) continue
                if (child.isDirectory) {
                    val sub = File(dst, child.name)
                    if (!sub.mkdirs() && !sub.isDirectory) return false
                    if (!copyDir(child, sub)) return false
                } else if (child.isFile) {
                    files++
                    if (files > MAX_FILES) return false
                    val out = File(dst, child.name)
                    child.newInputStream().use { input ->
                        out.outputStream().use { output ->
                            val buffer = ByteArray(16 * 1024)
                            while (true) {
                                val read = input.read(buffer)
                                if (read < 0) break
                                bytes += read
                                if (bytes > MAX_BYTES) return false
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                }
            }
            return true
        }

        if (!copyDir(source, tmp)) {
            tmp.deleteRecursively()
            return false
        }
        File(tmp, ".complete").writeText(moduleId)
        target.deleteRecursively()
        if (!tmp.renameTo(target)) {
            tmp.deleteRecursively()
            return false
        }
        return true
    }

    fun delete(context: Context, moduleId: String) {
        cacheDir(context, moduleId).deleteRecursively()
    }

    private fun cacheDir(context: Context, moduleId: String): File {
        val name = sha256(moduleId).take(32)
        return File(File(context.filesDir, CACHE_DIR), name)
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

    private const val HEX = "0123456789abcdef"
}
