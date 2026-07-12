package data

actual object SettingsStore {
    private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries

    actual fun getSetting(key: String, default: String): String =
        queries.getSetting(key).executeAsOneOrNull() ?: default

    actual fun setSetting(key: String, value: String) {
        queries.setSetting(key, value)
    }

    actual fun getSettingBoolean(key: String, default: Boolean): Boolean =
        queries.getSetting(key).executeAsOneOrNull()?.toBooleanStrictOrNull() ?: default

    actual fun setSettingBoolean(key: String, value: Boolean) {
        queries.setSetting(key, value.toString())
    }
}
