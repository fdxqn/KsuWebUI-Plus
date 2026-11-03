package io.github.a13e300.ksuwebui.shortcut

object ModuleIdValidator {
    private const val MAX_LENGTH = 128

    fun isValid(moduleId: String?): Boolean {
        if (moduleId == null || moduleId.isEmpty() || moduleId.length > MAX_LENGTH) return false
        if (moduleId.isBlank()) return false
        if (moduleId == "." || moduleId == ".." || moduleId.startsWith(".")) return false
        return moduleId.none { ch ->
            ch == '/' || ch == '\\' || ch == '\u0000' || Character.isISOControl(ch)
        }
    }
}
