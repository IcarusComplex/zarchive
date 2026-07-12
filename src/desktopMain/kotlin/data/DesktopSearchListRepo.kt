package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

// SavedSearchList now lives in commonMain/kotlin/data/SearchListRepo.kt (shared interface) —
// same `data` package, no import needed here.

class DesktopSearchListRepo : SearchListRepo {
    private val _lists = MutableStateFlow<List<SavedSearchList>>(emptyList())
    override val lists: StateFlow<List<SavedSearchList>> = _lists.asStateFlow()

    init {
        refresh()
        seedIfEmpty()
    }

    private fun seedIfEmpty() {
        val alreadySeeded = transaction {
            Settings.selectAll().where { Settings.key eq "_seed_v1_lists" }.count() > 0L
        }
        if (alreadySeeded) return
        val cards = listOf(
            "Aragorn and Arwen, Wed",
            "Abzan Falconer",
            "Loran of the Third Path",
            "Nykthos Paragon",
            "Secure the Wastes",
            "White Sun's Zenith",
            "Season of the Burrow",
            "Tempt with Bunnies",
            "Avacyn's Pilgrim",
            "Elvish Mystic",
            "Kami of Whispered Hopes",
            "Rishkar, Peema Renegade",
            "Inspiring Call",
            "Collective Unconscious",
            "Harvest Season",
            "Kodama's Reach",
            "Rishkar's Expertise",
            "Shamanic Revelation",
            "Tempt with Discovery",
            "Hardened Scales",
            "Primeval Bounty",
            "Byrke, Long Ear of the Law",
            "Cadira, Caller of the Small",
            "Conclave Mentor",
            "Dyadrine, Synthesis Amalgam",
            "Emmara, Soul of the Accord",
            "Finneas, Ace Archer",
            "Ghalta and Mavren",
            "Haliya, Ascendant Cadet",
            "Hamza, Guardian of Arashin",
            "Lathiel, the Bounteous Dawn",
            "Yasharn, Implacable Earth",
            "Aura Mutation",
            "March of the Multitudes",
            "Rite of Harmony",
            "Camaraderie",
        )
        val listName = "Aragorn & Arwen EDH (Example)"
        val now = System.currentTimeMillis()
        val id = transaction {
            SearchLists.insert {
                it[SearchLists.name]      = listName
                it[SearchLists.createdAt] = now
                it[SearchLists.updatedAt] = now
            }[SearchLists.id]
        }
        insertCards(id, cards)
        transaction {
            Settings.upsert { it[Settings.key] = "_seed_v1_lists"; it[Settings.value] = "true" }
        }
        refresh()
    }

    private fun refresh() {
        _lists.value = transaction {
            val cardsByList = SearchListCards.selectAll()
                .orderBy(SearchListCards.position)
                .groupBy({ it[SearchListCards.listId] }, { it[SearchListCards.cardName] })

            SearchLists.selectAll()
                .orderBy(SearchLists.updatedAt, SortOrder.DESC)
                .map { row ->
                    SavedSearchList(
                        id        = row[SearchLists.id],
                        name      = row[SearchLists.name],
                        cards     = cardsByList[row[SearchLists.id]] ?: emptyList(),
                        updatedAt = row[SearchLists.updatedAt],
                    )
                }
        }
    }

    override suspend fun create(name: String, cards: List<String>): Int = withContext(Dispatchers.IO) {
        // 'listName' avoids shadowing by SearchLists.name inside the table-receiver lambda.
        val listName = name
        val now = System.currentTimeMillis()
        val newId = transaction {
            SearchLists.insert {
                it[SearchLists.name]      = listName
                it[SearchLists.createdAt] = now
                it[SearchLists.updatedAt] = now
            }[SearchLists.id]
        }
        insertCards(newId, cards)
        refresh()
        newId
    }

    // Call when a list is loaded so it rises to the top of the recency order.
    override suspend fun touch(id: Int): Unit = withContext(Dispatchers.IO) {
        val lid = id
        transaction {
            SearchLists.update({ Op.build { SearchLists.id eq lid } }) {
                it[SearchLists.updatedAt] = System.currentTimeMillis()
            }
        }
        refresh()
    }

    override suspend fun rename(id: Int, newName: String): Unit = withContext(Dispatchers.IO) {
        val lid = id
        transaction {
            SearchLists.update({ Op.build { SearchLists.id eq lid } }) {
                it[SearchLists.name]      = newName
                it[SearchLists.updatedAt] = System.currentTimeMillis()
            }
        }
        refresh()
    }

    override suspend fun update(id: Int, newName: String, cards: List<String>): Unit = withContext(Dispatchers.IO) {
        val lid = id; val listName = newName; val now = System.currentTimeMillis()
        transaction {
            SearchListCards.deleteWhere { Op.build { SearchListCards.listId eq lid } }
            SearchLists.update({ Op.build { SearchLists.id eq lid } }) {
                it[SearchLists.name]      = listName
                it[SearchLists.updatedAt] = now
            }
        }
        insertCards(id, cards)
        refresh()
    }

    override suspend fun updateCards(id: Int, cards: List<String>): Unit = withContext(Dispatchers.IO) {
        val lid = id
        transaction {
            SearchListCards.deleteWhere { Op.build { SearchListCards.listId eq lid } }
            SearchLists.update({ Op.build { SearchLists.id eq lid } }) {
                it[SearchLists.updatedAt] = System.currentTimeMillis()
            }
        }
        insertCards(id, cards)
        refresh()
    }

    override suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        val lid = id
        transaction {
            SearchListCards.deleteWhere { Op.build { SearchListCards.listId eq lid } }
            SearchLists.deleteWhere    { Op.build { SearchLists.id eq lid } }
        }
        refresh()
    }

    private fun insertCards(listId: Int, cards: List<String>) {
        if (cards.isEmpty()) return
        val lid = listId
        transaction {
            SearchListCards.batchInsert(cards.mapIndexed { i, c -> i to c }) { (pos, card) ->
                this[SearchListCards.listId]   = lid
                this[SearchListCards.position] = pos
                this[SearchListCards.cardName] = card
            }
        }
    }
}
