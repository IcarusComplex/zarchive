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

    override suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        queries.deleteSnapshot(id.toLong())
        refresh()
    }
}
