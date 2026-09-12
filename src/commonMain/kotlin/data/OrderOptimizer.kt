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

/**
 * Why a card couldn't be fully sourced. "Unavailable" used to collapse four very different
 * situations into one label, which made a transient store failure indistinguishable from a card
 * nobody stocks -- and a card silently governed by a pin indistinguishable from both.
 */
enum class ShortfallReason {
    /** Some copies were found, just fewer than requested. */
    PARTIAL,
    /** A pinned listing is set for this card and it isn't in stock in the current results. */
    PINNED_UNAVAILABLE,
    /** At least one store errored/timed out for this card, so "unavailable" may just be missing data. */
    INCOMPLETE,
    /** Listings were found, but every one of them is out of stock. */
    OUT_OF_STOCK,
    /** No store returned a listing for this card at all. */
    NOT_STOCKED,
}

/**
 * A card that couldn't be fully sourced: [found] of [needed] units were available, because of
 * [reason]. [erroredStores] and [listingsSeen] are the evidence behind [reason] -- how many stores
 * failed to answer for this card, and how many listings (in stock or not) were seen for it.
 */
data class OrderShortfall(
    val card: String,
    val needed: Int,
    val found: Int,
    val reason: ShortfallReason = ShortfallReason.NOT_STOCKED,
    val erroredStores: Int = 0,
    val listingsSeen: Int = 0,
)

/**
 * Short "why" shown next to the card name in the order list's "Not fully available" panel.
 * Shared so desktop and Android word it identically. Null for [ShortfallReason.NOT_STOCKED] --
 * "no store had it" is what the bare card name in that panel already says.
 */
fun shortfallReasonLabel(shortfall: OrderShortfall): String? {
    val n = shortfall.erroredStores
    val didntAnswer = "$n store${if (n == 1) "" else "s"} didn't answer"
    return when (shortfall.reason) {
        ShortfallReason.PARTIAL -> "${shortfall.found} of ${shortfall.needed} found"
        ShortfallReason.PINNED_UNAVAILABLE -> "pinned listing unavailable"
        ShortfallReason.INCOMPLETE -> if (shortfall.listingsSeen > 0) "out of stock, $didntAnswer" else didntAnswer
        ShortfallReason.OUT_OF_STOCK -> "out of stock"
        ShortfallReason.NOT_STOCKED -> null
    }
}

/**
 * A complete buying plan: which cards to order from which stores, plus the cards
 * that couldn't be fully sourced anywhere in the current result set.
 */
data class OrderPlan(
    val storeOrders: List<StoreOrder>,
    val uncoveredCards: List<OrderShortfall>,
    /**
     * Per-parcel delivery estimate charged in [deliveryTotal] / [allInTotal]. Every strategy
     * carries it so the three plans' all-in figures are comparable -- that comparison is the
     * whole point of the strategy toggle, and "cheapest cards" routinely loses it by spreading
     * the order over more parcels. Only [balancedPlan] *optimises* against it; the other two
     * merely report it. [grandTotal] always excludes it -- a store's real courier fee isn't
     * something a search can know.
     */
    val deliveryPerStore: Double = 0.0,
) {
    val storeCount: Int get() = storeOrders.size
    val itemCount: Int get() = storeOrders.sumOf { it.itemCount }
    val grandTotal: Double get() = storeOrders.sumOf { it.total }
    /** Estimated delivery across every store in the plan; 0.0 unless [deliveryPerStore] is set. */
    val deliveryTotal: Double get() = storeCount * deliveryPerStore
    /** Cards plus estimated delivery. This is the number [balancedPlan] minimises. */
    val allInTotal: Double get() = grandTotal + deliveryTotal
}

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

/** What the raw result rows say about one card, beyond what survives [inStockOnly]. */
private class CardEvidence(val listingsSeen: Int, val erroredStores: Int)

// Counted from the *raw* results (not inStockOnly's), because the interesting cases are exactly
// the rows the plan throws away: out-of-stock listings, and title-less rows left behind by a store
// that errored, timed out or was rate-limited for this card (note != "not stocked", which is a real
// "we looked and they don't have it" answer).
private fun cardEvidence(results: List<SearchResult>): Map<String, CardEvidence> =
    results.groupBy { it.card }.mapValues { (_, rows) ->
        CardEvidence(
            listingsSeen = rows.count { it.title != null },
            erroredStores = rows.filter { it.title == null && it.note != NOTE_NOT_STOCKED }
                .mapTo(mutableSetOf()) { it.store }.size,
        )
    }

// True when a pin can't be honoured at all: a listing is pinned for this card but it isn't among
// the card's in-stock rows this run (sold out since, store errored, or the pin is left over from
// an earlier search). Without this the card reads as "unavailable" with no hint that a pin, not
// the market, is what emptied its candidate pool.
private fun pinUnhonoured(pin: String?, inStock: List<SearchResult>?): Boolean =
    pin != null && inStock.orEmpty().none { it.url == pin }

private fun shortfallOf(
    card: String,
    needed: Int,
    found: Int,
    evidence: CardEvidence?,
    pinUnhonoured: Boolean,
): OrderShortfall {
    val errored = evidence?.erroredStores ?: 0
    val seen = evidence?.listingsSeen ?: 0
    return OrderShortfall(
        card = card,
        needed = needed,
        found = found,
        reason = when {
            found > 0 -> ShortfallReason.PARTIAL
            pinUnhonoured -> ShortfallReason.PINNED_UNAVAILABLE
            errored > 0 -> ShortfallReason.INCOMPLETE
            seen > 0 -> ShortfallReason.OUT_OF_STOCK
            else -> ShortfallReason.NOT_STOCKED
        },
        erroredStores = errored,
        listingsSeen = seen,
    )
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
// unknown (null) stockQty is conservatively assumed to be 1 — we have no confirmation the store
// actually has more, and wrongly assuming "unlimited" here is what causes the plan (and then the
// real store cart, via the "Open cart" button) to over-order from an under-stocked listing.
private fun consume(card: String, needed: Int, pool: List<SearchResult>): Pair<List<OrderLine>, Int> {
    val lines = mutableListOf<OrderLine>()
    var remaining = needed
    for (listing in pool) {
        if (remaining <= 0) break
        val take = minOf(remaining, listing.stockQty ?: 1)
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
    deliveryPerStore: Double = DEFAULT_DELIVERY_ZAR,
): OrderPlan {
    val uniqueCards = cards.distinct()
    val rawByCard = results.inStockOnly().groupBy { it.card }
    val exactByCard = rawByCard.mapValues { (card, ls) -> preferExactMatches(card, ls, exactOnly = !includePartialMatches) }
    val evidence = cardEvidence(results)

    val chosen = mutableListOf<OrderLine>()
    val shortfalls = mutableListOf<OrderShortfall>()
    for (card in uniqueCards) {
        val needed = quantities[card] ?: 1
        val pin = pinnedListings[card]
        val pool = candidatePool(card, rawByCard, exactByCard, pin, topUpPinnedShortfalls)
        val (lines, remaining) = consume(card, needed, pool)
        chosen += lines
        if (remaining > 0) shortfalls += shortfallOf(
            card, needed, needed - remaining, evidence[card], pinUnhonoured(pin, rawByCard[card]),
        )
    }
    return OrderPlan(buildStoreOrders(chosen), shortfalls, deliveryPerStore)
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
    deliveryPerStore: Double = DEFAULT_DELIVERY_ZAR,
): OrderPlan {
    val uniqueCards = cards.distinct()
    val rawByCard = results.inStockOnly().groupBy { it.card }
    val exactByCard = rawByCard.mapValues { (card, ls) -> preferExactMatches(card, ls, exactOnly = !includePartialMatches) }
    val evidence = cardEvidence(results)

    val pools: Map<String, List<SearchResult>> = uniqueCards.associateWith { card ->
        candidatePool(card, rawByCard, exactByCard, pinnedListings[card], topUpPinnedShortfalls)
    }
    val uncoveredFromStart = uniqueCards.filter { pools[it].isNullOrEmpty() }
    val coverable = uniqueCards.filter { !pools[it].isNullOrEmpty() }

    // store → (card → total units that store's pool listings can supply for that card)
    val storeCoverage: Map<String, Map<String, Int>> = buildMap<String, MutableMap<String, Int>> {
        for (card in coverable) {
            for (listing in pools.getValue(card)) {
                val units = listing.stockQty ?: 1
                val perCard = getOrPut(listing.store) { mutableMapOf() }
                perCard[card] = (perCard[card] ?: 0) + units
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
        if (found < needed) shortfallOf(
            card, needed, found, evidence[card], pinUnhonoured(pinnedListings[card], rawByCard[card]),
        ) else null
    } + uncoveredFromStart.map { card ->
        shortfallOf(
            card, quantities[card] ?: 1, 0, evidence[card], pinUnhonoured(pinnedListings[card], rawByCard[card]),
        )
    }

    return OrderPlan(buildStoreOrders(chosen), shortfalls, deliveryPerStore)
}

// ── Balanced (cards + delivery) ───────────────────────────────────────────────

/**
 * What one extra parcel is assumed to cost when [balancedPlan] weighs "one more store" against a
 * cheaper card. A flat SA-courier-ish estimate, deliberately a single number: stores publish
 * delivery rules we can't read (per-order fee, per-weight, free over a threshold, collection
 * points), so a precise-looking per-store figure would be false precision. Change it in one place,
 * or pass `deliveryPerStore` explicitly.
 */
const val DEFAULT_DELIVERY_ZAR = 110.0

// Safety valve on the exact search below: if a pathological instance ever blows past it, the
// incumbent from the local search is returned instead -- a valid plan, just not a proven-optimal
// one. Sized so the worst measured realistic case (83 cards x 20 stores with quantities) stays
// two orders of magnitude clear of it; see BalancedPlanPerfTest.
private const val BALANCED_NODE_BUDGET = 200_000

// A Long is the store-set representation, so the exact search needs the store count to fit in one.
// Beyond this the local-search answer is used as-is (the app ships 20 stores; this is unreachable
// short of a roster three times over).
private const val BALANCED_MAX_STORES = 62

/**
 * One card, flattened for the subset search: its candidate listings **in pool order** (cheapest
 * first, or the pinned listing first when it has one), as parallel arrays of store-bit, price and
 * units. Scanning these in order against a store-set mask reproduces exactly what [consume] does
 * with the same pool filtered to those stores -- the search and the plan it finally materialises
 * can't disagree.
 */
private class CardEntries(
    val needed: Int,
    val coverable: Int,
    val storeBits: LongArray,
    val prices: DoubleArray,
    val units: IntArray,
)

/**
 * Total card spend when only the stores in [mask] are ordered from, or null when [mask] can't
 * cover as much as the full store set can. Allocation-free and early-exiting: this runs once per
 * search node per bound, so it's the hot loop.
 */
private fun cardSpend(cards: Array<CardEntries>, mask: Long): Double? {
    var total = 0.0
    for (c in cards) {
        var remaining = c.needed
        var covered = 0
        var i = 0
        while (i < c.storeBits.size && remaining > 0) {
            if (c.storeBits[i] and mask != 0L) {
                val take = if (remaining < c.units[i]) remaining else c.units[i]
                if (take > 0) {
                    total += c.prices[i] * take
                    remaining -= take
                    covered += take
                }
            }
            i++
        }
        // A plan that "saves" money by quietly dropping a card the user asked for isn't an
        // alternative, it's a different order -- and the other two strategies always buy every copy
        // they can find, so this keeps all three answering the same question.
        if (covered < c.coverable) return null
    }
    return total
}

/**
 * Materialises the winning store set into real [OrderLine]s, by running the ordinary [consume] over
 * each card's pool filtered to those stores. Deliberately the slow, obvious path: it runs once, and
 * reusing [consume] is what guarantees the lines match what [cheapestPlan] would produce within the
 * same store set.
 */
private fun linesForStores(
    open: Set<String>,
    cards: List<String>,
    needed: Map<String, Int>,
    pools: Map<String, List<SearchResult>>,
): List<OrderLine> = cards.flatMap { card ->
    consume(card, needed.getValue(card), pools.getValue(card).filter { it.store in open }).first
}

// Stores that no combination of the others can replace: drop one and some card can no longer be
// fully covered. Every valid answer contains them, so they're fixed up front instead of enumerated
// over -- which is what keeps the search small on the realistic worst case (a long list where most
// cards have exactly one seller).
private fun essentialStores(storeCount: Int, cards: Array<CardEntries>): Long {
    val all = if (storeCount == 64) -1L else (1L shl storeCount) - 1
    var essential = 0L
    for (bit in 0 until storeCount) {
        val without = all and (1L shl bit).inv()
        if (cardSpend(cards, without) == null) essential = essential or (1L shl bit)
    }
    return essential
}

/**
 * **Balanced** plan: minimises `cards + delivery` rather than cards alone -- every store in the
 * plan is charged [deliveryPerStore] (default [DEFAULT_DELIVERY_ZAR]), so a cheaper listing only
 * wins if it beats the cost of the extra parcel it would add. Sits between [cheapestPlan] (which is
 * this with delivery 0) and [fewestStoresPlan] (which is this with delivery large enough to
 * dominate every price difference).
 *
 * Coverage first, cost second: the plan buys every copy the full store set could supply, same as
 * the other two strategies, so [OrderPlan.uncoveredCards] means the same thing in all three.
 *
 * Pins are honoured before the search starts, exactly as [cheapestPlan] honours them -- a pinned
 * card can only come from its pinned listing (plus, with [topUpPinnedShortfalls], the rest of any
 * shortfall from elsewhere), and its store is forced into the plan, so its delivery becomes a fixed
 * cost the rest of the choice optimises around.
 *
 * ### How it's solved
 * This is uncapacitated facility location (stores = facilities with an opening cost, cards =
 * clients): NP-hard in general, but the instances here are tiny and the only real decision is
 * *which subset of stores to order from* -- once that's fixed, each card just takes its cheapest
 * available copies from those stores. So it's solved exactly, not heuristically:
 * 1. **Reduce.** Pinned stores and [essentialStores] are forced into every answer; only the
 *    genuinely optional stores are searched over.
 * 2. **Warm start.** Steepest-descent from "every store open", dropping whichever store saves the
 *    most while coverage holds. Cheap, and usually lands on or near the optimum, which makes the
 *    bound below bite immediately.
 * 3. **Branch and bound.** Depth-first over the optional stores. At each node the bound is
 *    `delivery x storesSoFar + cheapest-possible-card-spend using every store still addable in this
 *    branch` -- a real lower bound on everything below it, so a branch that has already excluded
 *    the stores it needed dies at once, and so does one whose parcels alone reach the incumbent.
 *    Store sets are `Long` bitmasks and the card scan is allocation-free, because this loop runs
 *    tens of thousands of times per recompute.
 *
 * Measured (BalancedPlanPerfTest): single-digit milliseconds for 83 cards x 20 stores with
 * quantities, the largest search the app supports -- it recomputes on every streamed result row.
 */
fun balancedPlan(
    cards: List<String>,
    results: List<SearchResult>,
    pinnedListings: Map<String, String> = emptyMap(),
    includePartialMatches: Boolean = false,
    quantities: Map<String, Int> = emptyMap(),
    topUpPinnedShortfalls: Boolean = false,
    deliveryPerStore: Double = DEFAULT_DELIVERY_ZAR,
): OrderPlan {
    // Free parcels means there's nothing to trade off -- cheapest-per-card IS the balanced answer,
    // and the search below would have no bound to prune on.
    if (deliveryPerStore <= 0.0) {
        return cheapestPlan(cards, results, pinnedListings, includePartialMatches, quantities, topUpPinnedShortfalls, deliveryPerStore)
    }

    val uniqueCards = cards.distinct()
    val rawByCard = results.inStockOnly().groupBy { it.card }
    val exactByCard = rawByCard.mapValues { (card, ls) -> preferExactMatches(card, ls, exactOnly = !includePartialMatches) }
    val evidence = cardEvidence(results)

    val pools = uniqueCards.associateWith { card ->
        candidatePool(card, rawByCard, exactByCard, pinnedListings[card], topUpPinnedShortfalls)
    }
    val needed = uniqueCards.associateWith { quantities[it] ?: 1 }
    // What the full store set can supply per card -- the coverage every candidate subset has to match.
    val coverable = uniqueCards.associateWith { card ->
        val want = needed.getValue(card)
        want - consume(card, want, pools.getValue(card)).second
    }

    val shortfalls = uniqueCards.mapNotNull { card ->
        val want = needed.getValue(card)
        val found = coverable.getValue(card)
        if (found < want) shortfallOf(
            card, want, found, evidence[card], pinUnhonoured(pinnedListings[card], rawByCard[card]),
        ) else null
    }

    val storeNames = pools.values.flatten().map { it.store }.distinct()
    if (storeNames.isEmpty()) return OrderPlan(emptyList(), shortfalls, deliveryPerStore)
    // Store sets are Long bitmasks below, and `1L shl 64` silently wraps to bit 0 rather than
    // overflowing -- so past the cap, don't build masks at all. Unreachable with the shipped roster
    // (20 stores); the cheapest plan is the honest fallback, still labelled with the delivery
    // estimate so the UI reports the same all-in figure it would have optimised for.
    if (storeNames.size > BALANCED_MAX_STORES) {
        return cheapestPlan(cards, results, pinnedListings, includePartialMatches, quantities, topUpPinnedShortfalls, deliveryPerStore)
    }
    val storeBit = storeNames.withIndex().associate { (i, name) -> name to (1L shl i) }
    val allMask = storeNames.indices.fold(0L) { m, i -> m or (1L shl i) }

    // Cards with nothing to source (uncoverable everywhere) can't influence the choice of stores.
    val searchCards = uniqueCards.filter { coverable.getValue(it) > 0 }.map { card ->
        val pool = pools.getValue(card)
        CardEntries(
            needed = needed.getValue(card),
            coverable = coverable.getValue(card),
            storeBits = LongArray(pool.size) { storeBit.getValue(pool[it].store) },
            prices = DoubleArray(pool.size) { pool[it].priceZar ?: 0.0 },
            units = IntArray(pool.size) { pool[it].stockQty ?: 1 },
        )
    }.toTypedArray()
    if (searchCards.isEmpty()) return OrderPlan(emptyList(), shortfalls, deliveryPerStore)

    // Opening everything always achieves full coverage, so this is non-null by construction.
    val cardFloor = cardSpend(searchCards, allMask) ?: return OrderPlan(emptyList(), shortfalls, deliveryPerStore)

    val pinnedMask = uniqueCards.fold(0L) { mask, card ->
        val store = pinnedListings[card]?.let { pin -> rawByCard[card]?.firstOrNull { it.url == pin }?.store }
        if (store == null) mask else mask or storeBit.getValue(store)
    }
    val forcedMask = pinnedMask or essentialStores(storeNames.size, searchCards)

    fun costOf(mask: Long): Double? = cardSpend(searchCards, mask)?.plus(mask.countOneBits() * deliveryPerStore)

    // Warm start: steepest-descent drops from "everything open". Gives the branch and bound a tight
    // incumbent before it starts, which is most of what makes the bound prune.
    var bestMask = allMask
    var bestCost = cardFloor + allMask.countOneBits() * deliveryPerStore
    while (true) {
        var dropBit = 0L
        var dropCost = bestCost
        for (i in storeNames.indices) {
            val bit = 1L shl i
            if (bestMask and bit == 0L || forcedMask and bit != 0L) continue
            val cost = costOf(bestMask and bit.inv()) ?: continue
            if (cost < dropCost - 1e-9) {
                dropCost = cost
                dropBit = bit
            }
        }
        if (dropBit == 0L) break
        bestMask = bestMask and dropBit.inv()
        bestCost = dropCost
    }

    // Optional stores, most useful first, so the search meets good subsets (and so a tighter
    // incumbent) early; ties by name to keep the plan stable between recomputes.
    val optional = storeNames.indices
        .filter { forcedMask and (1L shl it) == 0L }
        .sortedWith(
            compareByDescending<Int> { i -> searchCards.count { c -> c.storeBits.any { it == 1L shl i } } }
                .thenBy { storeNames[it] }
        )
        .map { 1L shl it }
    // suffixMask[i] = every optional store from i onwards, i.e. what this branch can still add.
    val suffixMask = LongArray(optional.size + 1)
    for (i in optional.indices.reversed()) suffixMask[i] = suffixMask[i + 1] or optional[i]

    var nodes = 0
    fun search(from: Int, mask: Long) {
        if (nodes++ > BALANCED_NODE_BUDGET) return
        // Lower bound for everything in this branch: the parcels already committed, plus the
        // cheapest card spend still reachable using the stores that remain addable. Null means
        // this branch can't even cover what the full set covers.
        val reachable = cardSpend(searchCards, mask or suffixMask[from]) ?: return
        if (mask.countOneBits() * deliveryPerStore + reachable >= bestCost - 1e-9) return
        costOf(mask)?.let {
            if (it < bestCost - 1e-9) {
                bestCost = it
                bestMask = mask
            }
        }
        for (i in from until optional.size) search(i + 1, mask or optional[i])
    }
    search(0, forcedMask)

    val open = storeNames.filterIndexedTo(mutableSetOf()) { i, _ -> bestMask and (1L shl i) != 0L }
    return OrderPlan(
        buildStoreOrders(linesForStores(open, uniqueCards, needed, pools)),
        shortfalls,
        deliveryPerStore,
    )
}
