package data

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BalancedPlanTest {

    private fun result(
        card: String,
        store: String,
        price: Double?,
        title: String = card,
        available: Boolean? = true,
        stockQty: Int? = null,
        url: String = "https://example.com/$store/$card",
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

    // ── the trade-off itself ─────────────────────────────────────────────────

    @Test fun `a saving smaller than a parcel is not worth a second store`() {
        val results = listOf(
            result("Bolt", "StoreA", 100.0),
            result("Spear", "StoreA", 100.0),
            result("Spear", "StoreB", 40.0),   // R60 cheaper, but a whole R110 parcel away
        )
        val plan = balancedPlan(listOf("Bolt", "Spear"), results)
        assertEquals(1, plan.storeCount)
        assertEquals("StoreA", plan.storeOrders.single().store)
        assertEquals(310.0, plan.allInTotal, 0.001)   // 200 cards + 110 delivery

        // ...which is exactly where it differs from cheapest, who takes the R60 and eats the parcel.
        val cheapest = cheapestPlan(listOf("Bolt", "Spear"), results)
        assertEquals(2, cheapest.storeCount)
        assertEquals(140.0, cheapest.grandTotal, 0.001)
    }

    @Test fun `a saving bigger than a parcel is worth a second store`() {
        val results = listOf(
            result("Bolt", "StoreA", 100.0),
            result("Spear", "StoreA", 300.0),
            result("Spear", "StoreB", 40.0),   // R260 cheaper -- worth the extra R110 parcel
        )
        val plan = balancedPlan(listOf("Bolt", "Spear"), results)
        assertEquals(2, plan.storeCount)
        assertEquals(360.0, plan.allInTotal, 0.001)   // 140 cards + 220 delivery
    }

    @Test fun `delivery of zero is exactly the cheapest plan`() {
        val results = listOf(
            result("Bolt", "StoreA", 100.0),
            result("Spear", "StoreA", 100.0),
            result("Spear", "StoreB", 40.0),
        )
        val balanced = balancedPlan(listOf("Bolt", "Spear"), results, deliveryPerStore = 0.0)
        val cheapest = cheapestPlan(listOf("Bolt", "Spear"), results)
        assertEquals(cheapest.grandTotal, balanced.grandTotal, 0.001)
        assertEquals(cheapest.storeCount, balanced.storeCount)
    }

    @Test fun `delivery large enough to dominate collapses to the fewest-stores answer`() {
        val results = listOf(
            result("Bolt", "StoreA", 100.0),
            result("Spear", "StoreA", 300.0),
            result("Spear", "StoreB", 40.0),
        )
        val balanced = balancedPlan(listOf("Bolt", "Spear"), results, deliveryPerStore = 100_000.0)
        assertEquals(1, balanced.storeCount)
        assertEquals(fewestStoresPlan(listOf("Bolt", "Spear"), results).storeCount, balanced.storeCount)
    }

    // A store that's cheapest for nothing can still be the right answer -- it replaces two parcels
    // with one. This is why "only consider stores that win a card outright" would be wrong.
    @Test fun `a store that is cheapest for no card can still win on delivery`() {
        val results = listOf(
            result("Bolt", "StoreP", 10.0),
            result("Spear", "StoreQ", 10.0),
            result("Bolt", "StoreX", 20.0),
            result("Spear", "StoreX", 20.0),
        )
        val plan = balancedPlan(listOf("Bolt", "Spear"), results)
        assertEquals(1, plan.storeCount)
        assertEquals("StoreX", plan.storeOrders.single().store)
        assertEquals(150.0, plan.allInTotal, 0.001)   // 40 + 110, vs 20 + 220 for P+Q
    }

    // ── coverage, quantities, pins ───────────────────────────────────────────

    @Test fun `coverage wins over cost -- a card only one store has still gets bought`() {
        val results = listOf(
            result("Bolt", "StoreA", 100.0),
            result("Spear", "StoreB", 5.0),   // only source, so its parcel is unavoidable
        )
        val plan = balancedPlan(listOf("Bolt", "Spear"), results)
        assertEquals(2, plan.storeCount)
        assertTrue(plan.uncoveredCards.isEmpty())
    }

    @Test fun `same shortfalls as the cheapest plan`() {
        val results = listOf(
            result("Bolt", "StoreA", 100.0),
            result("Spear", "StoreA", 50.0, available = false),
            result("Counterspell", "StoreB", 5.0, stockQty = 1),
        )
        val cards = listOf("Bolt", "Spear", "Counterspell")
        val quantities = mapOf("Counterspell" to 3)
        assertEquals(
            cheapestPlan(cards, results, quantities = quantities).uncoveredCards,
            balancedPlan(cards, results, quantities = quantities).uncoveredCards,
        )
    }

    @Test fun `quantity is split across stores when one store cannot cover it`() {
        val results = listOf(
            result("Bolt", "StoreA", 10.0, stockQty = 2),
            result("Bolt", "StoreB", 12.0, stockQty = 2),
        )
        val plan = balancedPlan(listOf("Bolt"), results, quantities = mapOf("Bolt" to 4))
        assertEquals(2, plan.storeCount)
        assertEquals(4, plan.itemCount)
        assertTrue(plan.uncoveredCards.isEmpty())
    }

    // A pin fixes both the card's listing and its store's parcel; the rest of the plan is then
    // optimised around that fixed cost -- which can make a second store pointless.
    @Test fun `a pin fixes its store and the rest optimises around it`() {
        val results = listOf(
            result("Bolt", "StoreA", 250.0, url = "https://example.com/pin"),
            result("Bolt", "StoreB", 100.0),
            result("Spear", "StoreA", 90.0),
            result("Spear", "StoreB", 40.0),   // R50 cheaper, but StoreA's parcel is already paid
        )
        val plan = balancedPlan(
            listOf("Bolt", "Spear"), results,
            pinnedListings = mapOf("Bolt" to "https://example.com/pin"),
        )
        assertEquals(1, plan.storeCount)
        assertEquals("StoreA", plan.storeOrders.single().store)
        val bolt = plan.storeOrders.single().lines.first { it.card == "Bolt" }
        assertEquals("https://example.com/pin", bolt.listing.url)
        assertEquals(450.0, plan.allInTotal, 0.001)   // 250 + 90 + 110
    }

    @Test fun `an unhonourable pin is reported, not silently substituted`() {
        val results = listOf(
            result("Bolt", "StoreA", 250.0, url = "https://example.com/pin", available = false),
            result("Bolt", "StoreB", 100.0),
        )
        val plan = balancedPlan(
            listOf("Bolt"), results,
            pinnedListings = mapOf("Bolt" to "https://example.com/pin"),
        )
        assertTrue(plan.storeOrders.isEmpty())
        assertEquals(ShortfallReason.PINNED_UNAVAILABLE, plan.uncoveredCards.single().reason)
    }

    @Test fun `plan carries the delivery estimate it optimised against`() {
        val plan = balancedPlan(listOf("Bolt"), listOf(result("Bolt", "StoreA", 10.0)))
        assertEquals(DEFAULT_DELIVERY_ZAR, plan.deliveryPerStore, 0.001)
        assertEquals(DEFAULT_DELIVERY_ZAR, plan.deliveryTotal, 0.001)
        assertEquals(10.0 + DEFAULT_DELIVERY_ZAR, plan.allInTotal, 0.001)
        // The other two strategies don't price delivery at all.
        assertEquals(0.0, cheapestPlan(listOf("Bolt"), listOf(result("Bolt", "StoreA", 10.0))).allInTotal - 10.0, 0.001)
    }

    @Test fun `empty results produce an empty plan, not a crash`() {
        val plan = balancedPlan(listOf("Bolt"), emptyList())
        assertTrue(plan.storeOrders.isEmpty())
        assertEquals(ShortfallReason.NOT_STOCKED, plan.uncoveredCards.single().reason)
    }

    // ── the search is exact, not just plausible ──────────────────────────────

    // The pruning (essential-store forcing + the delivery lower bound) is what makes this fast; it's
    // also what could quietly make it wrong. So: random instances, checked against an exhaustive
    // sweep of all 2^n store subsets, each subset costed by the already-trusted cheapestPlan
    // restricted to that subset.
    @Test fun `matches brute force over every store subset on random instances`() {
        val rng = Random(20260909)
        repeat(60) { iteration ->
            val storeCount = 2 + rng.nextInt(5)          // 2..6 stores
            val cardCount = 1 + rng.nextInt(5)           // 1..5 cards
            val stores = (0 until storeCount).map { "Store$it" }
            val cards = (0 until cardCount).map { "Card$it" }
            val quantities = cards.associateWith { 1 + rng.nextInt(2) }
            val results = mutableListOf<SearchResult>()
            for (card in cards) for (store in stores) {
                if (rng.nextInt(100) < 55) {             // ~55% of (card, store) pairs are stocked
                    results += result(
                        card, store,
                        price = (5 + rng.nextInt(400)).toDouble(),
                        stockQty = if (rng.nextInt(100) < 40) 1 + rng.nextInt(2) else null,
                    )
                }
            }
            val delivery = listOf(0.5, 30.0, 110.0, 900.0)[rng.nextInt(4)]
            // Sometimes pin a card, since a pin forces a store into every candidate answer and so
            // exercises a different path through the search. Top-up stays off here: with it on, a
            // pinned card can legally be sourced elsewhere, which is a deliberate difference from
            // what this brute force models (it costs each subset with the pin simply honoured).
            val pins = results.takeIf { rng.nextInt(100) < 30 }
                ?.random(rng)
                ?.let { mapOf(it.card to it.url) }
                .orEmpty()

            val plan = balancedPlan(cards, results, pinnedListings = pins, quantities = quantities, deliveryPerStore = delivery)
            val brute = bruteForce(cards, stores, results, pins, quantities, delivery)
            assertEquals(
                brute, plan.allInTotal, 0.001,
                "instance $iteration: search found ${plan.allInTotal}, brute force says $brute",
            )
        }
    }

    // Reference implementation: try every subset of stores, cost each one with cheapestPlan (which
    // is optimal *within* a fixed store set), and keep the cheapest that still covers as much as
    // the full set does.
    private fun bruteForce(
        cards: List<String>,
        stores: List<String>,
        results: List<SearchResult>,
        pins: Map<String, String>,
        quantities: Map<String, Int>,
        delivery: Double,
    ): Double {
        fun covered(plan: OrderPlan) = cards.associateWith { card ->
            plan.storeOrders.flatMap { it.lines }.filter { it.card == card }.sumOf { it.qty }
        }
        val fullCoverage = covered(cheapestPlan(cards, results, pinnedListings = pins, quantities = quantities))
        var best = Double.MAX_VALUE
        for (mask in 0 until (1 shl stores.size)) {
            val open = stores.filterIndexedTo(mutableSetOf()) { i, _ -> (mask shr i) and 1 == 1 }
            val plan = cheapestPlan(cards, results.filter { it.store in open }, pinnedListings = pins, quantities = quantities)
            if (covered(plan) != fullCoverage) continue
            best = minOf(best, plan.grandTotal + plan.storeCount * delivery)
        }
        return best
    }
}
