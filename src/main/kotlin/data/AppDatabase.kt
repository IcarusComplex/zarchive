package data

import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File

// ── Table definitions ──────────────────────────────────────────────────────────

object Settings : Table("settings") {
    val key   = text("key")
    val value = text("value")
    override val primaryKey = PrimaryKey(key)
}

object SearchLists : Table("search_lists") {
    val id        = integer("id").autoIncrement()
    val name      = text("name")
    val createdAt = long("created_at")
    val updatedAt = long("updated_at")
    override val primaryKey = PrimaryKey(id)
}

// Plain integer FK — cascade handled in code.
object SearchListCards : Table("search_list_cards") {
    val listId   = integer("list_id")
    val position = integer("position")
    val cardName = text("card_name")
    override val primaryKey = PrimaryKey(listId, position)
}

// ── Singleton ──────────────────────────────────────────────────────────────────

object AppDatabase {
    private val dbFile = File(System.getProperty("user.home"), ".zarchive/zarchive")

    fun init() {
        dbFile.parentFile.mkdirs()
        Database.connect(
            "jdbc:h2:file:${dbFile.absolutePath};DB_CLOSE_DELAY=-1;AUTO_SERVER=TRUE",
            driver = "org.h2.Driver",
        )
        transaction {
            SchemaUtils.createMissingTablesAndColumns(Settings, SearchLists, SearchListCards)
        }
        migrateFromPrefs()
    }

    // ── Settings ───────────────────────────────────────────────────────────────

    fun getSetting(key: String, default: String): String = transaction {
        Settings.selectAll().where { Settings.key eq key }
            .firstOrNull()?.get(Settings.value) ?: default
    }

    fun getSettingBoolean(key: String, default: Boolean): Boolean =
        getSetting(key, default.toString()).toBooleanStrictOrNull() ?: default

    fun setSetting(key: String, value: String): Unit = transaction {
        val k = key; val v = value
        Settings.upsert { it[Settings.key] = k; it[Settings.value] = v }
    }

    fun setSettingBoolean(key: String, value: Boolean) = setSetting(key, value.toString())

    // ── Prefs migration ────────────────────────────────────────────────────────

    // On first run, copy java.util.prefs values into settings, then wipe the registry node.
    private fun migrateFromPrefs() {
        val alreadyMigrated = transaction {
            Settings.selectAll().where { Settings.key eq "_migrated_from_prefs" }.count() > 0
        }
        if (alreadyMigrated) return

        runCatching {
            val prefs = java.util.prefs.Preferences.userRoot().node("zarchive")
            transaction {
                fun put(k: String, v: String) =
                    Settings.upsert { it[Settings.key] = k; it[Settings.value] = v }
                put("ignoreBasicLands",  prefs.getBoolean("ignoreBasicLands", true).toString())
                put("autoOpenLuckshack", prefs.getBoolean("autoOpenLuckshack", false).toString())
                put("earlyAccess",       prefs.getBoolean("earlyAccess", false).toString())
                put("disabledStores",    prefs.get("disabledStores", ""))
                if (prefs.getBoolean("includeWarren", false)) put("includeWarren", "true")
                put("_migrated_from_prefs", "true")
            }
            prefs.removeNode()
        }
        // Fresh install — mark migration done.
        transaction {
            if (Settings.selectAll().where { Settings.key eq "_migrated_from_prefs" }.count() == 0L)
                Settings.upsert { it[Settings.key] = "_migrated_from_prefs"; it[Settings.value] = "true" }
        }
    }
}
