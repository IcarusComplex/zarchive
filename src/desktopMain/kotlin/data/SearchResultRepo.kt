package data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction

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

class SearchResultRepo {
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<SavedResultEntry>>(emptyList())
    val entries: StateFlow<List<SavedResultEntry>> = _entries.asStateFlow()

    init { refresh() }

    private fun refresh() {
        _entries.value = transaction {
            SavedResultSnapshots.selectAll()
                .orderBy(SavedResultSnapshots.savedAt, SortOrder.DESC)
                .map { row ->
                    SavedResultEntry(
                        id          = row[SavedResultSnapshots.id],
                        name        = row[SavedResultSnapshots.name],
                        description = row[SavedResultSnapshots.description],
                        savedAt     = row[SavedResultSnapshots.savedAt],
                        cardCount   = row[SavedResultSnapshots.cardCount],
                        cards       = json.decodeFromString(row[SavedResultSnapshots.cardsJson]),
                    )
                }
        }
    }

    suspend fun save(
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    ): Unit = withContext(Dispatchers.IO) {
        val n = name; val d = description; val now = System.currentTimeMillis()
        val cardsEnc    = json.encodeToString(cards)
        val resultsEnc  = json.encodeToString(results)
        val excludedEnc = json.encodeToString(excludedCards.toList())
        val uncheckedEnc = json.encodeToString(uncheckedLines.toList())
        val pinnedEnc   = json.encodeToString(pinnedListings)
        transaction {
            SavedResultSnapshots.insert {
                it[SavedResultSnapshots.name]               = n
                it[SavedResultSnapshots.description]        = d
                it[SavedResultSnapshots.savedAt]            = now
                it[SavedResultSnapshots.cardCount]          = cards.size
                it[SavedResultSnapshots.cardsJson]          = cardsEnc
                it[SavedResultSnapshots.resultsJson]        = resultsEnc
                it[SavedResultSnapshots.excludedCardsJson]  = excludedEnc
                it[SavedResultSnapshots.uncheckedLinesJson] = uncheckedEnc
                it[SavedResultSnapshots.pinnedListingsJson] = pinnedEnc
            }
        }
        refresh()
    }

    // Overwrites an existing snapshot in place (same id), used when the user saves under a
    // name that already matches another saved result and confirms replacing it.
    suspend fun overwrite(
        id: Int,
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
        excludedCards: Set<String>,
        uncheckedLines: Set<String>,
        pinnedListings: Map<String, String>,
    ): Unit = withContext(Dispatchers.IO) {
        val lid = id; val n = name; val d = description; val now = System.currentTimeMillis()
        val cardsEnc    = json.encodeToString(cards)
        val resultsEnc  = json.encodeToString(results)
        val excludedEnc = json.encodeToString(excludedCards.toList())
        val uncheckedEnc = json.encodeToString(uncheckedLines.toList())
        val pinnedEnc   = json.encodeToString(pinnedListings)
        transaction {
            SavedResultSnapshots.update({ Op.build { SavedResultSnapshots.id eq lid } }) {
                it[SavedResultSnapshots.name]               = n
                it[SavedResultSnapshots.description]        = d
                it[SavedResultSnapshots.savedAt]            = now
                it[SavedResultSnapshots.cardCount]          = cards.size
                it[SavedResultSnapshots.cardsJson]          = cardsEnc
                it[SavedResultSnapshots.resultsJson]        = resultsEnc
                it[SavedResultSnapshots.excludedCardsJson]  = excludedEnc
                it[SavedResultSnapshots.uncheckedLinesJson] = uncheckedEnc
                it[SavedResultSnapshots.pinnedListingsJson] = pinnedEnc
            }
        }
        refresh()
    }

    suspend fun load(id: Int): LoadedResultSnapshot? = withContext(Dispatchers.IO) {
        val lid = id
        val row = transaction {
            SavedResultSnapshots.selectAll()
                .where { SavedResultSnapshots.id eq lid }
                .firstOrNull()
        } ?: return@withContext null
        LoadedResultSnapshot(
            cards          = json.decodeFromString(row[SavedResultSnapshots.cardsJson]),
            results        = json.decodeFromString(row[SavedResultSnapshots.resultsJson]),
            excludedCards  = json.decodeFromString<List<String>>(row[SavedResultSnapshots.excludedCardsJson] ?: "[]").toSet(),
            uncheckedLines = json.decodeFromString<List<String>>(row[SavedResultSnapshots.uncheckedLinesJson] ?: "[]").toSet(),
            pinnedListings = json.decodeFromString(row[SavedResultSnapshots.pinnedListingsJson] ?: "{}"),
        )
    }

    suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        val lid = id
        transaction {
            SavedResultSnapshots.deleteWhere { Op.build { SavedResultSnapshots.id eq lid } }
        }
        refresh()
    }
}
