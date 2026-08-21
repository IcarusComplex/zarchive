package data

/** One card assigned to a specific store listing in an order plan, for [qty] units of it. */
data class OrderLine(val card: String, val listing: SearchResult, val qty: Int = 1)

/** All the cards to buy from a single store, with a link to that store. */
data class StoreOrder(
    val store: String,
    val storeUrl: String,
    val lines: List<OrderLine>,
) {
    /** Distinct listing rows (not physical copies — see [itemCount]). */
    val lineCount: Int get() = lines.size
    /** Total physical copies across all lines. */
    val itemCount: Int get() = lines.sumOf { it.qty }
    /** Sum of the priced lines (null-priced listings contribute nothing). */
    val total: Double get() = lines.sumOf { (it.listing.priceZar ?: 0.0) * it.qty }
}

/** A card that couldn't be fully sourced: [found] of [needed] units were available. */
data class OrderShortfall(val card: String, val needed: Int, val found: Int)

/**
 * A complete buying plan: which cards to order from which stores, plus the cards
 * that couldn't be fully sourced anywhere in the current result set.
 */
data class OrderPlan(
    val storeOrders: List<StoreOrder>,
    val uncoveredCards: List<OrderShortfall>,
) {
    val storeCount: Int get() = storeOrders.size
    val itemCount: Int get() = storeOrders.sumOf { it.itemCount }
    val grandTotal: Double get() = storeOrders.sumOf { it.total }
}

// A store's contribution is treated as this large when a listing's stockQty is unknown (null),
// so summing/min-ing against real remaining-need values always "takes everything needed."
private const val UNLIMITED_SENTINEL = 1_000_000

// Cheapest first; null prices (Unknown) sort last.
private val byPrice = compareBy<SearchResult>({ it.priceZar == null }, { it.priceZar ?: Double.MAX_VALUE })

// A listing is orderable only if it's a real, in-stock product row at a named store.
// Placeholder rows (e.g. Luckshack's "Click to search…" — store is blank, no price, no
// stock state) are NOT shops we can build an order from, so they're excluded entirely.
private fun List<SearchResult>.inStockOnly() =
    filter { it.title != null && it.available != false && it.store.isNotBlank() }

/**
 * Cards from [cards] with no in-stock listing anywhere in [results] — same "unavailable"
 * definition used by [cheapestPlan]/[fewestStoresPlan]'s `uncoveredCards`. Lets callers (e.g.
 * "refresh only unavailable cards") ask the question without building a full [OrderPlan].
 * Deliberately quantity-agnostic — "any stock at all," not "enough stock" — a different,
 * simpler question than the order plan answers.
 */
fun unavailableCards(cards: List<String>, results: List<SearchResult>, includePartialMatches: Boolean = false): List<String> {
    val byCard = results.inStockOnly().groupBy { it.card }
    return cards.distinct().filter { card ->
        val listings = byCard[card] ?: return@filter true
        preferExactMatches(card, listings, exactOnly = !includePartialMatches).isEmpty()
    }
}

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
 * Ordered candidate listings for one card, honoring pin/top-up semantics:
 * - No pin: every in-stock listing (already narrowed to exact-name matches unless
 *   [includePartialMatches]), cheapest first.
 * - Pinned, top-up off: only the pinned listing — a pin means "source here only."
 * - Pinned, top-up on: the pinned listing first (regardless of price, since the user
 *   explicitly chose it), then every other listing cheapest-first to cover any shortfall.
 */
private fun candidatePool(
    card: String,
    rawByCard: Map<String, List<SearchResult>>,
    exactByCard: Map<String, List<SearchResult>>,
    pin: String?,
    topUpPinnedShortfalls: Boolean,
): List<SearchResult> = when {
    pin == null -> exactByCard[card].orEmpty().sortedWith(byPrice)
    else -> {
        val pinnedListing = rawByCard[card].orEmpty().firstOrNull { it.url == pin }
        if (!topUpPinnedShortfalls) listOfNotNull(pinnedListing)
        else listOfNotNull(pinnedListing) + exactByCard[card].orEmpty().filter { it.url != pin }.sortedWith(byPrice)
    }
}

// Consumes [pool] cheapest-first (pool is already ordered) until [needed] units are taken or the
// pool is exhausted. A listing with a known stockQty caps how much can be taken from it; an
// unknown (null) stockQty is treated as "enough to cover whatever's left."
private fun consume(card: String, needed: Int, pool: List<SearchResult>): Pair<List<OrderLine>, Int> {
    val lines = mutableListOf<OrderLine>()
    var remaining = needed
    for (listing in pool) {
        if (remaining <= 0) break
        val take = listing.stockQty?.let { minOf(remaining, it) } ?: remaining
        if (take <= 0) continue
        lines += OrderLine(card, listing, take)
        remaining -= take
    }
    return lines to remaining
}

/**
 * **Cheapest total** plan: for every requested card, buy the cheapest in-stock listings first,
 * consuming as many units as each one has (or all that's still needed, when stock is unknown),
 * moving to the next-cheapest listing if more units are still needed. Minimises spend; may split
 * a single card's quantity across multiple listings/stores, and may spread the order across many
 * stores. If a card's total available stock falls short of [quantities], the units that *were*
 * found are still included in the plan — the shortfall is reported in [OrderPlan.uncoveredCards],
 * not treated as all-or-nothing.
 *
 * If [pinnedListings] contains an entry for a card (card → listing URL), only that specific
 * listing is considered for that card, unless [topUpPinnedShortfalls] is set — then any shortfall
 * left by the pinned listing is topped up from other listings.
 *
 * [quantities] gives the number of units wanted per card; cards absent from the map default to 1.
 */
fun cheapestPlan(
    cards: List<String>,
    results: List<SearchResult>,
    pinnedListings: Map<String, String> = emptyMap(),
    includePartialMatches: Boolean = false,
    quantities: Map<String, Int> = emptyMap(),
    topUpPinnedShortfalls: Boolean = false,
): OrderPlan {
    val uniqueCards = cards.distinct()
    val rawByCard = results.inStockOnly().groupBy { it.card }
    val exactByCard = rawByCard.mapValues { (card, ls) -> preferExactMatches(card, ls, exactOnly = !includePartialMatches) }

    val chosen = mutableListOf<OrderLine>()
    val shortfalls = mutableListOf<OrderShortfall>()
    for (card in uniqueCards) {
        val needed = quantities[card] ?: 1
        val pool = candidatePool(card, rawByCard, exactByCard, pinnedListings[card], topUpPinnedShortfalls)
        val (lines, remaining) = consume(card, needed, pool)
        chosen += lines
        if (remaining > 0) shortfalls += OrderShortfall(card, needed, needed - remaining)
    }
    return OrderPlan(buildStoreOrders(chosen), shortfalls)
}

/**
 * **Fewest packages** plan: a greedy set-cover that picks the smallest set of stores which
 * together supply as many of the requested units as possible. Each picked store then supplies
 * whichever cards/units it can, cheapest-listing-first. Minimises number of orders/shipments,
 * price aside. Falls short the same way [cheapestPlan] does: a card whose total available stock
 * (across picked stores) is less than requested still contributes its found units to the plan,
 * with the rest reported in [OrderPlan.uncoveredCards].
 *
 * If [pinnedListings] contains an entry for a card (card → listing URL), only that specific
 * listing is considered, which forces the set-cover to include that listing's store — unless
 * [topUpPinnedShortfalls] is set, in which case other stores may still be picked to cover any
 * shortfall left by the pinned listing.
 *
 * [quantities] gives the number of units wanted per card; cards absent from the map default to 1.
 */
fun fewestStoresPlan(
    cards: List<String>,
    results: List<SearchResult>,
    pinnedListings: Map<String, String> = emptyMap(),
    includePartialMatches: Boolean = false,
    quantities: Map<String, Int> = emptyMap(),
    topUpPinnedShortfalls: Boolean = false,
): OrderPlan {
    val uniqueCards = cards.distinct()
    val rawByCard = results.inStockOnly().groupBy { it.card }
    val exactByCard = rawByCard.mapValues { (card, ls) -> preferExactMatches(card, ls, exactOnly = !includePartialMatches) }

    val pools: Map<String, List<SearchResult>> = uniqueCards.associateWith { card ->
        candidatePool(card, rawByCard, exactByCard, pinnedListings[card], topUpPinnedShortfalls)
    }
    val uncoveredFromStart = uniqueCards.filter { pools[it].isNullOrEmpty() }
    val coverable = uniqueCards.filter { !pools[it].isNullOrEmpty() }

    // store → (card → total units that store's pool listings can supply for that card)
    val storeCoverage: Map<String, Map<String, Int>> = buildMap<String, MutableMap<String, Int>> {
        for (card in coverable) {
            for (listing in pools.getValue(card)) {
                val units = listing.stockQty ?: UNLIMITED_SENTINEL
                val perCard = getOrPut(listing.store) { mutableMapOf() }
                perCard[card] = minOf((perCard[card] ?: 0) + units, UNLIMITED_SENTINEL)
            }
        }
    }

    val remaining = coverable.associateWith { quantities[it] ?: 1 }.toMutableMap()
    val picked = linkedSetOf<String>()
    val chosen = mutableListOf<OrderLine>()

    while (remaining.values.any { it > 0 }) {
        // Store covering the most still-needed units. Tie-break: combined price for the units
        // it would newly supply (keeps the plan deterministic & sensible).
        val best = storeCoverage.entries
            .filter { it.key !in picked }
            .maxWithOrNull(
                compareBy<Map.Entry<String, Map<String, Int>>> { entry ->
                    coverable.sumOf { card -> minOf(remaining[card] ?: 0, entry.value[card] ?: 0) }
                }.thenByDescending { entry ->
                    coverable.sumOf { card ->
                        val need = remaining[card] ?: 0
                        if (need <= 0) 0.0
                        else consume(card, need, pools.getValue(card).filter { it.store == entry.key }.sortedWith(byPrice))
                            .first.sumOf { (it.listing.priceZar ?: 10_000.0) * it.qty }
                    }
                }
            ) ?: break
        val gain = coverable.sumOf { card -> minOf(remaining[card] ?: 0, best.value[card] ?: 0) }
        if (gain <= 0) break
        picked += best.key

        for (card in coverable) {
            val need = remaining[card] ?: 0
            if (need <= 0) continue
            val (lines, stillRemaining) = consume(card, need, pools.getValue(card).filter { it.store == best.key }.sortedWith(byPrice))
            chosen += lines
            remaining[card] = stillRemaining
        }
    }

    val shortfalls = coverable.mapNotNull { card ->
        val needed = quantities[card] ?: 1
        val found = needed - (remaining[card] ?: needed)
        if (found < needed) OrderShortfall(card, needed, found) else null
    } + uncoveredFromStart.map { OrderShortfall(it, quantities[it] ?: 1, 0) }

    return OrderPlan(buildStoreOrders(chosen), shortfalls)
}
