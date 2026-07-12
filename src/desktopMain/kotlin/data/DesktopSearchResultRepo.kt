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

// SavedResultEntry/LoadedResultSnapshot now live in commonMain/kotlin/data/SearchResultRepo.kt
// (shared interface) — same `data` package, no import needed here.

class DesktopSearchResultRepo : SearchResultRepo {
    private val json = Json { ignoreUnknownKeys = true }

    private val _entries = MutableStateFlow<List<SavedResultEntry>>(emptyList())
    override val entries: StateFlow<List<SavedResultEntry>> = _entries.asStateFlow()

    init { refresh() }

    private fun refresh() {
        _entries.value = transaction {
            SavedResultSnapshots.selectAll()
                .where { SavedResultSnapshots.deleted eq false }
                .orderBy(SavedResultSnapshots.savedAt, SortOrder.DESC)
                .map { row ->
                    SavedResultEntry(
                        id          = row[SavedResultSnapshots.id],
                        name        = row[SavedResultSnapshots.name],
                        description = row[SavedResultSnapshots.description],
                        savedAt     = row[SavedResultSnapshots.savedAt],
                        cardCount   = row[SavedResultSnapshots.cardCount],
                        cards       = json.decodeFromString(row[SavedResultSnapshots.cardsJson]),
                        syncId      = row[SavedResultSnapshots.syncId],
                    )
                }
        }
    }

    override suspend fun save(
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
                it[SavedResultSnapshots.syncId]             = java.util.UUID.randomUUID().toString()
            }
        }
        refresh()
    }

    // Overwrites an existing snapshot in place (same id), used when the user saves under a
    // name that already matches another saved result and confirms replacing it.
    override suspend fun overwrite(
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

    override suspend fun load(id: Int): LoadedResultSnapshot? = withContext(Dispatchers.IO) {
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

    // Soft-delete (tombstone) -- see the matching comment on DesktopSearchListRepo.delete().
    override suspend fun delete(id: Int): Unit = withContext(Dispatchers.IO) {
        val lid = id; val now = System.currentTimeMillis()
        transaction {
            SavedResultSnapshots.update({ Op.build { SavedResultSnapshots.id eq lid } }) {
                it[SavedResultSnapshots.deleted]   = true
                it[SavedResultSnapshots.deletedAt] = now
                it[SavedResultSnapshots.savedAt]   = now
            }
        }
        refresh()
    }

    override suspend fun allForSync(): List<SyncedResultRecord> = withContext(Dispatchers.IO) {
        transaction {
            SavedResultSnapshots.selectAll().map { row ->
                SyncedResultRecord(
                    id             = row[SavedResultSnapshots.id],
                    syncId         = row[SavedResultSnapshots.syncId],
                    name           = row[SavedResultSnapshots.name],
                    description    = row[SavedResultSnapshots.description],
                    savedAt        = row[SavedResultSnapshots.savedAt],
                    cards          = json.decodeFromString(row[SavedResultSnapshots.cardsJson]),
                    results        = json.decodeFromString(row[SavedResultSnapshots.resultsJson]),
                    excludedCards  = json.decodeFromString<List<String>>(row[SavedResultSnapshots.excludedCardsJson] ?: "[]").toSet(),
                    uncheckedLines = json.decodeFromString<List<String>>(row[SavedResultSnapshots.uncheckedLinesJson] ?: "[]").toSet(),
                    pinnedListings = json.decodeFromString(row[SavedResultSnapshots.pinnedListingsJson] ?: "{}"),
                    deleted        = row[SavedResultSnapshots.deleted],
                    deletedAt      = row[SavedResultSnapshots.deletedAt],
                )
            }
        }
    }

    override suspend fun applyRemote(record: SyncedResultRecord): Unit = withContext(Dispatchers.IO) {
        val sid = requireNotNull(record.syncId) { "applyRemote requires a non-null syncId" }
        val cardsEnc     = json.encodeToString(record.cards)
        val resultsEnc   = json.encodeToString(record.results)
        val excludedEnc  = json.encodeToString(record.excludedCards.toList())
        val uncheckedEnc = json.encodeToString(record.uncheckedLines.toList())
        val pinnedEnc    = json.encodeToString(record.pinnedListings)
        transaction {
            val existingId = SavedResultSnapshots.selectAll()
                .where { SavedResultSnapshots.syncId eq sid }
                .firstOrNull()?.get(SavedResultSnapshots.id)
            if (existingId == null) {
                SavedResultSnapshots.insert {
                    it[SavedResultSnapshots.name]               = record.name
                    it[SavedResultSnapshots.description]        = record.description
                    it[SavedResultSnapshots.savedAt]            = record.savedAt
                    it[SavedResultSnapshots.cardCount]          = record.cards.size
                    it[SavedResultSnapshots.cardsJson]          = cardsEnc
                    it[SavedResultSnapshots.resultsJson]        = resultsEnc
                    it[SavedResultSnapshots.excludedCardsJson]  = excludedEnc
                    it[SavedResultSnapshots.uncheckedLinesJson] = uncheckedEnc
                    it[SavedResultSnapshots.pinnedListingsJson] = pinnedEnc
                    it[SavedResultSnapshots.syncId]             = sid
                    it[SavedResultSnapshots.deleted]            = record.deleted
                    it[SavedResultSnapshots.deletedAt]          = record.deletedAt
                }
            } else {
                SavedResultSnapshots.update({ Op.build { SavedResultSnapshots.id eq existingId } }) {
                    it[SavedResultSnapshots.name]               = record.name
                    it[SavedResultSnapshots.description]        = record.description
                    it[SavedResultSnapshots.savedAt]            = record.savedAt
                    it[SavedResultSnapshots.cardCount]          = record.cards.size
                    it[SavedResultSnapshots.cardsJson]          = cardsEnc
                    it[SavedResultSnapshots.resultsJson]        = resultsEnc
                    it[SavedResultSnapshots.excludedCardsJson]  = excludedEnc
                    it[SavedResultSnapshots.uncheckedLinesJson] = uncheckedEnc
                    it[SavedResultSnapshots.pinnedListingsJson] = pinnedEnc
                    it[SavedResultSnapshots.deleted]            = record.deleted
                    it[SavedResultSnapshots.deletedAt]          = record.deletedAt
                }
            }
        }
        refresh()
    }
}
