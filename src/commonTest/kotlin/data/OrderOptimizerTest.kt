package data

import kotlin.test.*

class OrderOptimizerTest {

    private fun result(
        card: String,
        store: String,
        price: Double?,
        title: String = card,
        available: Boolean? = true,
    ) = SearchResult(
        store = store,
        card = card,
        title = title,
        priceZar = price,
        available = available,
        url = "https://example.com/$store",
        note = "",
    )

    // ── cheapestPlan ─────────────────────────────────────────────────────────

    @Test fun `cheapest picks lowest price per card`() {
        val results = listOf(
            result("Bolt", "StoreA", 10.0),
            result("Bolt", "StoreB", 8.0),
        )
        val plan = cheapestPlan(listOf("Bolt"), results)
        assertEquals(1, plan.storeOrders.size)
        val line = plan.storeOrders[0].lines[0]
        assertEquals(8.0, line.listing.priceZar)
        assertEquals("StoreB", line.listing.store)
    }

    @Test fun `cheapest groups multiple cards from same store`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Spear", "StoreA", 15.0),
        )
        val plan = cheapestPlan(listOf("Bolt", "Spear"), results)
        assertEquals(1, plan.storeOrders.size)
        assertEquals(2, plan.storeOrders[0].itemCount)
        assertEquals(20.0, plan.grandTotal, 0.001)
    }

    @Test fun `cheapest splits cards across stores when cheaper`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Bolt", "StoreB", 15.0),
            result("Spear", "StoreA", 50.0),
            result("Spear", "StoreB", 10.0),
        )
        val plan = cheapestPlan(listOf("Bolt", "Spear"), results)
        // Bolt cheapest at StoreA (5), Spear cheapest at StoreB (10)
        assertEquals(2, plan.storeOrders.size)
        assertEquals(15.0, plan.grandTotal, 0.001)
    }

    @Test fun `cheapest puts out-of-stock card in uncovered`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, available = false),
        )
        val plan = cheapestPlan(listOf("Bolt"), results)
        assertTrue(plan.storeOrders.isEmpty())
        assertEquals(listOf("Bolt"), plan.uncoveredCards)
    }

    @Test fun `cheapest puts card with no listings in uncovered`() {
        val plan = cheapestPlan(listOf("Bolt", "Spear"), emptyList())
        assertTrue(plan.storeOrders.isEmpty())
        assertEquals(listOf("Bolt", "Spear"), plan.uncoveredCards)
    }

    @Test fun `cheapest skips null-title placeholder rows`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0).copy(title = null),
            result("Bolt", "StoreB", 8.0),
        )
        val plan = cheapestPlan(listOf("Bolt"), results)
        assertEquals("StoreB", plan.storeOrders[0].store)
    }

    @Test fun `cheapest null price sorts last`() {
        val results = listOf(
            result("Bolt", "StoreA", null),
            result("Bolt", "StoreB", 10.0),
        )
        val plan = cheapestPlan(listOf("Bolt"), results)
        assertEquals("StoreB", plan.storeOrders[0].store)
    }

    // ── fewestStoresPlan ──────────────────────────────────────────────────────

    @Test fun `fewest uses one store when it covers everything`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Spear", "StoreA", 10.0),
            result("Bolt", "StoreB", 3.0),
        )
        val plan = fewestStoresPlan(listOf("Bolt", "Spear"), results)
        // StoreA covers both; StoreB only covers Bolt → StoreA wins (fewest stores)
        assertEquals(1, plan.storeCount)
        assertEquals("StoreA", plan.storeOrders[0].store)
    }

    @Test fun `fewest falls back to two stores when no single covers all`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Spear", "StoreB", 10.0),
        )
        val plan = fewestStoresPlan(listOf("Bolt", "Spear"), results)
        assertEquals(2, plan.storeCount)
        assertTrue(plan.uncoveredCards.isEmpty())
    }

    @Test fun `fewest sources each card from cheapest picked store`() {
        val results = listOf(
            result("Bolt", "StoreA", 10.0),
            result("Bolt", "StoreA", 5.0).copy(url = "https://example.com/StoreA-2"),
            result("Spear", "StoreA", 20.0),
        )
        val plan = fewestStoresPlan(listOf("Bolt", "Spear"), results)
        val boltLine = plan.storeOrders[0].lines.first { it.card == "Bolt" }
        assertEquals(5.0, boltLine.listing.priceZar)
    }

    @Test fun `fewest puts unavailable cards in uncovered`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Spear", "StoreA", 10.0, available = false),
        )
        val plan = fewestStoresPlan(listOf("Bolt", "Spear"), results)
        assertEquals(listOf("Spear"), plan.uncoveredCards)
        assertEquals(1, plan.storeOrders[0].itemCount)
    }

    @Test fun `fewest empty results all uncovered`() {
        val plan = fewestStoresPlan(listOf("Bolt", "Spear"), emptyList())
        assertTrue(plan.storeOrders.isEmpty())
        assertEquals(2, plan.uncoveredCards.size)
    }

    // ── OrderPlan aggregates ─────────────────────────────────────────────────

    @Test fun `grand total sums across stores`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Spear", "StoreB", 10.0),
        )
        val plan = cheapestPlan(listOf("Bolt", "Spear"), results)
        assertEquals(15.0, plan.grandTotal, 0.001)
    }

    @Test fun `item count is total lines across all stores`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0),
            result("Spear", "StoreB", 10.0),
            result("Counterspell", "StoreA", 3.0),
        )
        val plan = cheapestPlan(listOf("Bolt", "Spear", "Counterspell"), results)
        assertEquals(3, plan.itemCount)
    }
}
