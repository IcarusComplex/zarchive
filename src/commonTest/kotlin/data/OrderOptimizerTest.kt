package data

import kotlin.test.*

class OrderOptimizerTest {

    private fun result(
        card: String,
        store: String,
        price: Double?,
        title: String = card,
        available: Boolean? = true,
        stockQty: Int? = null,
        url: String = "https://example.com/$store",
    ) = SearchResult(
        store = store,
        card = card,
        title = title,
        priceZar = price,
        available = available,
        url = url,
        note = "",
        stockQty = stockQty,
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
        assertEquals(listOf(OrderShortfall("Bolt", needed = 1, found = 0)), plan.uncoveredCards)
    }

    @Test fun `cheapest puts card with no listings in uncovered`() {
        val plan = cheapestPlan(listOf("Bolt", "Spear"), emptyList())
        assertTrue(plan.storeOrders.isEmpty())
        assertEquals(
            listOf(OrderShortfall("Bolt", needed = 1, found = 0), OrderShortfall("Spear", needed = 1, found = 0)),
            plan.uncoveredCards,
        )
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
        assertEquals(listOf(OrderShortfall("Spear", needed = 1, found = 0)), plan.uncoveredCards)
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

    // ── quantity-aware cheapestPlan ──────────────────────────────────────────

    @Test fun `cheapest splits quantity across listings when one is insufficient`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = 2),
            result("Bolt", "StoreB", 8.0, stockQty = 2),
        )
        val plan = cheapestPlan(listOf("Bolt"), results, quantities = mapOf("Bolt" to 4))
        assertTrue(plan.uncoveredCards.isEmpty())
        val lines = plan.storeOrders.flatMap { it.lines }
        assertEquals(2, lines.size)
        assertEquals(4, lines.sumOf { it.qty })
        assertEquals(2, lines.first { it.listing.store == "StoreA" }.qty)
        assertEquals(2, lines.first { it.listing.store == "StoreB" }.qty)
    }

    @Test fun `cheapest treats unconfirmed stock as 1, not unlimited`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = null),
        )
        val plan = cheapestPlan(listOf("Bolt"), results, quantities = mapOf("Bolt" to 26))
        assertEquals(listOf(OrderShortfall("Bolt", needed = 26, found = 1)), plan.uncoveredCards)
        val lines = plan.storeOrders.flatMap { it.lines }
        assertEquals(1, lines.size)
        assertEquals(1, lines[0].qty)
    }

    @Test fun `cheapest reports shortfall when total stock is insufficient`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = 2),
            result("Bolt", "StoreB", 8.0, stockQty = 2),
        )
        val plan = cheapestPlan(listOf("Bolt"), results, quantities = mapOf("Bolt" to 5))
        assertEquals(listOf(OrderShortfall("Bolt", needed = 5, found = 4)), plan.uncoveredCards)
        val lines = plan.storeOrders.flatMap { it.lines }
        assertEquals(4, lines.sumOf { it.qty })
    }

    // ── quantity-aware fewestStoresPlan ──────────────────────────────────────

    @Test fun `fewest prefers single store that covers full quantity`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = 5),
            result("Bolt", "StoreB", 3.0, stockQty = 1),
        )
        val plan = fewestStoresPlan(listOf("Bolt"), results, quantities = mapOf("Bolt" to 4))
        assertEquals(1, plan.storeCount)
        assertEquals("StoreA", plan.storeOrders[0].store)
        assertEquals(4, plan.storeOrders[0].itemCount)
        assertTrue(plan.uncoveredCards.isEmpty())
    }

    @Test fun `fewest splits across stores when none alone covers the quantity`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = 2),
            result("Bolt", "StoreB", 8.0, stockQty = 2),
        )
        val plan = fewestStoresPlan(listOf("Bolt"), results, quantities = mapOf("Bolt" to 4))
        assertEquals(2, plan.storeCount)
        assertEquals(4, plan.itemCount)
        assertTrue(plan.uncoveredCards.isEmpty())
    }

    // ── pinned listings + topUpPinnedShortfalls ──────────────────────────────

    @Test fun `pinned listing without top-up leaves shortfall when insufficient`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = 2, url = "https://example.com/pin"),
            result("Bolt", "StoreB", 8.0, stockQty = null),
        )
        val plan = cheapestPlan(
            listOf("Bolt"), results,
            pinnedListings = mapOf("Bolt" to "https://example.com/pin"),
            quantities = mapOf("Bolt" to 4),
            topUpPinnedShortfalls = false,
        )
        assertEquals(listOf(OrderShortfall("Bolt", needed = 4, found = 2)), plan.uncoveredCards)
        val lines = plan.storeOrders.flatMap { it.lines }
        assertEquals(1, lines.size)
        assertEquals("StoreA", lines[0].listing.store)
        assertEquals(2, lines[0].qty)
    }

    @Test fun `pinned listing with top-up sources the remainder elsewhere`() {
        val results = listOf(
            result("Bolt", "StoreA", 5.0, stockQty = 2, url = "https://example.com/pin"),
            result("Bolt", "StoreB", 8.0, stockQty = 2),
        )
        val plan = cheapestPlan(
            listOf("Bolt"), results,
            pinnedListings = mapOf("Bolt" to "https://example.com/pin"),
            quantities = mapOf("Bolt" to 4),
            topUpPinnedShortfalls = true,
        )
        assertTrue(plan.uncoveredCards.isEmpty())
        val lines = plan.storeOrders.flatMap { it.lines }
        assertEquals(2, lines.size)
        assertEquals(2, lines.first { it.listing.store == "StoreA" }.qty)
        assertEquals(2, lines.first { it.listing.store == "StoreB" }.qty)
    }
}
