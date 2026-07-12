package data

import kotlinx.coroutines.flow.StateFlow

data class SavedSearchList(
    val id: Int,
    val name: String,
    val cards: List<String>,
    val updatedAt: Long,
    // Google Drive sync (see sync/SyncMerge.kt): syncId is the stable cross-device identity that
    // local auto-increment ids can't provide (both platforms' ids start counting from 1). deleted/
    // deletedAt are a soft-delete tombstone so a delete propagates through the latest-wins merge
    // instead of a naive sync resurrecting the row. Nullable/default so every existing call site
    // that constructs a SavedSearchList without sync in mind keeps compiling unchanged.
    val syncId: String? = null,
    val deleted: Boolean = false,
    val deletedAt: Long? = null,
)

/**
 * Saved card-list CRUD, backed by a real database per platform: `DesktopSearchListRepo` (Exposed/
 * H2, desktopMain) or `AndroidSearchListRepo` (SQLDelight, androidMain).
 */
interface SearchListRepo {
    val lists: StateFlow<List<SavedSearchList>>

    suspend fun create(name: String, cards: List<String>): Int
    suspend fun touch(id: Int)
    suspend fun rename(id: Int, newName: String)
    suspend fun update(id: Int, newName: String, cards: List<String>)
    suspend fun updateCards(id: Int, cards: List<String>)
    suspend fun delete(id: Int)

    /** Every row including soft-deleted tombstones -- for building the outgoing sync payload. */
    suspend fun allForSync(): List<SavedSearchList>

    /**
     * Insert-or-update (matched by [record.syncId], never by name/id) so the local row exactly
     * matches a remote record that won the latest-wins merge. [record.id] is ignored on insert
     * (a fresh local auto-increment id is assigned); it only matters for matching on update.
     */
    suspend fun applyRemote(record: SavedSearchList)
}
