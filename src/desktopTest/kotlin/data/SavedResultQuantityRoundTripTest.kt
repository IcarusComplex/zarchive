package data

import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Deliberately connects its own throwaway H2 file (never touches AppDatabase.init()/the real
// ~/.zarchive DB) to empirically verify cardQuantities round-trips through DesktopSearchResultRepo.
class SavedResultQuantityRoundTripTest {

    @Test
    fun `saved result quantities survive a save-then-load round trip`() = runBlocking {
        val tempDb = File.createTempFile("zarchive-test-", "")
        tempDb.delete()
        Database.connect("jdbc:h2:file:${tempDb.absolutePath};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction { SchemaUtils.create(SavedResultSnapshots) }

        val repo = DesktopSearchResultRepo()
        roundTrip(repo)
    }

    // Mirrors SavedResultSnapshots as it looked before card_quantities_json (and syncId/deleted/
    // deletedAt) existed -- same Exposed Table DSL the real old schema was actually created with,
    // so the generated DDL/metadata matches production exactly instead of hand-rolled raw SQL.
    private object OldSavedResultSnapshots : org.jetbrains.exposed.sql.Table("saved_result_snapshots") {
        val id                 = integer("id").autoIncrement()
        val name               = text("name")
        val description        = text("description")
        val savedAt            = long("saved_at")
        val cardCount          = integer("card_count")
        val cardsJson          = text("cards_json")
        val resultsJson        = text("results_json")
        val excludedCardsJson  = text("excluded_cards_json").nullable()
        val uncheckedLinesJson = text("unchecked_lines_json").nullable()
        val pinnedListingsJson = text("pinned_listings_json").nullable()
        override val primaryKey = PrimaryKey(id)
    }

    @Test
    fun `quantities persist correctly after migrating from a pre-quantities table`() = runBlocking {
        val tempDb = File.createTempFile("zarchive-test-migration-", "")
        tempDb.delete()
        Database.connect("jdbc:h2:file:${tempDb.absolutePath};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        // Simulate an existing install's table as it looked before card_quantities_json existed.
        transaction { SchemaUtils.create(OldSavedResultSnapshots) }
        // Now run the app's real startup migration against that pre-existing table.
        transaction { SchemaUtils.createMissingTablesAndColumns(SavedResultSnapshots) }

        val repo = DesktopSearchResultRepo()
        roundTrip(repo)
    }

    private suspend fun roundTrip(repo: DesktopSearchResultRepo) {
        val cards = listOf("Lightning Bolt", "Hare Apparent")
        val quantities = mapOf("Lightning Bolt" to 4, "Hare Apparent" to 30)
        val results = listOf(
            SearchResult(store = "Test Store", card = "Lightning Bolt", title = "Lightning Bolt", priceZar = 10.0, available = true, url = "http://x", note = "in stock"),
        )

        repo.save(
            name = "Test", description = "", cards = cards, results = results,
            excludedCards = emptySet(), uncheckedLines = emptySet(), pinnedListings = emptyMap(),
            cardQuantities = quantities,
        )

        val entry = repo.entries.value.first()
        val loaded = repo.load(entry.id)

        assertEquals(quantities, loaded?.cardQuantities)
    }
}
