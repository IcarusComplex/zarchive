package ui

import data.CfThrottleRules
import data.CollectionRows
import data.DesktopCollectionRepo
import data.DesktopSearchListRepo
import data.DesktopSearchResultRepo
import data.SearchListCards
import data.SearchLists
import data.SavedResultSnapshots
import data.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

// Exercises the REAL SearchViewModel code path (not just the repo layer) to reproduce a
// live-reported bug: search "4x Lightning Bolt" -> order list correctly shows 4 -> save -> reload
// -> quantity silently drops to 1. Every layer read individually looked correct, so this drives
// the actual VM methods (search-equivalent state population, saveCurrentResults, loadSavedResult)
// against a real throwaway DB to catch anything a static read would miss.
class SaveQuantityEndToEndTest {

    @Test
    fun `quantities survive save then load through the real ViewModel`() = runBlocking {
        val tempDb = File.createTempFile("zarchive-test-vm-", "")
        tempDb.delete()
        Database.connect("jdbc:h2:file:${tempDb.absolutePath};DB_CLOSE_DELAY=-1", driver = "org.h2.Driver")
        transaction {
            SchemaUtils.create(Settings, SearchLists, SearchListCards, CfThrottleRules, SavedResultSnapshots, CollectionRows)
        }

        val vm = SearchViewModel(
            searchListRepo = DesktopSearchListRepo(),
            searchResultRepo = DesktopSearchResultRepo(),
            collectionRepo = DesktopCollectionRepo(),
            platformActions = ui.PlatformActions(),
        )

        // Mimic what search() does after parsing "4x Lightning Bolt", without a real network call.
        vm.query = "4x Lightning Bolt"
        vm.searchedCards = listOf("Lightning Bolt")
        vm.cardQuantities = mapOf("Lightning Bolt" to 4)
        vm.results.add(
            data.SearchResult(
                store = "Test Store", card = "Lightning Bolt", title = "Lightning Bolt",
                priceZar = 10.0, available = true, url = "http://x", note = "in stock",
            )
        )

        vm.saveCurrentResults("Test Save", "")
        delay(500) // save() launches on Dispatchers.IO

        val entry = vm.savedResults.value.first()

        // Simulate a fresh app session: wipe live VM state, then load the saved result back.
        vm.cardQuantities = emptyMap()
        vm.searchedCards = emptyList()
        vm.query = ""

        vm.loadSavedResult(entry)
        delay(1000) // load() + image resolution launches on scope.launch

        assertEquals(mapOf("Lightning Bolt" to 4), vm.cardQuantities)
        assertEquals("4x Lightning Bolt", vm.query)
    }
}
