package data

import kotlinx.coroutines.flow.StateFlow

/**
 * Dumb storage for the last-imported collection-tracker export: a full replace on every import,
 * no per-row CRUD, no Google Drive sync merge (it's a one-way local mirror of whatever was last
 * imported, not something that round-trips through [sync.SyncMerge]). All the "which groups count
 * as owned" / format / Drive-fetch logic lives in `collection.CollectionImportEngine`, which treats
 * this repo purely as a place to persist rows.
 *
 * Backed by a real table per platform: `DesktopCollectionRepo` (Exposed/H2, desktopMain) or
 * `AndroidCollectionRepo` (SQLDelight, androidMain).
 */
interface CollectionRepo {
    val rows: StateFlow<List<CollectionRow>>

    suspend fun replaceRows(rows: List<CollectionRow>)
}
