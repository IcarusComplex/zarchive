package data

import kotlin.test.*

class ImportExportTest {

    private fun savedList(id: Int, syncId: String?, name: String, cards: List<String> = listOf("Card A")) =
        SavedSearchList(id = id, name = name, cards = cards, updatedAt = 0L, syncId = syncId)

    private fun savedResultEntry(id: Int, syncId: String?, name: String) =
        SavedResultEntry(id = id, name = name, description = "", savedAt = 0L, cardCount = 0, cards = emptyList(), syncId = syncId)

    private fun exportedList(syncId: String, name: String, cards: List<String> = listOf("Card A")) =
        ExportedList(syncId = syncId, name = name, cards = cards, updatedAt = 0L)

    private fun exportedResult(syncId: String, name: String) =
        ExportedResult(syncId = syncId, name = name, description = "", savedAt = 0L, cards = emptyList(), results = emptyList())

    private fun result(url: String, card: String = "Card", store: String = "Store") =
        SearchResult(store = store, card = card, title = card, priceZar = 1.0, available = true, url = url, note = "")

    // ── buildImportPlan ──────────────────────────────────────────────────────

    @Test fun `list with unknown syncId is a new insert`() {
        val plan = buildImportPlan(
            bundle = ExportBundle(lists = listOf(exportedList("new-id", "Fresh"))),
            localLists = listOf(savedList(1, "existing-id", "Existing")),
            localResults = emptyList(),
        )
        assertEquals(1, plan.newLists.size)
        assertTrue(plan.listConflicts.isEmpty())
        assertFalse(plan.hasConflicts)
    }

    @Test fun `list with matching syncId is a conflict`() {
        val local = savedList(1, "shared-id", "Existing")
        val incoming = exportedList("shared-id", "Renamed")
        val plan = buildImportPlan(
            bundle = ExportBundle(lists = listOf(incoming)),
            localLists = listOf(local),
            localResults = emptyList(),
        )
        assertTrue(plan.newLists.isEmpty())
        assertEquals(1, plan.listConflicts.size)
        assertEquals(local, plan.listConflicts[0].local)
        assertEquals(incoming, plan.listConflicts[0].incoming)
    }

    @Test fun `result with unknown syncId is a new insert`() {
        val plan = buildImportPlan(
            bundle = ExportBundle(results = listOf(exportedResult("new-id", "Fresh"))),
            localLists = emptyList(),
            localResults = listOf(savedResultEntry(1, "existing-id", "Existing")),
        )
        assertEquals(1, plan.newResults.size)
        assertTrue(plan.resultConflicts.isEmpty())
    }

    @Test fun `result with matching syncId is a conflict`() {
        val local = savedResultEntry(1, "shared-id", "Existing")
        val incoming = exportedResult("shared-id", "Renamed")
        val plan = buildImportPlan(
            bundle = ExportBundle(results = listOf(incoming)),
            localLists = emptyList(),
            localResults = listOf(local),
        )
        assertEquals(1, plan.resultConflicts.size)
    }

    @Test fun `null local syncId never matches`() {
        val plan = buildImportPlan(
            bundle = ExportBundle(lists = listOf(exportedList("some-id", "Incoming"))),
            localLists = listOf(savedList(1, null, "NoSyncIdYet")),
            localResults = emptyList(),
        )
        assertEquals(1, plan.newLists.size)
        assertTrue(plan.listConflicts.isEmpty())
    }

    @Test fun `empty bundle produces empty plan`() {
        val plan = buildImportPlan(ExportBundle(), emptyList(), emptyList())
        assertTrue(plan.isEmpty)
    }

    // ── mergeCardLists ───────────────────────────────────────────────────────

    @Test fun `merge card lists dedupes case-insensitively keeping local order first`() {
        val merged = mergeCardLists(listOf("Bolt", "Shock"), listOf("shock", "Counterspell"))
        assertEquals(listOf("Bolt", "Shock", "Counterspell"), merged)
    }

    @Test fun `merge card lists with no overlap concatenates`() {
        val merged = mergeCardLists(listOf("Bolt"), listOf("Shock"))
        assertEquals(listOf("Bolt", "Shock"), merged)
    }

    // ── mergeResultData ──────────────────────────────────────────────────────

    @Test fun `merge result data dedupes listings by url and unions everything else`() {
        val shared = result("https://store/a", card = "Bolt")
        val localOnly = result("https://store/b", card = "Shock")
        val incomingOnly = result("https://store/c", card = "Counterspell")
        val merged = mergeResultData(
            localCards = listOf("Bolt", "Shock"),
            incomingCards = listOf("Bolt", "Counterspell"),
            localResults = listOf(shared, localOnly),
            incomingResults = listOf(shared.copy(priceZar = 99.0), incomingOnly),
            localExcluded = setOf("Shock"),
            incomingExcluded = setOf("Counterspell"),
            localUnchecked = setOf("line1"),
            incomingUnchecked = setOf("line2"),
            localPinned = mapOf("Bolt" to "https://store/a"),
            incomingPinned = mapOf("Counterspell" to "https://store/c"),
        )
        assertEquals(listOf("Bolt", "Shock", "Counterspell"), merged.cards)
        assertEquals(3, merged.results.size)
        // local's copy of the shared url wins on dedupe (kept first)
        assertEquals(1.0, merged.results.first { it.url == "https://store/a" }.priceZar)
        assertEquals(setOf("Shock", "Counterspell"), merged.excludedCards)
        assertEquals(setOf("line1", "line2"), merged.uncheckedLines)
        assertEquals(mapOf("Bolt" to "https://store/a", "Counterspell" to "https://store/c"), merged.pinnedListings)
    }
}
