package data

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * The card-summary panel recomputes this on every recomposition, and a streaming search
 * recomposes on every arriving row -- so a large search ran it many times a second on the UI
 * thread. It used to compile five java.util.regex Patterns per listing title (they are now
 * hoisted to constants); on an 80-card / 3200-listing search that was tens of thousands of
 * Pattern compilations per frame. Guard the cost so it can't creep back.
 */
class CardSummaryFactsPerfTest {

    @Test fun `stays fast on a large search`() {
        val cards = (1..80).map { "Test Card $it" }
        val results = cards.flatMap { card ->
            (1..40).map { i ->
                SearchResult(
                    store = "Store $i",
                    card = card,
                    title = "$card (Foil) [Set Name] #$i - Some Set NM",
                    priceZar = 10.0,
                    available = i % 3 != 0,
                    url = "https://example.com/$card/$i",
                    note = "",
                )
            }
        }
        // Warm up the JIT so the measured run reflects steady state, not first-call compilation.
        repeat(3) { cardSummaryFacts(cards, results, includePartialMatches = false) }
        val elapsed = measureTime {
            repeat(5) { cardSummaryFacts(cards, results, includePartialMatches = false) }
        }
        val perCall = elapsed / 5
        assertTrue(
            perCall.inWholeMilliseconds < 250,
            "cardSummaryFacts took $perCall per call on 80 cards x 40 listings (budget 250ms)",
        )
    }
}
