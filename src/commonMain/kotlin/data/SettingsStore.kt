package data

/**
 * Simple string/boolean key-value settings storage. Desktop delegates to `AppDatabase` (Exposed/
 * H2, unchanged); Android is an in-memory stub (non-persistent) until Phase 5's SQLDelight database.
 */
expect object SettingsStore {
    fun getSetting(key: String, default: String): String
    fun setSetting(key: String, value: String)
    fun getSettingBoolean(key: String, default: Boolean): Boolean
    fun setSettingBoolean(key: String, value: Boolean)
}
