package sync

import data.SearchResult
import kotlinx.serialization.Serializable

/**
 * Wire format for a saved card list synced via Google Drive. Mirrors [data.SavedSearchList] plus
 * the sync bookkeeping fields; deliberately a separate type (not the repo's own data class) so the
 * DB schema/repo shape can evolve independently of the Drive JSON contract.
 */
@Serializable
data class SyncedList(
    val syncId: String,
    val name: String,
    val cards: List<String>,
    val updatedAt: Long,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

/**
 * Wire format for a saved result snapshot synced via Google Drive. Mirrors
 * [data.SyncedResultRecord] (full snapshot content, not the lightweight [data.SavedResultEntry]
 * list-row summary).
 */
@Serializable
data class SyncedResult(
    val syncId: String,
    val name: String,
    val description: String,
    val savedAt: Long,
    val cards: List<String>,
    val results: List<SearchResult>,
    val excludedCards: Set<String> = emptySet(),
    val uncheckedLines: Set<String> = emptySet(),
    val pinnedListings: Map<String, String> = emptyMap(),
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

/** The single JSON blob uploaded to/downloaded from the app-created Drive folder. */
@Serializable
data class SyncBlob(
    val schemaVersion: Int = 1,
    val lists: List<SyncedList> = emptyList(),
    val results: List<SyncedResult> = emptyList(),
)
