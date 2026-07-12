package data

import androidapp.ZArchiveApplication
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import data.db.ZArchiveDatabase

/** Singleton wrapping the Android-only SQLDelight database — see `AndroidSearchListRepo`,
 * `AndroidSearchResultRepo`, `SettingsStore.android.kt`, `CfThrottleStore.android.kt`. */
object AndroidDatabase {
    val instance: ZArchiveDatabase by lazy {
        val driver = AndroidSqliteDriver(ZArchiveDatabase.Schema, ZArchiveApplication.appContext, "zarchive.db")
        ZArchiveDatabase(driver)
    }
}
