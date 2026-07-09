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
// Two independent tiers: tierSmall (for cards*stores < 300) and tierLarge (>= 300).
// Each escalates only when a 429 fires in its respective bucket, so a store that
// 429s on a large search doesn't slow down future small searches.
object CfThrottleRules : Table("cf_throttle_rules") {
    val baseUrl       = text("base_url")
    val cardThreshold = integer("card_threshold") // kept for schema compat; logic uses cards*stores
    val tier          = integer("tier")           // tierSmall: for cards*stores < 300
    val tierLarge     = integer("tier_large").default(2) // for cards*stores >= 300
    val lastHitAt     = long("last_hit_at")       // epoch ms of most-recent 429
    val lastHitCards  = integer("last_hit_cards") // card count at that time
    override val primaryKey = PrimaryKey(baseUrl)
}

// ── Domain object returned by loadActiveCfRules ────────────────────────────────

data class CfThrottleRule(
    val baseUrl: String,
    val tierSmall: Int,  // applied when cards*stores < 300
    val tierLarge: Int,  // applied when cards*stores >= 300
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
        migrateCfThresholdV2()
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
                baseUrl   = row[CfThrottleRules.baseUrl],
                tierSmall = row[CfThrottleRules.tier],
                tierLarge = row[CfThrottleRules.tierLarge],
            )
        }.associateBy { it.baseUrl }
    }

    /**
     * Record a 429 hit for [baseUrl].
     *
     * [isLargeSearch] is true when cards*stores >= 300 — i.e. the search was in the
     * large bucket. Only the matching bucket's tier escalates: a 429 on a large search
     * advances tierLarge; a 429 on a small search advances tierSmall. Each bucket
     * escalates at most once per 2-hour window (max tier 3).
     *
     * First hit in either bucket escalates from the bucket's base (small=1, large=2).
     */
    fun recordCfBlock(baseUrl: String, cardCount: Int, isLargeSearch: Boolean): Unit = transaction {
        val now        = System.currentTimeMillis()
        val twoHoursMs = 2L * 60 * 60 * 1_000

        val existing = CfThrottleRules.selectAll()
            .where { CfThrottleRules.baseUrl eq baseUrl }
            .firstOrNull()

        if (existing == null) {
            CfThrottleRules.insert {
                it[CfThrottleRules.baseUrl]       = baseUrl
                it[CfThrottleRules.cardThreshold] = 300
                // Escalate from the base for whichever bucket got the 429.
                it[CfThrottleRules.tier]          = if (isLargeSearch) 1 else 2 // tierSmall
                it[CfThrottleRules.tierLarge]     = if (isLargeSearch) 3 else 2 // tierLarge
                it[CfThrottleRules.lastHitAt]     = now
                it[CfThrottleRules.lastHitCards]  = cardCount
            }
        } else {
            val lastHitAt  = existing[CfThrottleRules.lastHitAt]
            val isNewEvent = (now - lastHitAt) > twoHoursMs
            val storedSmall = existing[CfThrottleRules.tier]
            val storedLarge = existing[CfThrottleRules.tierLarge]
            val newSmall = if (!isLargeSearch && isNewEvent) minOf(3, storedSmall + 1) else storedSmall
            val newLarge = if (isLargeSearch  && isNewEvent) minOf(3, storedLarge + 1) else storedLarge

            CfThrottleRules.update({ CfThrottleRules.baseUrl eq baseUrl }) {
                it[CfThrottleRules.tier]      = newSmall
                it[CfThrottleRules.tierLarge] = newLarge
                it[CfThrottleRules.lastHitAt]    = now
                it[CfThrottleRules.lastHitCards] = cardCount
            }
        }
    }

    // ── CF threshold migration (v2) ────────────────────────────────────────────

    // Ran once to fix the old threshold=cardCount-5 escalation cycle. Now also ensures
    // tierSmall=1 and tierLarge is present (added by createMissingTablesAndColumns with
    // default=2). After this migration: all stores start at tierSmall=1, tierLarge=2.
    private fun migrateCfThresholdV2() {
        val done = transaction {
            Settings.selectAll().where { Settings.key eq "_cf_v2_threshold" }.count() > 0
        }
        if (done) return
        transaction {
            CfThrottleRules.update({ CfThrottleRules.cardThreshold less 300 }) {
                it[CfThrottleRules.cardThreshold] = 300
            }
            CfThrottleRules.update({ CfThrottleRules.tier greater 1 }) {
                it[CfThrottleRules.tier] = 1
            }
            Settings.upsert { it[Settings.key] = "_cf_v2_threshold"; it[Settings.value] = "true" }
        }
    }
}
