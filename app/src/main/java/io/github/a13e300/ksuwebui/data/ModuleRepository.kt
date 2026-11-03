package io.github.a13e300.ksuwebui.data

import com.topjohnwu.superuser.nio.FileSystemManager
import io.github.a13e300.ksuwebui.shortcut.ModuleIdValidator
import java.io.IOException

sealed interface ModuleResolveResult {
    data class Success(val module: ModuleInfo, val webRoot: String) : ModuleResolveResult
    data object InvalidId : ModuleResolveResult
    data object NotFound : ModuleResolveResult
    data object Disabled : ModuleResolveResult
    data object NoWebUi : ModuleResolveResult
}

class ModuleRepository(private val fs: FileSystemManager) {
    fun listModules(includeDisabled: Boolean): List<ModuleInfo> {
        val files = fs.getFile(MODULES_ROOT).listFiles() ?: return emptyList()
        return files.mapNotNull { dir ->
            if (!dir.isDirectory) return@mapNotNull null
            if (!ModuleIdValidator.isValid(dir.name)) return@mapNotNull null
            if (!fs.getFile(dir, "webroot").isDirectory) return@mapNotNull null
            val enabled = !fs.getFile(dir, "disable").exists()
            if (!enabled && !includeDisabled) return@mapNotNull null
            readModule(dir.name, enabled)
        }
    }

    fun resolve(moduleId: String?, allowDisabled: Boolean = false): ModuleResolveResult {
        if (!ModuleIdValidator.isValid(moduleId)) return ModuleResolveResult.InvalidId
        val id = requireNotNull(moduleId)

        val root = canonicalOrNull(fs.getFile(MODULES_ROOT)) ?: return ModuleResolveResult.NotFound
        val dir = canonicalOrNull(fs.getFile("$MODULES_ROOT/$id")) ?: return ModuleResolveResult.NotFound
        if (dir.parentFile?.absolutePath != root.absolutePath) return ModuleResolveResult.InvalidId
        if (!dir.isDirectory) return ModuleResolveResult.NotFound

        val enabled = !fs.getFile(dir, "disable").exists()
        if (!enabled && !allowDisabled) return ModuleResolveResult.Disabled

        val webroot = fs.getFile(dir, "webroot")
        if (!fs.getFile(webroot, "index.html").exists()) return ModuleResolveResult.NoWebUi

        val module = readModule(id, enabled) ?: return ModuleResolveResult.NotFound
        return ModuleResolveResult.Success(module, webroot.absolutePath)
    }

    private fun canonicalOrNull(file: com.topjohnwu.superuser.nio.ExtendedFile): com.topjohnwu.superuser.nio.ExtendedFile? {
        return try {
            file.canonicalFile
        } catch (_: IOException) {
            null
        }
    }

    private fun readModule(id: String, enabled: Boolean): ModuleInfo? {
        val dir = fs.getFile("$MODULES_ROOT/$id")
        if (!dir.isDirectory) return null

        var name = id
        var description = ""
        var author = "?"
        var version = "?"
        val prop = fs.getFile(dir, "module.prop")
        if (prop.exists()) {
            try {
                prop.newInputStream().bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        val parts = line.split("=", limit = 2)
                        if (parts.size == 2) {
                            val value = parts[1].trimEnd('\r')
                            when (parts[0]) {
                                "name" -> name = value
                                "description" -> description = value
                                "author" -> author = value
                                "version" -> version = value
                            }
                        }
                    }
                }
            } catch (_: IOException) {
                // fall back to defaults
            }
        }

        return ModuleInfo(
            id = id,
            name = name,
            description = description,
            author = author,
            version = version,
            enabled = enabled,
            hasWebUi = true
        )
    }

    companion object {
        const val MODULES_ROOT = "/data/adb/modules"
    }
}
