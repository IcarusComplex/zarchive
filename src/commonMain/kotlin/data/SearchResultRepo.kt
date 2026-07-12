package data

import kotlinx.coroutines.flow.StateFlow

data class SavedResultEntry(
    val id: Int,
    val name: String,
    val description: String,
    val savedAt: Long,
    val cardCount: Int,
    val cards: List<String>,
    // Google Drive sync -- see the matching comment on SavedSearchList.
    val syncId: String? = null,
)

data class LoadedResultSnapshot(
    val cards: List<String>,
    val results: List<SearchResult>,
    val excludedCards: Set<String>,
    val uncheckedLines: Set<String>,
    val pinnedListings: Map<String, String>,
)

/**
 * Full content of a saved result snapshot, including the sync bookkeeping fields that
 * [SavedResultEntry] (a lightweight list-row summary) and [LoadedResultSnapshot] (returned only by
 * `load()`, no summary fields) each omit. This is the shape the Google Drive sync engine reads/
 * writes via [SearchResultRepo.allForSync]/[SearchResultRepo.applyRemote] -- kept separate from
 * those two so neither's existing shape/call sites need to change.
 */
data class SyncedResultRecord(
    val id: Int,
    val syncId: String?,
    val name: String,
    val description: String,
    val savedAt: Long,
    val cards: List<String>,
    val results: List<SearchResult>,
    val excludedCards: Set<String>,
    val uncheckedLines: Set<String>,
    val pinnedListings: Map<String, String>,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

/**
 * Saved result-snapshot CRUD, backed by a real database per platform: `DesktopSearchResultRepo`
 * (Exposed/H2, desktopMain) or `AndroidSearchResultRepo` (SQLDelight, androidMain).
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

    /** Every row including soft-deleted tombstones -- for building the outgoing sync payload. */
    suspend fun allForSync(): List<SyncedResultRecord>

    /**
     * Insert-or-update (matched by [record.syncId], never by name/id) so the local row exactly
     * matches a remote record that won the latest-wins merge.
     */
    suspend fun applyRemote(record: SyncedResultRecord)
}
