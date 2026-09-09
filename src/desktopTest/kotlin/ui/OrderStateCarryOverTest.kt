package ui

import data.CollectionRows
import data.DesktopCollectionRepo
import data.DesktopSearchListRepo
import data.DesktopSearchResultRepo
import data.SearchListCards
import data.SearchLists
import data.SavedResultSnapshots
import data.Settings
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Order-list state (pins, per-card exclusions, unchecked lines) used to survive a brand-new search
// untouched -- only clearAll() reset it. A pin left over from an earlier list then silently governed
// the next search: candidatePool() considers ONLY the pinned listing, so a pin whose URL isn't in
// the new results empties that card's pool and the card reads as "not available anywhere", with
// nothing on screen pointing at the pin. This pins down what a new search keeps and what it drops.
class OrderStateCarryOverTest {

    private fun viewModel(): SearchViewModel {
        val tempDb = File.createTempFile("zarchive-test-carryover-", "")
        tempDb.delete()
        Database.connect("jdbc:h2:file:${tempDb.absolutePath};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(Settings, SearchLists, SearchListCards, SavedResultSnapshots, CollectionRows)
        }
        return SearchViewModel(
            searchListRepo = DesktopSearchListRepo(),
            searchResultRepo = DesktopSearchResultRepo(),
            collectionRepo = DesktopCollectionRepo(),
            platformActions = ui.PlatformActions(),
        )
    }

    @Test
    fun `a new search keeps order state for cards still in the list and drops the rest`() {
        val vm = viewModel()
        vm.pinnedListings["Bolt"] = "https://example.com/bolt-pin"
        vm.pinnedListings["Spear"] = "https://example.com/spear-pin"
        vm.excludedCards["Bolt"] = Unit
        vm.excludedCards["Spear"] = Unit
        vm.uncheckedOrderLines["https://example.com/bolt-pin"] = Unit

        // Searching a list that no longer contains "Spear".
        vm.dropOrderStateOutsideCardSet(listOf("Bolt", "Counterspell"))

        assertEquals(mapOf("Bolt" to "https://example.com/bolt-pin"), vm.pinnedListings.toMap())
        assertEquals(setOf("Bolt"), vm.excludedCards.keys.toSet())
        // Keyed by listing URL -- every one of them belongs to the results the search just cleared.
        assertTrue(vm.uncheckedOrderLines.isEmpty())
    }

    @Test
    fun `re-searching the same list keeps every pin`() {
        val vm = viewModel()
        vm.pinnedListings["Bolt"] = "https://example.com/bolt-pin"
        vm.excludedCards["Bolt"] = Unit

        vm.dropOrderStateOutsideCardSet(listOf("Bolt"))

        assertEquals(mapOf("Bolt" to "https://example.com/bolt-pin"), vm.pinnedListings.toMap())
        assertEquals(setOf("Bolt"), vm.excludedCards.keys.toSet())
    }
}
