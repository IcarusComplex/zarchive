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
                    )
                }
        }
    }

    suspend fun save(
        name: String,
        description: String,
        cards: List<String>,
        results: List<SearchResult>,
    ): Unit = withContext(Dispatchers.IO) {
        val n = name; val d = description; val now = System.currentTimeMillis()
        val cardsEnc   = json.encodeToString(cards)
        val resultsEnc = json.encodeToString(results)
        transaction {
            SavedResultSnapshots.insert {
                it[SavedResultSnapshots.name]        = n
                it[SavedResultSnapshots.description] = d
                it[SavedResultSnapshots.savedAt]     = now
                it[SavedResultSnapshots.cardCount]   = cards.size
                it[SavedResultSnapshots.cardsJson]   = cardsEnc
                it[SavedResultSnapshots.resultsJson] = resultsEnc
            }
        }
        refresh()
    }

    suspend fun load(id: Int): Pair<List<String>, List<SearchResult>>? = withContext(Dispatchers.IO) {
        val lid = id
        val row = transaction {
            SavedResultSnapshots.selectAll()
                .where { SavedResultSnapshots.id eq lid }
                .firstOrNull()
        } ?: return@withContext null
        val cards   = json.decodeFromString<List<String>>(row[SavedResultSnapshots.cardsJson])
        val results = json.decodeFromString<List<SearchResult>>(row[SavedResultSnapshots.resultsJson])
        cards to results
    }

    suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        val lid = id
        transaction {
            SavedResultSnapshots.deleteWhere { Op.build { SavedResultSnapshots.id eq lid } }
        }
        refresh()
    }
}
