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

// Snapshots of search results — each row is a named save-point the user can restore.
object SavedResultSnapshots : Table("saved_result_snapshots") {
    val id                 = integer("id").autoIncrement()
    val name               = text("name")
    val description        = text("description")           // optional user note
    val savedAt            = long("saved_at")              // epoch ms
    val cardCount          = integer("card_count")
    val cardsJson          = text("cards_json")            // JSON List<String>
    val resultsJson        = text("results_json")          // JSON List<SearchResult>
    val excludedCardsJson  = text("excluded_cards_json").nullable()   // JSON Set<String>
    val uncheckedLinesJson = text("unchecked_lines_json").nullable()  // JSON Set<String> (URLs)
    val pinnedListingsJson = text("pinned_listings_json").nullable()  // JSON Map<String,String>
    override val primaryKey = PrimaryKey(id)
}

// Persisted Cloudflare throttle rules — one row per store that has ever 429'd.
// Tier escalates (1→2→3) on independent re-offences. Rules are permanent — CF limits don't relax.
object CfThrottleRules : Table("cf_throttle_rules") {
    val baseUrl       = text("base_url")
    val cardThreshold = integer("card_threshold") // throttle only if search >= this
    val tier          = integer("tier")           // 1 / 2 / 3
    val lastHitAt     = long("last_hit_at")       // epoch ms of most-recent 429
    val lastHitCards  = integer("last_hit_cards") // card count at that time
    override val primaryKey = PrimaryKey(baseUrl)
}

// ── Domain object returned by loadActiveCfRules ────────────────────────────────

data class CfThrottleRule(
    val baseUrl: String,
    val cardThreshold: Int,
    val effectiveTier: Int,
)

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
            SchemaUtils.createMissingTablesAndColumns(
                Settings, SearchLists, SearchListCards, CfThrottleRules, SavedResultSnapshots,
            )
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

    // ── CF throttle rules ──────────────────────────────────────────────────────

    fun loadActiveCfRules(): Map<String, CfThrottleRule> = transaction {
        CfThrottleRules.selectAll().map { row ->
            CfThrottleRule(
                baseUrl       = row[CfThrottleRules.baseUrl],
                cardThreshold = row[CfThrottleRules.cardThreshold],
                effectiveTier = row[CfThrottleRules.tier],
            )
        }.associateBy { it.baseUrl }
    }

    /**
     * Record a 429 hit for [baseUrl] that occurred during a [cardCount]-card search.
     *
     * - First hit: tier 1, threshold = max(1, cardCount − 5).
     * - Subsequent hit > 2 h after the last: escalate tier (max 3), take the stricter threshold.
     * - Subsequent hit ≤ 2 h after the last: still within the original cooldown window —
     *   update timestamp to reset the window but do not escalate.
     */
    fun recordCfBlock(baseUrl: String, cardCount: Int): Unit = transaction {
        val newThreshold = maxOf(1, cardCount - 5)
        val now          = System.currentTimeMillis()
        val twoHoursMs   = 2L * 60 * 60 * 1_000

        val existing = CfThrottleRules.selectAll()
            .where { CfThrottleRules.baseUrl eq baseUrl }
            .firstOrNull()

        if (existing == null) {
            CfThrottleRules.insert {
                it[CfThrottleRules.baseUrl]       = baseUrl
                it[CfThrottleRules.cardThreshold] = newThreshold
                it[CfThrottleRules.tier]          = 1
                it[CfThrottleRules.lastHitAt]     = now
                it[CfThrottleRules.lastHitCards]  = cardCount
            }
        } else {
            val storedTier   = existing[CfThrottleRules.tier]
            val lastHitAt    = existing[CfThrottleRules.lastHitAt]
            val isNewEvent   = (now - lastHitAt) > twoHoursMs
            val newTier      = if (isNewEvent) minOf(3, storedTier + 1) else storedTier
            val strictThresh = maxOf(1, minOf(existing[CfThrottleRules.cardThreshold], newThreshold))

            CfThrottleRules.update({ CfThrottleRules.baseUrl eq baseUrl }) {
                it[CfThrottleRules.cardThreshold] = strictThresh
                it[CfThrottleRules.tier]          = newTier
                it[CfThrottleRules.lastHitAt]     = now
                it[CfThrottleRules.lastHitCards]  = cardCount
            }
        }
    }

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
