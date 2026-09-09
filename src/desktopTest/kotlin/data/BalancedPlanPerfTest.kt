package data

import kotlin.random.Random
import kotlin.system.measureTimeMillis
import kotlin.test.Test
import kotlin.test.assertTrue

// balancedPlan runs inside a derivedStateOf that recomputes on every streamed result row, so its
// worst realistic case has to stay well clear of "noticeable". Sizes here mirror the largest search
// the app is documented to support (83 cards x 20 stores, quantities on).
class BalancedPlanPerfTest {

    private fun instance(cards: Int, stores: Int, seed: Int): Triple<List<String>, List<SearchResult>, Map<String, Int>> {
        val rng = Random(seed)
        val cardNames = (0 until cards).map { "Card$it" }
        val storeNames = (0 until stores).map { "Store$it" }
        val results = mutableListOf<SearchResult>()
        for (card in cardNames) for (store in storeNames) {
            if (rng.nextInt(100) < 60) {
                repeat(1 + rng.nextInt(2)) { variant ->
                    results += SearchResult(
                        store = store, card = card, title = card,
                        priceZar = (5 + rng.nextInt(600)).toDouble(),
                        available = true, url = "https://example.com/$store/$card/$variant", note = "",
                        stockQty = if (rng.nextInt(100) < 40) 1 + rng.nextInt(3) else null,
                    )
                }
            }
        }
        return Triple(cardNames, results, cardNames.associateWith { 1 + rng.nextInt(4) })
    }

    @Test fun `large search stays fast`() {
        val (cards, results, quantities) = instance(cards = 83, stores = 20, seed = 7)
        // Warm the JIT the same way the UI does (several recomputes as rows stream in).
        repeat(3) { balancedPlan(cards, results, quantities = quantities) }
        val ms = measureTimeMillis { balancedPlan(cards, results, quantities = quantities) }
        println("balancedPlan: 83 cards x 20 stores (${results.size} listings) in ${ms}ms")
        assertTrue(ms < 250, "balancedPlan took ${ms}ms on 83 cards x 20 stores (measured ~7ms; 5s before the bitmask branch-and-bound)")
    }

    // The shape that actually stresses the subset search: few cards (so the card floor is low and
    // the delivery bound allows deep branches) spread thinly across every store.
    @Test fun `wide sparse search stays fast`() {
        val (cards, results, quantities) = instance(cards = 6, stores = 20, seed = 11)
        repeat(3) { balancedPlan(cards, results, quantities = quantities) }
        val ms = measureTimeMillis { balancedPlan(cards, results, quantities = quantities) }
        println("balancedPlan: 6 cards x 20 stores (${results.size} listings) in ${ms}ms")
        assertTrue(ms < 250, "balancedPlan took ${ms}ms on 6 cards x 20 stores (measured ~1ms)")
    }
}
