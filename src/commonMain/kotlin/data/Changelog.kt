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
)

/**
 * Changelog entries strictly newer than [lastSeenVersion] and no newer than [currentVersion],
 * oldest first -- so a user who skipped several releases (e.g. the in-app updater always jumps
 * straight to the latest) sees everything they missed, not just the newest entry. Returns nothing
 * when [lastSeenVersion] is blank (a fresh install has no prior version to compare against, so
 * there's nothing "new" to announce).
 */
fun pendingWhatsNew(lastSeenVersion: String, currentVersion: String): List<Pair<String, List<String>>> {
    if (lastSeenVersion.isBlank()) return emptyList()
    return CHANGELOG.entries
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
