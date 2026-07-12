package data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Temporary in-memory stub — replaced by a real SQLDelight-backed implementation in Phase 5. */
class AndroidSearchListRepo : SearchListRepo {
    private var nextId = 1
    private val _lists = MutableStateFlow<List<SavedSearchList>>(emptyList())
    override val lists: StateFlow<List<SavedSearchList>> = _lists.asStateFlow()

    override suspend fun create(name: String, cards: List<String>): Int {
        val id = nextId++
        val now = System.currentTimeMillis()
        _lists.value = listOf(SavedSearchList(id, name, cards, now)) + _lists.value
        return id
    }

    override suspend fun touch(id: Int) {
        _lists.value = _lists.value.map {
            if (it.id == id) it.copy(updatedAt = System.currentTimeMillis()) else it
        }.sortedByDescending { it.updatedAt }
    }

    override suspend fun rename(id: Int, newName: String) {
        _lists.value = _lists.value.map {
            if (it.id == id) it.copy(name = newName, updatedAt = System.currentTimeMillis()) else it
        }
    }

    override suspend fun update(id: Int, newName: String, cards: List<String>) {
        _lists.value = _lists.value.map {
            if (it.id == id) it.copy(name = newName, cards = cards, updatedAt = System.currentTimeMillis()) else it
        }
    }

    override suspend fun updateCards(id: Int, cards: List<String>) {
        _lists.value = _lists.value.map {
            if (it.id == id) it.copy(cards = cards, updatedAt = System.currentTimeMillis()) else it
        }
    }

    override suspend fun delete(id: Int) {
        _lists.value = _lists.value.filter { it.id != id }
    }
}
