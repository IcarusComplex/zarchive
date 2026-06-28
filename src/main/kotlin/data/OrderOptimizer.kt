package data

/** One card assigned to a specific store listing in an order plan. */
data class OrderLine(val card: String, val listing: SearchResult)

/** All the cards to buy from a single store, with a link to that store. */
data class StoreOrder(
    val store: String,
    val storeUrl: String,
    val lines: List<OrderLine>,
) {
    val itemCount: Int get() = lines.size
    /** Sum of the priced lines (null-priced listings contribute nothing). */
    val total: Double get() = lines.sumOf { it.listing.priceZar ?: 0.0 }
}

/**
 * A complete buying plan: which cards to order from which stores, plus the cards
 * that aren't in stock anywhere in the current result set.
 */
data class OrderPlan(
    val storeOrders: List<StoreOrder>,
    val uncoveredCards: List<String>,
) {
    val storeCount: Int get() = storeOrders.size
    val itemCount: Int get() = storeOrders.sumOf { it.itemCount }
    val grandTotal: Double get() = storeOrders.sumOf { it.total }
}

// Cheapest first; null prices (Unknown) sort last.
private val byPrice = compareBy<SearchResult>({ it.priceZar == null }, { it.priceZar ?: Double.MAX_VALUE })

// A listing is orderable only if it's a real, in-stock product row at a named store.
// Placeholder rows (e.g. Luckshack's "Click to search…" — store is blank, no price, no
// stock state) are NOT shops we can build an order from, so they're excluded entirely.
private fun List<SearchResult>.inStockOnly() =
    filter { it.title != null && it.available != false && it.store.isNotBlank() }

private fun buildStoreOrders(lines: List<OrderLine>): List<StoreOrder> =
    lines.groupBy { it.listing.store }
        .map { (store, ls) ->
            StoreOrder(
                store = store,
                storeUrl = STORES[store] ?: ls.first().listing.url,
                lines = ls.sortedBy { it.card.lowercase() },
            )
        }
        // Biggest orders first, then alphabetical — stable, useful ordering.
        .sortedWith(compareByDescending<StoreOrder> { it.itemCount }.thenBy { it.store })

/**
 * **Cheapest total** plan: for every requested card pick the single lowest-priced
 * in-stock listing anywhere, then group those picks by store. Minimises spend,
 * may spread the order across many stores.
 *
 * If [pinnedListings] contains an entry for a card (card → listing URL), only that
 * specific listing is considered for that card.
 */
fun cheapestPlan(cards: List<String>, results: List<SearchResult>, pinnedListings: Map<String, String> = emptyMap(), includePartialMatches: Boolean = false): OrderPlan {
    val uniqueCards = cards.distinct()
    val byCard = results.inStockOnly().groupBy { it.card }
        .mapValues { (card, ls) ->
            val pin = pinnedListings[card]
            if (pin != null) ls.filter { it.url == pin } else preferExactMatches(card, ls, exactOnly = !includePartialMatches)
        }
    val chosen = mutableListOf<OrderLine>()
    val uncovered = mutableListOf<String>()
    for (card in uniqueCards) {
        val best = byCard[card]?.minWithOrNull(byPrice)
        if (best == null) uncovered += card else chosen += OrderLine(card, best)
    }
    return OrderPlan(buildStoreOrders(chosen), uncovered)
}

/**
 * **Fewest packages** plan: a greedy set-cover that picks the smallest set of stores
 * which together stock every available card. Each card is then sourced from the
 * cheapest of the chosen stores that carries it. Minimises number of orders/shipments,
 * regardless of price.
 *
 * If [pinnedListings] contains an entry for a card (card → listing URL), only that
 * specific listing is considered, which forces the set-cover to include that listing's store.
 */
fun fewestStoresPlan(cards: List<String>, results: List<SearchResult>, pinnedListings: Map<String, String> = emptyMap(), includePartialMatches: Boolean = false): OrderPlan {
    val uniqueCards = cards.distinct()
    val byCard = results.inStockOnly().groupBy { it.card }
        .mapValues { (card, ls) ->
            val pin = pinnedListings[card]
            if (pin != null) ls.filter { it.url == pin } else preferExactMatches(card, ls, exactOnly = !includePartialMatches)
        }
    val inStock = byCard.values.flatten()
    val uncovered = uniqueCards.filter { byCard[it].isNullOrEmpty() }
    val coverable = uniqueCards.filter { !byCard[it].isNullOrEmpty() }.toSet()

    // store → set of (coverable) cards it can supply
    val storeCoverage: Map<String, Set<String>> = inStock
        .filter { it.card in coverable }
        .groupBy { it.store }
        .mapValues { (_, rows) -> rows.map { it.card }.toSet() }

    val remaining = coverable.toMutableSet()
    val picked = linkedSetOf<String>()
    while (remaining.isNotEmpty()) {
        // Store covering the most still-needed cards. Tie-break: cheaper combined
        // price for the newly-covered cards (keeps the plan deterministic & sensible).
        val best = storeCoverage.entries
            .filter { it.key !in picked }
            .maxWithOrNull(
                compareBy<Map.Entry<String, Set<String>>> { (it.value intersect remaining).size }
                    .thenByDescending { entry ->
                        val gain = entry.value intersect remaining
                        inStock.filter { it.store == entry.key && it.card in gain }
                            .groupBy { it.card }
                            .values.sumOf { rows -> rows.minWithOrNull(byPrice)?.priceZar ?: 10_000.0 }
                    }
            ) ?: break
        val gain = best.value intersect remaining
        if (gain.isEmpty()) break
        picked += best.key
        remaining -= gain
    }

    // Each coverable card → cheapest listing among the picked stores that stock it.
    val chosen = coverable.mapNotNull { card ->
        byCard[card]!!.filter { it.store in picked }.minWithOrNull(byPrice)
            ?.let { OrderLine(card, it) }
    }
    return OrderPlan(buildStoreOrders(chosen), uncovered)
}
