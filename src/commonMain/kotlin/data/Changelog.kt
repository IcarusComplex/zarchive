package data

/**
 * "What's new" popup content, keyed by version, shown once on the first launch after updating to
 * that version (see [pendingWhatsNew] and `SearchViewModel.checkWhatsNew`). Baked into the binary
 * at build time -- not fetched over the network, so every entry must ship in the same release it
 * describes.
 *
 * Add an entry here as part of preparing each release: always generate the text yourself (from
 * `git log`/`git diff` since the last tag, written up as a short human-readable bullet list) --
 * never prompt the user to write it or ask for confirmation first. Versions with no entry here
 * are silently skipped -- the popup only ever shows for versions that actually have something
 * written.
 */
val CHANGELOG: Map<String, List<String>> = mapOf(
    "1.1.14" to listOf(
        "Fixed a bug where large searches with quantities (e.g. \"4x Card Name\") could get rate-limited and stall on several stores.",
        "Added this What's New popup, shown once after each update.",
    ),
    "1.1.15" to listOf(
        "Further fixed rate-limiting on several stores when searching with larger quantities (e.g. \"30x Card Name\") -- stock checks for those stores are now paced much more conservatively.",
        "Sped up regular searches by skipping unnecessary stock checks for single-copy requests.",
        "Fixed the What's New popup not showing up for existing installs updating into this feature for the first time.",
    ),
    "1.1.16" to listOf(
        "Stock-count checks for stores that have previously rate-limited a search now automatically slow down further on later searches, instead of every store sharing the same fixed pace.",
    ),
    "1.1.17" to listOf(
        "Fixed a bug where saved-result quantities (e.g. \"4x Card Name\") could get silently reset to 1 shortly after saving, if Google Drive sync was connected.",
        "Added a \"Clear all\" button next to the search box to quickly reset the current search and results.",
    ),
    "1.1.18" to listOf(
        "Reduced rate-limiting on searches for cards with many similarly-named listings (e.g. tokens reprinted across several sets) by limiting how many of them get a detailed stock check.",
        "Added an icon to the \"Clear all\" button.",
    ),
    "1.1.19" to listOf(
        "Fixed a bug where a store's stock check could silently fail and cause the order planner to assume unlimited stock, recommending far more copies from a listing than were actually available.",
    ),
    "1.1.20" to listOf(
        "Fixed a bug where an unconfirmed stock count was still treated as unlimited when planning orders, letting a single listing get assigned far more copies than the store actually had.",
        "Fixed card art for double-sided tokens (e.g. Bloomburrow's two-sided tokens) not loading -- the two names are now recognised correctly and the first face's art is shown.",
        "Added a Diagnostics screen (Settings menu) that logs API errors and Cloudflare rate-limit backoffs, with buttons to copy the log or report it on GitHub.",
    ),
    "1.1.21" to listOf(
        "Fixed the Diagnostics screen rendering as a tiny, unreadable strip on Android instead of a proper full-screen dialog.",
        "Diagnostics entries now include the full response detail (headers and body) for API errors and Cloudflare backoffs, not just a generic message.",
        "Reduced rate-limiting on large multi-quantity searches on Shopify/WooCommerce stores by combining a store's regular browsing traffic and its stock-count checks into one shared, paced queue instead of two independent ones that could add up to more requests than the store allows.",
    ),
)

/**
 * Changelog entries strictly newer than [lastSeenVersion] and no newer than [currentVersion],
 * oldest first -- so a user who skipped several releases (e.g. the in-app updater always jumps
 * straight to the latest) sees everything they missed, not just the newest entry.
 *
 * [lastSeenVersion] blank is ambiguous by construction: it means either a genuinely fresh install
 * *or* an existing install updating for the first time since this feature shipped (every install
 * that predates `whatsNew.lastSeenVersion` existing at all looks identical to a fresh install --
 * there is no way to tell them apart from this setting alone). Silently returning nothing here
 * would permanently skip the popup for every already-existing user on the very release that
 * introduces it (confirmed live: an Android update from 1.1.13 to the 1.1.14 release that added
 * this feature showed nothing). So blank shows just the single most recent entry instead of the
 * full history -- a reasonable, low-noise default either way: a real first-time user gets a brief
 * "here's what's new," and an existing user updating into this feature for the first time gets
 * told about the release they just installed, instead of nothing.
 */
fun pendingWhatsNew(
    lastSeenVersion: String,
    currentVersion: String,
    changelog: Map<String, List<String>> = CHANGELOG,
): List<Pair<String, List<String>>> {
    val newest = changelog.entries
        .filter { (v, _) -> !isNewerVersion(v, currentVersion) }
        .maxWithOrNull(compareBy(versionComparator) { it.key })
    if (lastSeenVersion.isBlank()) return newest?.let { listOf(it.key to it.value) } ?: emptyList()
    return changelog.entries
        .filter { (v, _) -> isNewerVersion(v, lastSeenVersion) && !isNewerVersion(v, currentVersion) }
        .sortedWith(compareBy(versionComparator) { it.key })
        .map { it.key to it.value }
}

private val versionComparator = Comparator<String> { a, b ->
    fun parts(v: String) = v.split(".").mapNotNull { it.toIntOrNull() }
    val pa = parts(a)
    val pb = parts(b)
    var result = 0
    for (i in 0 until maxOf(pa.size, pb.size)) {
        val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
        if (diff != 0) { result = diff; break }
    }
    result
}
