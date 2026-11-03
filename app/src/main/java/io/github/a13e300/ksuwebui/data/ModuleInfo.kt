package io.github.a13e300.ksuwebui.data

data class ModuleInfo(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val enabled: Boolean,
    val hasWebUi: Boolean
)
