package data

actual object SettingsStore {
    actual fun getSetting(key: String, default: String): String = AppDatabase.getSetting(key, default)
    actual fun setSetting(key: String, value: String) = AppDatabase.setSetting(key, value)
    actual fun getSettingBoolean(key: String, default: Boolean): Boolean = AppDatabase.getSettingBoolean(key, default)
    actual fun setSettingBoolean(key: String, value: Boolean) = AppDatabase.setSettingBoolean(key, value)
}
