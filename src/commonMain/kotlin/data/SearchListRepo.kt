package data

import kotlinx.coroutines.flow.StateFlow

data class SavedSearchList(
    val id: Int,
    val name: String,
    val cards: List<String>,
    val updatedAt: Long,
)

/**
 * Saved card-list CRUD, backed by a real database per platform: `DesktopSearchListRepo` (Exposed/
 * H2, desktopMain) or `AndroidSearchListRepo` (in-memory stub until Phase 5's SQLDelight database).
 */
interface SearchListRepo {
    val lists: StateFlow<List<SavedSearchList>>

    suspend fun create(name: String, cards: List<String>): Int
    suspend fun touch(id: Int)
    suspend fun rename(id: Int, newName: String)
    suspend fun update(id: Int, newName: String, cards: List<String>)
    suspend fun updateCards(id: Int, cards: List<String>)
    suspend fun delete(id: Int)
}
