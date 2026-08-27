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
    // Google Drive sync (see sync/SyncMerge.kt): syncId is the stable cross-device identity
    // (local auto-increment ids collide across devices, both start counting from 1). deleted/
    // deletedAt are a soft-delete tombstone so a delete propagates through the latest-wins merge
    // instead of a naive sync resurrecting the row.
    val syncId    = text("sync_id").nullable()
    val deleted   = bool("deleted").default(false)
    val deletedAt = long("deleted_at").nullable()
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
    val cardQuantitiesJson = text("card_quantities_json").nullable()  // JSON Map<String,Int>
    // Google Drive sync — see the matching comment on SearchLists above.
    val syncId    = text("sync_id").nullable()
    val deleted   = bool("deleted").default(false)
    val deletedAt = long("deleted_at").nullable()
    override val primaryKey = PrimaryKey(id)
}

// Persisted Cloudflare throttle rules — one row per store that has ever 429'd.
// Two independent tiers: tierSmall (for cards*stores < 300) and tierLarge (>= 300).
// Each escalates only when a 429 fires in its respective bucket, so a store that
// 429s on a large search doesn't slow down future small searches.
object CfThrottleRules : Table("cf_throttle_rules") {
    val baseUrl       = text("base_url")
    val cardThreshold = integer("card_threshold") // kept for schema compat; logic uses SearchClassifier weight
    val tier          = integer("tier")           // tierSmall: for SearchCategory.SMALL
    val tierMedium    = integer("tier_medium").default(2) // for SearchCategory.MEDIUM
    val tierLarge     = integer("tier_large").default(2)  // for SearchCategory.LARGE
    val lastHitAt     = long("last_hit_at")       // epoch ms of most-recent 429
    val lastHitCards  = integer("last_hit_cards") // card count at that time
    override val primaryKey = PrimaryKey(baseUrl)
}

// CfThrottleRule itself now lives in commonMain/kotlin/data/CfThrottleRule.kt (shared with
// engine.SearchEngine, which moved to jvmCommonMain in Phase 3) — same `data` package, no import
// needed here.

// Persisted API error/backoff log — see data/ErrorLog.kt (commonMain expect/actual). Capped to the
// most recent ~500 rows (see AppDatabase.recordApiError) so a repeated backoff loop can't grow the
// table unbounded.
object ApiErrorLogs : Table("api_error_logs") {
    val id        = integer("id").autoIncrement()
    val timestamp = long("timestamp")
    val store     = text("store")
    val url       = text("url")
    val kind      = text("kind")
    val message   = text("message")
    // Full response detail (status/headers/truncated body) when available -- e.g. a Cloudflare
    // block's cf-ray/retry-after headers and challenge body. Nullable: added after the table's
    // first release; createMissingTablesAndColumns adds it as a nullable column to existing DBs.
    val detail    = text("detail").nullable()
    override val primaryKey = PrimaryKey(id)
}

// A full replace on every collection import (see data/CollectionRepo.kt) — no sync_id/deleted,
// this never round-trips through Google Drive's list/result merge.
object CollectionRows : Table("collection_rows") {
    val id        = integer("id").autoIncrement()
    val groupName = text("group_name")
    val groupType = text("group_type")
    val cardName  = text("card_name")
    val quantity  = integer("quantity")
    override val primaryKey = PrimaryKey(id)
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
            SchemaUtils.createMissingTablesAndColumns(
                Settings, SearchLists, SearchListCards, CfThrottleRules, SavedResultSnapshots, CollectionRows,
                ApiErrorLogs,
            )
        }
        migrateCfThresholdV2()
        migrateSyncV1Backfill()
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
                baseUrl    = row[CfThrottleRules.baseUrl],
                tierSmall  = row[CfThrottleRules.tier],
                tierMedium = row[CfThrottleRules.tierMedium],
                tierLarge  = row[CfThrottleRules.tierLarge],
            )
        }.associateBy { it.baseUrl }
    }

    /**
     * Record a 429 hit for [baseUrl]. Only the bucket matching [category] escalates: a 429 on a
     * MEDIUM search advances tierMedium; a 429 on a SMALL search advances tierSmall; etc. Each
     * bucket escalates at most once per 2-hour window (max tier 3).
     *
     * First hit in any bucket escalates from that bucket's base (small=1, medium=2, large=3) --
     * large's base is already the max tier, so a first LARGE-bucket hit is a no-op numerically, but
     * still records lastHitAt/lastHitCards.
     */
    fun recordCfBlock(baseUrl: String, cardCount: Int, category: SearchCategory): Unit = transaction {
        val now        = System.currentTimeMillis()
        val twoHoursMs = 2L * 60 * 60 * 1_000

        val existing = CfThrottleRules.selectAll()
            .where { CfThrottleRules.baseUrl eq baseUrl }
            .firstOrNull()

        val isNewEvent = existing == null || (now - existing[CfThrottleRules.lastHitAt]) > twoHoursMs
        val (newSmall, newMedium, newLarge) = if (existing == null) escalateCfTiers(category, isNewEvent)
            else escalateCfTiers(
                category, isNewEvent,
                existing[CfThrottleRules.tier], existing[CfThrottleRules.tierMedium], existing[CfThrottleRules.tierLarge],
            )

        if (existing == null) {
            CfThrottleRules.insert {
                it[CfThrottleRules.baseUrl]       = baseUrl
                it[CfThrottleRules.cardThreshold] = 300
                it[CfThrottleRules.tier]          = newSmall
                it[CfThrottleRules.tierMedium]    = newMedium
                it[CfThrottleRules.tierLarge]     = newLarge
                it[CfThrottleRules.lastHitAt]     = now
                it[CfThrottleRules.lastHitCards]  = cardCount
            }
        } else {
            CfThrottleRules.update({ CfThrottleRules.baseUrl eq baseUrl }) {
                it[CfThrottleRules.tier]         = newSmall
                it[CfThrottleRules.tierMedium]   = newMedium
                it[CfThrottleRules.tierLarge]    = newLarge
                it[CfThrottleRules.lastHitAt]    = now
                it[CfThrottleRules.lastHitCards] = cardCount
            }
        }
    }

    // ── API error/backoff log ────────────────────────────────────────────────

    private const val API_ERROR_LOG_CAP = 500

    fun recordApiError(store: String, url: String, kind: String, message: String, detail: String? = null): Unit = transaction {
        ApiErrorLogs.insert {
            it[ApiErrorLogs.timestamp] = System.currentTimeMillis()
            it[ApiErrorLogs.store]     = store
            it[ApiErrorLogs.url]       = url
            it[ApiErrorLogs.kind]      = kind
            it[ApiErrorLogs.message]   = message
            it[ApiErrorLogs.detail]    = detail
        }
        val count = ApiErrorLogs.selectAll().count()
        if (count > API_ERROR_LOG_CAP) {
            val staleIds = ApiErrorLogs.selectAll()
                .orderBy(ApiErrorLogs.timestamp, SortOrder.ASC)
                .limit((count - API_ERROR_LOG_CAP).toInt())
                .map { it[ApiErrorLogs.id] }
            ApiErrorLogs.deleteWhere { Op.build { ApiErrorLogs.id inList staleIds } }
        }
    }

    fun loadRecentApiErrors(limit: Int): List<ApiErrorEntry> = transaction {
        ApiErrorLogs.selectAll()
            .orderBy(ApiErrorLogs.timestamp, SortOrder.DESC)
            .limit(limit)
            .map { row ->
                ApiErrorEntry(
                    timestamp = row[ApiErrorLogs.timestamp],
                    store     = row[ApiErrorLogs.store],
                    url       = row[ApiErrorLogs.url],
                    kind      = row[ApiErrorLogs.kind],
                    message   = row[ApiErrorLogs.message],
                    detail    = row[ApiErrorLogs.detail],
                )
            }
    }

    fun clearApiErrors(): Unit = transaction {
        ApiErrorLogs.deleteAll()
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

    // ── Sync backfill (v1) ──────────────────────────────────────────────────────

    // One-time: assigns a syncId to every pre-existing row so Google Drive sync has a stable
    // cross-device identity for rows created before sync existed. New rows get a syncId at
    // creation time instead (see DesktopSearchListRepo/DesktopSearchResultRepo).
    private fun migrateSyncV1Backfill() {
        val done = transaction {
            Settings.selectAll().where { Settings.key eq "_sync_v1_backfill" }.count() > 0
        }
        if (done) return
        transaction {
            SearchLists.selectAll().where { SearchLists.syncId.isNull() }.forEach { row ->
                SearchLists.update({ SearchLists.id eq row[SearchLists.id] }) {
                    it[syncId] = java.util.UUID.randomUUID().toString()
                }
            }
            SavedResultSnapshots.selectAll().where { SavedResultSnapshots.syncId.isNull() }.forEach { row ->
                SavedResultSnapshots.update({ SavedResultSnapshots.id eq row[SavedResultSnapshots.id] }) {
                    it[syncId] = java.util.UUID.randomUUID().toString()
                }
            }
            Settings.upsert { it[Settings.key] = "_sync_v1_backfill"; it[Settings.value] = "true" }
        }
    }
}
