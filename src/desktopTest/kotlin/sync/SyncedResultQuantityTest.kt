package sync

import data.SearchResult
import data.SyncedResultRecord
import kotlin.test.Test
import kotlin.test.assertEquals

// Regression test for a real data-loss bug (found live, Aug 2026): SyncedResult (the Google Drive
// wire format) had no cardQuantities field, so every sync cycle round-tripped a saved result
// through Drive and silently wiped its quantities back to empty -- even on a single device, since
// toSynced()/toSyncedResultRecord() are applied unconditionally to the locally merged result and
// written straight back via applyRemote(). This directly exercises that conversion pair.
class SyncedResultQuantityTest {

    @Test
    fun `cardQuantities survives the local-to-wire-to-local round trip`() {
        val local = SyncedResultRecord(
            id = 1,
            syncId = "abc",
            name = "Test",
            description = "",
            savedAt = 1000L,
            cards = listOf("Lightning Bolt", "Hare Apparent"),
            results = listOf(
                SearchResult(store = "Store", card = "Lightning Bolt", title = "Lightning Bolt",
                    priceZar = 10.0, available = true, url = "http://x", note = "in stock"),
            ),
            excludedCards = emptySet(),
            uncheckedLines = emptySet(),
            pinnedListings = emptyMap(),
            cardQuantities = mapOf("Lightning Bolt" to 4, "Hare Apparent" to 30),
        )

        val roundTripped = local.toSynced().toSyncedResultRecord()

        assertEquals(local.cardQuantities, roundTripped.cardQuantities)
    }
}
