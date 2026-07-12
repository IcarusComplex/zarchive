package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** Real persistence via the Android-only SQLDelight database (Phase 5) — replaces the Phase 4
 * in-memory stub. Same synchronous-query + refresh()-into-StateFlow pattern as desktop's
 * `DesktopSearchListRepo`, translated from Exposed transactions to SQLDelight queries. */
class AndroidSearchListRepo : SearchListRepo {
    private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries

    private val _lists = MutableStateFlow<List<SavedSearchList>>(emptyList())
    override val lists: StateFlow<List<SavedSearchList>> = _lists.asStateFlow()

    init {
        refresh()
    }

    private fun refresh() {
        _lists.value = queries.selectAllLists().executeAsList().map { row ->
            SavedSearchList(
                id = row.id.toInt(),
                name = row.name,
                cards = queries.selectCardsForList(row.id).executeAsList(),
                updatedAt = row.updated_at,
                syncId = row.sync_id,
                deleted = row.deleted != 0L,
                deletedAt = row.deleted_at,
            )
        }
    }

    override suspend fun create(name: String, cards: List<String>): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        queries.insertList(name, now, now, java.util.UUID.randomUUID().toString())
        val newId = queries.lastInsertRowId().executeAsOne()
        insertCards(newId, cards)
        refresh()
        newId.toInt()
    }

    override suspend fun touch(id: Int): Unit = withContext(Dispatchers.IO) {
        queries.touchList(System.currentTimeMillis(), id.toLong())
        refresh()
    }

    override suspend fun rename(id: Int, newName: String): Unit = withContext(Dispatchers.IO) {
        queries.renameList(newName, System.currentTimeMillis(), id.toLong())
        refresh()
    }

    override suspend fun update(id: Int, newName: String, cards: List<String>): Unit = withContext(Dispatchers.IO) {
        queries.deleteListCards(id.toLong())
        queries.renameList(newName, System.currentTimeMillis(), id.toLong())
        insertCards(id.toLong(), cards)
        refresh()
    }

    override suspend fun updateCards(id: Int, cards: List<String>): Unit = withContext(Dispatchers.IO) {
        queries.deleteListCards(id.toLong())
        queries.touchList(System.currentTimeMillis(), id.toLong())
        insertCards(id.toLong(), cards)
        refresh()
    }

    // Soft-delete (tombstone), not a hard row delete: Google Drive sync needs to see this list was
    // deleted so the tombstone propagates through the latest-wins merge to the other device instead
    // of a naive sync resurrecting it. Cards are left intact in case a concurrent edit on the other
    // device is newer and "wins" back over this delete.
    override suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        queries.deleteList(now, now, id.toLong())
        refresh()
    }

    override suspend fun allForSync(): List<SavedSearchList> = withContext(Dispatchers.IO) {
        queries.selectAllListsForSync().executeAsList().map { row ->
            SavedSearchList(
                id = row.id.toInt(),
                name = row.name,
                cards = queries.selectCardsForList(row.id).executeAsList(),
                updatedAt = row.updated_at,
                syncId = row.sync_id,
                deleted = row.deleted != 0L,
                deletedAt = row.deleted_at,
            )
        }
    }

    override suspend fun applyRemote(record: SavedSearchList): Unit = withContext(Dispatchers.IO) {
        val sid = requireNotNull(record.syncId) { "applyRemote requires a non-null syncId" }
        val existing = queries.selectListBySyncId(sid).executeAsOneOrNull()
        if (existing == null) {
            queries.insertListForSync(
                record.name, record.updatedAt, record.updatedAt, sid,
                if (record.deleted) 1L else 0L, record.deletedAt,
            )
            val newId = queries.lastInsertRowId().executeAsOne()
            insertCards(newId, record.cards)
        } else {
            queries.deleteListCards(existing.id)
            queries.updateListForSync(
                record.name, record.updatedAt, if (record.deleted) 1L else 0L, record.deletedAt, existing.id,
            )
            insertCards(existing.id, record.cards)
        }
        refresh()
    }

    private fun insertCards(listId: Long, cards: List<String>) {
        cards.forEachIndexed { i, card -> queries.insertListCard(listId, i.toLong(), card) }
    }
}
