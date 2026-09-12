package data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The point of reporting delivery on every strategy: the three plans are meant to be compared,
 * and on a spread-out basket the cheapest-cards plan wins on cards and loses on all-in. If the
 * totals row only showed all-in for Balanced there was nothing to compare it against.
 */
class AllInComparisonTest {

    private fun result(card: String, store: String, price: Double) =
        SearchResult(store = store, card = card, title = card, priceZar = price,
            available = true, url = "https://$store.example.com/$card", note = "")

    // Three cards. One store has all three at a small premium; three other stores each undercut
    // it on a single card. Cheapest-cards therefore opens three parcels to save R15 of cards.
    private val results = listOf(
        result("Bolt", "OneStop", 100.0),
        result("Spear", "OneStop", 100.0),
        result("Sword", "OneStop", 100.0),
        result("Bolt", "CheapA", 95.0),
        result("Spear", "CheapB", 95.0),
        result("Sword", "CheapC", 95.0),
    )
    private val cards = listOf("Bolt", "Spear", "Sword")

    @Test fun `cheapest wins on cards and loses on all-in`() {
        val cheapest = cheapestPlan(cards, results)
        val balanced = balancedPlan(cards, results)

        assertEquals(285.0, cheapest.grandTotal, 0.001)
        assertEquals(3, cheapest.storeCount)
        assertEquals(300.0, balanced.grandTotal, 0.001)
        assertEquals(1, balanced.storeCount)

        assertTrue(cheapest.grandTotal < balanced.grandTotal, "cheapest should win on card spend")
        assertTrue(
            balanced.allInTotal < cheapest.allInTotal,
            "balanced should win on all-in: ${balanced.allInTotal} vs ${cheapest.allInTotal}",
        )
    }

    @Test fun `fewest packages reports an all-in figure too`() {
        val fewest = fewestStoresPlan(cards, results)
        assertEquals(1, fewest.storeCount)
        assertEquals(DEFAULT_DELIVERY_ZAR, fewest.deliveryTotal, 0.001)
        assertEquals(fewest.grandTotal + DEFAULT_DELIVERY_ZAR, fewest.allInTotal, 0.001)
    }
}
