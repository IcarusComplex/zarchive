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
        seedIfEmpty()
    }

    // Same one-time seed list as desktop's DesktopSearchListRepo, gated by the same "_seed_v1_lists"
    // settings flag (now backed by the Android settings table instead of AppDatabase's).
    private fun seedIfEmpty() {
        val alreadySeeded = queries.getSetting("_seed_v1_lists").executeAsOneOrNull() != null
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
        queries.insertList(listName, now, now)
        val id = queries.lastInsertRowId().executeAsOne()
        insertCards(id, cards)
        queries.setSetting("_seed_v1_lists", "true")
        refresh()
    }

    private fun refresh() {
        _lists.value = queries.selectAllLists().executeAsList().map { row ->
            SavedSearchList(
                id = row.id.toInt(),
                name = row.name,
                cards = queries.selectCardsForList(row.id).executeAsList(),
                updatedAt = row.updated_at,
            )
        }
    }

    override suspend fun create(name: String, cards: List<String>): Int = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        queries.insertList(name, now, now)
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

    override suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        queries.deleteListCards(id.toLong())
        queries.deleteList(id.toLong())
        refresh()
    }

    private fun insertCards(listId: Long, cards: List<String>) {
        cards.forEachIndexed { i, card -> queries.insertListCard(listId, i.toLong(), card) }
    }
}
