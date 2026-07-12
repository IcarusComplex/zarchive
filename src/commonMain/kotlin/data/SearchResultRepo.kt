package data

import kotlinx.coroutines.flow.StateFlow

data class SavedResultEntry(
    val id: Int,
    val name: String,
    val description: String,
    val savedAt: Long,
    val cardCount: Int,
    val cards: List<String>,
)

data class LoadedResultSnapshot(
    val cards: List<String>,
    val results: List<SearchResult>,
    val excludedCards: Set<String>,
    val uncheckedLines: Set<String>,
    val pinnedListings: Map<String, String>,
)

/**
 * Saved result-snapshot CRUD, backed by a real database per platform: `DesktopSearchResultRepo`
 * (Exposed/H2, desktopMain) or `AndroidSearchResultRepo` (in-memory stub until Phase 5's SQLDelight
 * database).
 */
interface SearchResultRepo {
    val entries: StateFlow<List<SavedResultEntry>>

    suspend fun save(
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    )

    suspend fun overwrite(
        id: Int,
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    )

    suspend fun load(id: Int): LoadedResultSnapshot?
    suspend fun delete(id: Int)
}
