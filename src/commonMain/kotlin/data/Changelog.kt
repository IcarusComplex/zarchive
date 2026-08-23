package data

/**
 * "What's new" popup content, keyed by version, shown once on the first launch after updating to
 * that version (see [pendingWhatsNew] and `SearchViewModel.checkWhatsNew`). Baked into the binary
 * at build time -- not fetched over the network, so every entry must ship in the same release it
 * describes.
 *
 * Add an entry here as part of preparing each release: by default, ask the user for the "what's
 * new" text before tagging a release; only generate it yourself (from `git log`/`git diff` since
 * the last tag, written up as a short human-readable bullet list) if they explicitly say to.
 * Versions with no entry here are silently skipped -- the popup only ever shows for versions that
 * actually have something written.
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
