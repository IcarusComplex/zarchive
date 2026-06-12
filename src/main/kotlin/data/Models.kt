package data

data class SearchResult(
    val store: String,
    val card: String,
    val title: String?,
    val priceZar: Double?,
    val available: Boolean?,
    val url: String,
    val note: String,
)


enum class Platform { SHOPIFY, WOOCOMMERCE, WC_STORE_API, OPENCART, BIGCOMMERCE, PRESTASHOP, WARREN_API, BROWSER, UNKNOWN, UNREACHABLE }

val STORES: Map<String, String> = linkedMapOf(
    "A.I. Fest"              to "https://store.ai-fest.co.za",
    "Battle Wizards"         to "https://www.battlewizards.co.za",
    "D20 Battleground"       to "http://d20battleground.co.za",
    "Dracoti"                to "http://www.dracoti.co.za",
    "Geek Home"              to "http://www.geekhome.co.za",
    "The Luckshack"          to "https://luckshack.co.za",
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

/** Pre-known platforms — confirmed stores where auto-detection fails (e.g. Cloudflare-blocked). */
val KNOWN_PLATFORMS: Map<String, Platform> = mapOf(
    "https://luckshack.co.za"          to Platform.BROWSER,
    "https://thewarren.co.za"          to Platform.BROWSER,
    "https://www.battlewizards.co.za"  to Platform.BIGCOMMERCE,
    "https://store.ai-fest.co.za"      to Platform.PRESTASHOP,
    "https://www.thehiddenrealm.co.za" to Platform.WC_STORE_API,
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

fun isRelevant(card: String, title: String): Boolean {
    val words = card.lowercase().split(Regex("[^a-z']+")).filter { it.length > 2 }
    val t = title.lowercase()
    return words.all { it in t }
}

private val NOISE_RE = Regex(
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
