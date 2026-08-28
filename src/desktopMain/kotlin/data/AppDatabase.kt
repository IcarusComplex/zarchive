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

// NOTE: the per-store escalating Cloudflare throttle system (a "cf_throttle_rules" table +
// CfThrottleRule/CfThrottleStore.desktop.kt) was removed (Aug 2026) in favour of SizeScaledThrottle
// (engine/SearchEngine.kt) -- a search-size-scaled delay applied unconditionally from a store's
// first request, not gated behind having 429'd before. The old table is deliberately left as an
// orphaned, unqueried artifact in any existing user's local DB rather than migrated away -- see the
// plan doc's "Redesign (Aug 28)" section for the reasoning (a DROP TABLE migration carries real risk
// for zero benefit versus just leaving inert data behind).

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
                Settings, SearchLists, SearchListCards, SavedResultSnapshots, CollectionRows,
                ApiErrorLogs,
            )
        }
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
