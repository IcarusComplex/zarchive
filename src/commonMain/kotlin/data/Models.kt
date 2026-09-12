package data

import kotlinx.serialization.Serializable

@Serializable
data class SearchResult(
    val store: String,
    val card: String,
    val title: String?,
    val priceZar: Double?,
    val available: Boolean?,
    val url: String,
    val note: String,
    val variantId: Long? = null,   // Shopify variant ID, WC product/variation ID, BC product ID
    val cartToken: String? = null, // PrestaShop static_token (same across all pages for a given store)
    val setHint: String? = null,   // set name/code extracted from structured payload (body HTML, short_description, etc.)
    val stockQty: Int? = null,     // per-listing stock count when the store exposes one; null = unknown/unlimited
)

/**
 * Label for a listing's [SearchResult.stockQty] -- how many copies the store itself says it has on
 * hand. Shared so desktop and Android word it identically. Both only render it when the count is
 * non-null: most platforms only report or probe a count when the search asked for more than one
 * copy (see SearchEngine.checkStore), so an unknown count is never shown as a number.
 */
fun stockCountLabel(qty: Int): String = "$qty available"

/**
 * The note on a title-less row meaning "this store answered, and doesn't have it" -- as opposed to
 * an error/timeout/rate-limit note, which means we never got an answer at all. The order plan
 * relies on that distinction to tell "nobody stocks this card" apart from "we didn't finish
 * asking" (see `OrderOptimizer.cardEvidence`), so it's one shared constant rather than a literal
 * repeated at every producer and consumer.
 */
const val NOTE_NOT_STOCKED = "not stocked"

/**
 * Reconciles a listing whose store reports zero copies on hand: that's out of stock, whatever the
 * search/suggest payload claimed. Shopify's cart probe (".. is already sold out" -> 0) and
 * BigCommerce's `"available_to_sell":0` both return a real 0 while the product still looks
 * available in the search payload it came from. Left as-is such a row renders as "In Stock --
 * 0 available" and is then silently skipped by the order optimizer (which can take 0 copies from
 * it), so the card lands in "Not fully available" with no visible reason. Applied centrally in
 * `SearchEngine.checkStore`, so every platform's rows agree on what 0 means.
 */
fun SearchResult.reconcileZeroStock(): SearchResult =
    if (stockQty == 0) copy(available = false, stockQty = null, note = "Out of stock") else this


enum class Platform { SHOPIFY, WOOCOMMERCE, WC_STORE_API, OPENCART, BIGCOMMERCE, PRESTASHOP, WARREN_API, UNTAPPED_API, BROWSER, UNKNOWN, UNREACHABLE }

val STORES: Map<String, String> = linkedMapOf(
    "A.I. Fest"              to "https://store.ai-fest.co.za",
    "Battle Wizards"         to "https://www.battlewizards.co.za",
    "D20 Battleground"       to "http://d20battleground.co.za",
    "Dracoti"                to "https://shop.dracoti.co.za",
    "Geek Home"              to "http://www.geekhome.co.za",
    "The Warren"             to "https://thewarren.co.za",
    "Underworld Connections" to "http://www.underworldconnections.co.za",
    "The Trade Inn"          to "https://thetradeinn.co.za",
    "Andres Side Hustle"     to "https://www.andressidehustle.co.za",
    "The Card Cache"         to "https://cardcache.co.za",
    "Knightly Gaming"        to "https://www.knightlygaming.co.za",
    "The Hidden Realm"       to "https://www.thehiddenrealm.co.za",
    "Wzard TCG"              to "https://wzrd.co.za",
    "Armchair Generals"      to "https://armchairgenerals.co.za",
    "Sword&Board"            to "https://swordandboard.co.za",
    "BigBang"                to "https://bigbangshop.co.za",
    "GreedyGold"             to "https://greedygold.co.za",
    "Magic and Monsters"     to "https://magicandmonsters.co.za",
    "The Cantina"            to "https://thecantina.co.za",
    "Untapped Potential TCG" to "https://untappedpotentialtcg.co.za",
)

// Luckshack is Cloudflare-protected and we don't scrape its stock — it's surfaced only as a
// per-card convenience link (outside the normal results), never as a search result or store.
const val LUCKSHACK_NAME = "The Luckshack"
fun luckshackSearchUrl(card: String): String =
    "https://luckshack.co.za/index.php?route=product/asearch&search=" +
        java.net.URLEncoder.encode(card, "UTF-8")

/**
 * Pre-known platforms — all confirmed stores pinned here so the concurrent 19-store detection
 * burst doesn't trigger Cloudflare challenges. Detection is only attempted for new/unknown stores.
 */
val KNOWN_PLATFORMS: Map<String, Platform> = mapOf(
    // Special-case platforms
    "https://thewarren.co.za"                to Platform.BROWSER,
    "https://www.battlewizards.co.za"        to Platform.BIGCOMMERCE,
    "https://store.ai-fest.co.za"            to Platform.PRESTASHOP,
    "https://www.thehiddenrealm.co.za"       to Platform.WC_STORE_API,
    // Untapped Potential TCG migrated off Shopify to a custom storefront backed by a Supabase
    // RPC search endpoint (verified 2026-08) — see network.searchUntappedPotential.
    "https://untappedpotentialtcg.co.za"     to Platform.UNTAPPED_API,
    // Shopify stores (verified 2026-06)
    "http://d20battleground.co.za"           to Platform.SHOPIFY,
    "https://thetradeinn.co.za"              to Platform.SHOPIFY,
    "https://cardcache.co.za"                to Platform.SHOPIFY,
    "https://www.knightlygaming.co.za"       to Platform.SHOPIFY,
    "https://wzrd.co.za"                     to Platform.SHOPIFY,
    "https://armchairgenerals.co.za"         to Platform.SHOPIFY,
    "https://swordandboard.co.za"            to Platform.SHOPIFY,
    "https://bigbangshop.co.za"              to Platform.SHOPIFY,
    "https://greedygold.co.za"               to Platform.SHOPIFY,
    "https://magicandmonsters.co.za"         to Platform.SHOPIFY,
    "https://thecantina.co.za"               to Platform.SHOPIFY,
    "http://www.underworldconnections.co.za" to Platform.SHOPIFY,
    // WooCommerce stores (verified 2026-06)
    "https://www.andressidehustle.co.za"     to Platform.WOOCOMMERCE,
    "https://shop.dracoti.co.za"             to Platform.WOOCOMMERCE,
    "http://www.geekhome.co.za"              to Platform.WOOCOMMERCE,
)

private val PRICE_RE = Regex("""R\s?([\d\s,.]+)""")
private val EURO_DECIMAL_RE = Regex("""[\d\s.]*\d,\d{2}""")

// SA stores use two formats:
//   English:  R3,000.00  (comma = thousands sep, dot = decimal)
//   European: R30,00     (comma = decimal sep — official ZAR locale)
// Detect European format by checking if the last separator is a comma followed by
// exactly 2 digits. Everything else is treated as English/dot-decimal.
fun parsePrice(text: String): Double? {
    val raw = PRICE_RE.find(text)?.groupValues?.get(1)?.trim() ?: return null
    val euroDecimal = raw.matches(EURO_DECIMAL_RE)
    return if (euroDecimal) {
        raw.replace(".", "").replace(" ", "").replace(",", ".").toDoubleOrNull()
    } else {
        raw.replace(",", "").replace(" ", "").toDoubleOrNull()
    }
}

// Accessories / sealed product that often share a card's name in their title but are NOT
// singles (e.g. a "Ultimate Guard Zipfolio … Season of the Burrow" binder matching the card
// "Season of the Burrow"). Phrases are multi-word/brand-specific on purpose so they don't
// clobber real card names that happen to contain a word like "booster" (e.g. Booster Tutor).
private val NON_SINGLE_RE = Regex(
    """(?i)\b(binder|portfolio|zipfolio|ultimate[ -]?guard|dragon[ -]?shield|""" +
    """gamegenic|playmat|play[ -]mat|mouse[ -]?pad|deck[ -]?box|deck[ -]?protector|""" +
    """card[ -]?sleeves|\bsleeves\b|toploader|top[ -]loader|storage[ -]?box|card[ -]?case|""" +
    """booster[ -](?:box|pack|bundle|case)|(?:collector|set|draft|jumpstart)[ -]booster|""" +
    """booster[ -]display|fat[ -]?pack|bundle[ -]?box|gift[ -]?bundle|prerelease[ -]?(?:pack|kit)|""" +
    """commander[ -]?deck|starter[ -]?deck|planeswalker[ -]?deck|intro[ -]?pack|""" +
    """booster[ -]?display|dice[ -]?set|life[ -]?counter|spindown)\b"""
)

// Collapse to a comparable key: lowercase, punctuation → spaces, trimmed.
private val MATCH_KEY_RE = Regex("[^a-z0-9]+")

private fun matchKey(s: String): String =
    s.lowercase().replace(MATCH_KEY_RE, " ").trim()

/**
 * Among a searched card's candidate listings, prefer the ones whose name matches the query
 * *exactly* (after normalisation, and per-face for double-faced / split cards). This stops a
 * search for "Reprieve" from surfacing "Graceful Reprieve", or "Bolt" from matching "Lightning
 * Bolt". Falls back to all candidates when nothing matches exactly, so messy titles that don't
 * normalise cleanly still show up rather than vanishing.
 */
fun preferExactMatches(card: String, listings: List<SearchResult>, exactOnly: Boolean = true): List<SearchResult> {
    if (!exactOnly) return listings
    if (listings.size <= 1) return listings
    val want = matchKey(card)
    if (want.isEmpty()) return listings
    val exact = listings.filter { r ->
        val title = r.title ?: return@filter false
        val norm = normalizeCardName(title)
        // Split the raw title by "//" before normalising so DFC face names are checked
        // individually — normalizeCardName strips "/" which would otherwise erase the boundary.
        matchKey(norm) == want || title.split("//").any { matchKey(normalizeCardName(it.trim())) == want }
    }
    return if (exact.isNotEmpty()) exact else listings
}

/**
 * Per-card facts the card-summary panel renders: the exact-match listing titles (image lookup),
 * whether anything is in/out of stock, and whether the card is still awaiting its first result.
 *
 * Exists so the panel computes this **once per results change** instead of once per card per
 * recomposition. [preferExactMatches] normalises every listing title, and on a large streaming
 * search the panel recomposes on every arriving row -- doing it inline per entry meant re-running
 * the whole normalisation over every listing many times a second on the UI thread.
 */
class CardSummaryFacts(
    val titles: List<String>,
    val hasInStock: Boolean,
    val hasOutOfStock: Boolean,
    val pending: Boolean,
)

fun cardSummaryFacts(
    cards: List<String>,
    results: List<SearchResult>,
    includePartialMatches: Boolean,
): Map<String, CardSummaryFacts> {
    val byCard = results.groupBy { it.card }
    return cards.associateWith { card ->
        val all = byCard[card].orEmpty()
        val listings = preferExactMatches(card, all.filter { it.title != null }, exactOnly = !includePartialMatches)
        val inStock = listings.any { it.available != false }
        CardSummaryFacts(
            titles = listings.mapNotNull { it.title },
            hasInStock = inStock,
            hasOutOfStock = !inStock && listings.any { it.available == false },
            pending = all.isEmpty(),
        )
    }
}

private val WORD_SPLIT_RE = Regex("[^a-z']+")

fun isRelevant(card: String, title: String): Boolean {
    if (NON_SINGLE_RE.containsMatchIn(title)) return false
    // Match against the set-name-stripped title, not the raw one — otherwise a query word that
    // only appears inside a bracketed/appended set name (e.g. "Gate" in "Imoen, Mystic Trickster
    // [Commander Legends: Battle for Baldur's Gate]") falsely satisfies a whole-word check for an
    // unrelated card like "Mystic Gate".
    val t = normalizeCardName(title).lowercase()
    val words = card.lowercase().split(WORD_SPLIT_RE).filter { it.length > 2 }
    if (words.isEmpty()) return false
    // Whole-word match (not substring) so "Hop to It" → "hop" doesn't match "Hope Thief".
    return words.all { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(t) }
}

internal val NOISE_RE = Regex(
    """(?i)\b(foil|etched|borderless|extended[ -]?art|showcase|retro|promo|prerelease|""" +
    """pre-release|near[ -]?mint|lightly[ -]?played|moderately[ -]?played|heavily[ -]?played|""" +
    """nm|lp|mp|hp|sp|dmg|damaged|played|mint|english|japanese|alt(?:ernate)?[ -]?art|""" +
    """full[ -]?art|galaxy[ -]?foil|surge[ -]?foil|textured|serial(?:ized)?|game[ -]?day|buy-?a-?box|""" +
    """double[ -]?sided(?:[ -]?token)?|dfc)\b"""
)

// Hoisted: normalizeCardNameSingle runs once per listing title, and the card summary re-derives
// those on every streamed result. Compiling these five patterns per call is ~20% of the function's
// cost and allocates a Pattern + Matcher per listing per pass -- pure churn for constant patterns.
private val BRACKET_GROUP_RE = Regex("""\[[^\]]*\]""")
private val PAREN_GROUP_RE   = Regex("""\([^)]*\)""")
private val HASH_SLASH_RE    = Regex("""[#/]""")
private val TRAILING_NUM_RE  = Regex("""\b\d{1,5}\b\s*$""")
private val WHITESPACE_RE    = Regex("""\s+""")

/**
 * Reduce a messy store listing title to a probable card name for Scryfall lookup.
 * Strips set names in brackets/parens, treatment & condition keywords, collector numbers,
 * and trailing "- Set Name" suffixes. Not perfect — the image service falls back to a
 * fuzzy Scryfall lookup when the cleaned name doesn't match exactly.
 */
private fun normalizeCardNameSingle(title: String): String {
    var s = title
    s = s.replace(BRACKET_GROUP_RE, " ")   // [Set Name]
    s = s.replace(PAREN_GROUP_RE, " ")     // (PFRF), (Foil), etc.
    // "Card Name - Set Name" → keep the part before the first " - "
    val dash = s.indexOf(" - ")
    if (dash > 0) s = s.substring(0, dash)
    s = s.replace(NOISE_RE, " ")
    s = s.replace(HASH_SLASH_RE, " ")
    s = s.replace(TRAILING_NUM_RE, " ")    // trailing collector number
    s = s.replace(WHITESPACE_RE, " ").trim()
    return s.trim(' ', '-', '–', '—', ',', '.', ':', '*')
}

/**
 * Like [normalizeCardNameSingle], but preserves a double-faced/double-sided-token " // "
 * separator instead of destroying it — each face is cleaned independently and rejoined, so
 * e.g. "Rabbit // Splash Lasher Double-Sided Token [Bloomburrow Tokens]" becomes
 * "Rabbit // Splash Lasher" rather than mangling both face names into one unresolvable string.
 */
fun normalizeCardName(title: String): String =
    if (" // " in title) title.split(" // ").joinToString(" // ") { normalizeCardNameSingle(it) }
    else normalizeCardNameSingle(title)
