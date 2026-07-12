package monitor

import data.STORES
import data.SearchResult
import engine.runSearch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import network.BrowserBackedSearcher
import ui.SearchViewModel

/**
 * The headless core of a single "search monitor" check -- shared by desktop's in-process polling
 * loop (SearchViewModel.startMonitorLoop) and Android's WorkManager-driven MonitorWorker, so both
 * execution paths run identical search/dedup logic. No ViewModel/Activity/Compose dependency.
 *
 * [seenKeys] is mutated in place (dedup key "store|title|price") -- the caller owns persisting it.
 * [onHit] fires once per newly-seen listing (already deduped); [onRows] fires for every result
 * batch as it streams in (all rows, not just new ones) so a caller that wants to eagerly resolve
 * card art for previously-seen-but-still-displayed hits can do so.
 *
 * Returns the count of newly-seen listings found this check.
 */
suspend fun runMonitorCheck(
    query: String,
    enabledStores: Set<String>,
    ignoreBasicLands: Boolean,
    browserSearcher: BrowserBackedSearcher?,
    seenKeys: MutableSet<String>,
    onHit: suspend (SearchResult) -> Unit,
    onRows: suspend (List<SearchResult>) -> Unit = {},
): Int {
    val cards = SearchViewModel.parseCardList(query, ignoreBasicLands)
    val storesToSearch = STORES.filterKeys { it in enabledStores }
    if (cards.isEmpty() || storesToSearch.isEmpty()) return 0

    var foundCount = 0
    val lock = Mutex()
    runSearch(
        cards = cards,
        stores = storesToSearch,
        sharedBrowserSearcher = browserSearcher,
        onProgress = {},
        onResults = { rows ->
            // runSearch fans results out from concurrent per-store IO coroutines -- guard
            // seenKeys with a mutex since it's a plain (non-thread-safe) MutableSet.
            rows.filter { it.title != null && it.available != false && it.store.isNotBlank() }
                .forEach { r ->
                    val key = "${r.store}|${r.title}|${r.priceZar}"
                    val isNew = lock.withLock { seenKeys.add(key) }
                    if (isNew) {
                        foundCount++
                        onHit(r)
                    }
                }
            onRows(rows)
        },
        onStoreComplete = {},
    )
    return foundCount
}
