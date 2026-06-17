package data

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
)


enum class Platform { SHOPIFY, WOOCOMMERCE, WC_STORE_API, OPENCART, BIGCOMMERCE, PRESTASHOP, WARREN_API, BROWSER, UNKNOWN, UNREACHABLE }

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

// SA stores use two formats:
//   English:  R3,000.00  (comma = thousands sep, dot = decimal)
//   European: R30,00     (comma = decimal sep — official ZAR locale)
// Detect European format by checking if the last separator is a comma followed by
// exactly 2 digits. Everything else is treated as English/dot-decimal.
fun parsePrice(text: String): Double? {
    val raw = PRICE_RE.find(text)?.groupValues?.get(1)?.trim() ?: return null
    val euroDecimal = raw.matches(Regex("""[\d\s.]*\d,\d{2}"""))
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
private fun matchKey(s: String): String =
    s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

/**
 * Among a searched card's candidate listings, prefer the ones whose name matches the query
 * *exactly* (after normalisation, and per-face for double-faced / split cards). This stops a
 * search for "Reprieve" from surfacing "Graceful Reprieve", or "Bolt" from matching "Lightning
 * Bolt". Falls back to all candidates when nothing matches exactly, so messy titles that don't
 * normalise cleanly still show up rather than vanishing.
 */
fun preferExactMatches(card: String, listings: List<SearchResult>): List<SearchResult> {
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

fun isRelevant(card: String, title: String): Boolean {
    if (NON_SINGLE_RE.containsMatchIn(title)) return false
    val t = title.lowercase()
    val words = card.lowercase().split(Regex("[^a-z']+")).filter { it.length > 2 }
    if (words.isEmpty()) return false
    // Whole-word match (not substring) so "Hop to It" → "hop" doesn't match "Hope Thief".
    return words.all { w -> Regex("""\b${Regex.escape(w)}\b""").containsMatchIn(t) }
}

internal val NOISE_RE = Regex(
    """(?i)\b(foil|etched|borderless|extended[ -]?art|showcase|retro|promo|prerelease|""" +
    """pre-release|near[ -]?mint|lightly[ -]?played|moderately[ -]?played|heavily[ -]?played|""" +
    """nm|lp|mp|hp|sp|dmg|damaged|played|mint|english|japanese|alt(?:ernate)?[ -]?art|""" +
    """full[ -]?art|galaxy[ -]?foil|surge[ -]?foil|textured|serial(?:ized)?|game[ -]?day|buy-?a-?box)\b"""
)

/**
 * Reduce a messy store listing title to a probable card name for Scryfall lookup.
 * Strips set names in brackets/parens, treatment & condition keywords, collector numbers,
 * and trailing "- Set Name" suffixes. Not perfect — the image service falls back to a
 * fuzzy Scryfall lookup when the cleaned name doesn't match exactly.
 */
fun normalizeCardName(title: String): String {
    var s = title
    s = s.replace(Regex("""\[[^\]]*\]"""), " ")   // [Set Name]
    s = s.replace(Regex("""\([^)]*\)"""), " ")    // (PFRF), (Foil), etc.
    // "Card Name - Set Name" → keep the part before the first " - "
    val dash = s.indexOf(" - ")
    if (dash > 0) s = s.substring(0, dash)
    s = s.replace(NOISE_RE, " ")
    s = s.replace(Regex("""[#/]"""), " ")
    s = s.replace(Regex("""\b\d{1,5}\b\s*$"""), " ")   // trailing collector number
    s = s.replace(Regex("""\s+"""), " ").trim()
    return s.trim(' ', '-', '–', '—', ',', '.', ':', '*')
}
