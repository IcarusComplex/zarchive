package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Real persistence via the Android-only SQLDelight database (Phase 5) — replaces the Phase 4
 * in-memory stub. Same synchronous-query + refresh()-into-StateFlow pattern, and the same JSON
 * encode/decode of snapshot columns, as desktop's `DesktopSearchResultRepo`. */
class AndroidSearchResultRepo : SearchResultRepo {
    private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<SavedResultEntry>>(emptyList())
    override val entries: StateFlow<List<SavedResultEntry>> = _entries.asStateFlow()

    init { refresh() }

    private fun refresh() {
        _entries.value = queries.selectAllSnapshots().executeAsList().map { row ->
            SavedResultEntry(
                id = row.id.toInt(),
                name = row.name,
                description = row.description,
                savedAt = row.saved_at,
                cardCount = row.card_count.toInt(),
                cards = json.decodeFromString(row.cards_json),
                syncId = row.sync_id,
            )
        }
    }

    override suspend fun save(
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    ): Unit = withContext(Dispatchers.IO) {
        queries.insertSnapshot(
            name = name,
            description = description,
            saved_at = System.currentTimeMillis(),
            card_count = cards.size.toLong(),
            cards_json = json.encodeToString(cards),
            results_json = json.encodeToString(results),
            excluded_cards_json = json.encodeToString(excludedCards.toList()),
            unchecked_lines_json = json.encodeToString(uncheckedLines.toList()),
            pinned_listings_json = json.encodeToString(pinnedListings),
            sync_id = java.util.UUID.randomUUID().toString(),
        )
        refresh()
    }

    override suspend fun overwrite(
        id: Int,
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    ): Unit = withContext(Dispatchers.IO) {
        queries.updateSnapshot(
            name = name,
            description = description,
            saved_at = System.currentTimeMillis(),
            card_count = cards.size.toLong(),
            cards_json = json.encodeToString(cards),
            results_json = json.encodeToString(results),
            excluded_cards_json = json.encodeToString(excludedCards.toList()),
            unchecked_lines_json = json.encodeToString(uncheckedLines.toList()),
            pinned_listings_json = json.encodeToString(pinnedListings),
            id = id.toLong(),
        )
        refresh()
    }

    override suspend fun load(id: Int): LoadedResultSnapshot? = withContext(Dispatchers.IO) {
        val row = queries.selectSnapshotById(id.toLong()).executeAsOneOrNull() ?: return@withContext null
        LoadedResultSnapshot(
            cards = json.decodeFromString(row.cards_json),
            results = json.decodeFromString(row.results_json),
            excludedCards = json.decodeFromString<List<String>>(row.excluded_cards_json ?: "[]").toSet(),
            uncheckedLines = json.decodeFromString<List<String>>(row.unchecked_lines_json ?: "[]").toSet(),
            pinnedListings = json.decodeFromString(row.pinned_listings_json ?: "{}"),
        )
    }

    // Soft-delete (tombstone) -- see the matching comment on AndroidSearchListRepo.delete().
    override suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        queries.deleteSnapshot(now, now, id.toLong())
        refresh()
    }

    override suspend fun allForSync(): List<SyncedResultRecord> = withContext(Dispatchers.IO) {
        queries.selectAllSnapshotsForSync().executeAsList().map { row ->
            SyncedResultRecord(
                id = row.id.toInt(),
                syncId = row.sync_id,
                name = row.name,
                description = row.description,
                savedAt = row.saved_at,
                cards = json.decodeFromString(row.cards_json),
                results = json.decodeFromString(row.results_json),
                excludedCards = json.decodeFromString<List<String>>(row.excluded_cards_json ?: "[]").toSet(),
                uncheckedLines = json.decodeFromString<List<String>>(row.unchecked_lines_json ?: "[]").toSet(),
                pinnedListings = json.decodeFromString(row.pinned_listings_json ?: "{}"),
                deleted = row.deleted != 0L,
                deletedAt = row.deleted_at,
            )
        }
    }

    override suspend fun applyRemote(record: SyncedResultRecord): Unit = withContext(Dispatchers.IO) {
        val sid = requireNotNull(record.syncId) { "applyRemote requires a non-null syncId" }
        val existing = queries.selectSnapshotBySyncId(sid).executeAsOneOrNull()
        val cardsJson = json.encodeToString(record.cards)
        val resultsJson = json.encodeToString(record.results)
        val excludedJson = json.encodeToString(record.excludedCards.toList())
        val uncheckedJson = json.encodeToString(record.uncheckedLines.toList())
        val pinnedJson = json.encodeToString(record.pinnedListings)
        if (existing == null) {
            queries.insertSnapshotForSync(
                record.name, record.description, record.savedAt, record.cards.size.toLong(),
                cardsJson, resultsJson, excludedJson, uncheckedJson, pinnedJson,
                sid, if (record.deleted) 1L else 0L, record.deletedAt,
            )
        } else {
            queries.updateSnapshotForSync(
                record.name, record.description, record.savedAt, record.cards.size.toLong(),
                cardsJson, resultsJson, excludedJson, uncheckedJson, pinnedJson,
                if (record.deleted) 1L else 0L, record.deletedAt, existing.id,
            )
        }
        refresh()
    }
}
