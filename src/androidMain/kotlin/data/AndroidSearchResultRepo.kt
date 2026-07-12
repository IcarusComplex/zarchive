package data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Temporary in-memory stub — replaced by a real SQLDelight-backed implementation in Phase 5. */
class AndroidSearchResultRepo : SearchResultRepo {
    private var nextId = 1
    private val snapshots = mutableMapOf<Int, LoadedResultSnapshot>()
    private val _entries = MutableStateFlow<List<SavedResultEntry>>(emptyList())
    override val entries: StateFlow<List<SavedResultEntry>> = _entries.asStateFlow()

    override suspend fun save(
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    ) {
        val id = nextId++
        snapshots[id] = LoadedResultSnapshot(cards, results, excludedCards, uncheckedLines, pinnedListings)
        _entries.value = listOf(
            SavedResultEntry(id, name, description, System.currentTimeMillis(), cards.size, cards)
        ) + _entries.value
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
    ) {
        snapshots[id] = LoadedResultSnapshot(cards, results, excludedCards, uncheckedLines, pinnedListings)
        _entries.value = _entries.value.map {
            if (it.id == id) it.copy(name = name, description = description, savedAt = System.currentTimeMillis(), cardCount = cards.size, cards = cards)
            else it
        }
    }

    override suspend fun load(id: Int): LoadedResultSnapshot? = snapshots[id]

    override suspend fun delete(id: Int) {
        snapshots.remove(id)
        _entries.value = _entries.value.filter { it.id != id }
    }
}
