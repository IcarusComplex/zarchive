package data

// Temporary in-memory stub (non-persistent) until Phase 5's SQLDelight database.
actual object SettingsStore {
    private val values = mutableMapOf<String, String>()

    actual fun getSetting(key: String, default: String): String = values[key] ?: default
    actual fun setSetting(key: String, value: String) { values[key] = value }
    actual fun getSettingBoolean(key: String, default: Boolean): Boolean =
        values[key]?.toBooleanStrictOrNull() ?: default
    actual fun setSettingBoolean(key: String, value: Boolean) { values[key] = value.toString() }
}
