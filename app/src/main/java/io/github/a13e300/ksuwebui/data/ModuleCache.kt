package io.github.a13e300.ksuwebui.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

object ModuleCache {
    private const val FILE_NAME = "module_cache.json"
    private const val VERSION = 1

    fun read(context: Context): List<ModuleInfo> {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.isFile) return emptyList()
        return try {
            val root = JSONObject(file.readText())
            if (root.optInt("version", 0) != VERSION) return emptyList()
            val modules = root.optJSONArray("modules") ?: return emptyList()
            buildList {
                for (i in 0 until modules.length()) {
                    val item = modules.optJSONObject(i) ?: continue
                    val id = item.optString("id", "")
                    if (id.isEmpty()) continue
                    add(
                        ModuleInfo(
                            id = id,
                            name = item.optString("name", id),
                            description = item.optString("description", ""),
                            author = item.optString("author", "?"),
                            version = item.optString("version", "?"),
                            enabled = item.optBoolean("enabled", true),
                            hasWebUi = item.optBoolean("hasWebUi", true)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun write(context: Context, modules: List<ModuleInfo>) {
        val array = JSONArray()
        modules.forEach { module ->
            array.put(
                JSONObject()
                    .put("id", module.id)
                    .put("name", module.name)
                    .put("description", module.description)
                    .put("author", module.author)
                    .put("version", module.version)
                    .put("enabled", module.enabled)
                    .put("hasWebUi", module.hasWebUi)
            )
        }
        val root = JSONObject()
            .put("version", VERSION)
            .put("modules", array)
        val file = File(context.filesDir, FILE_NAME)
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        try {
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        } catch (_: Exception) {
            tmp.delete()
        }
    }
}
