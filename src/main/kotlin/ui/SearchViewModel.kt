package ui

import androidx.compose.runtime.*
import data.SearchResult
import data.STORES
import data.luckshackSearchUrl
import engine.runSearch
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
import network.CardImageService

enum class StoreStatus { PENDING, CHECKING, DONE }

class SearchViewModel {
    var query by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var statusText by mutableStateOf("")
    var completedStores by mutableStateOf(0)
    var searchedCards by mutableStateOf<List<String>>(emptyList())
    val totalStores = STORES.size
    val storeStatuses = mutableStateMapOf<String, StoreStatus>()
    // How many card-queries have returned for each store (i.e. how many out of searchedCards.size).
    val storeCardCounts = mutableStateMapOf<String, Int>()

    // Persisted user setting: whether a Luckshack search auto-opens in the browser.
    private val prefs = java.util.prefs.Preferences.userRoot().node("zarchive")
    private var autoOpenLuckshackState by mutableStateOf(prefs.getBoolean("autoOpenLuckshack", false))
    var autoOpenLuckshack: Boolean
        get() = autoOpenLuckshackState
        set(value) {
            autoOpenLuckshackState = value
            prefs.putBoolean("autoOpenLuckshack", value)
        }

    val results = mutableStateListOf<SearchResult>()

    // Listing title → local image file path. Keyed by the listing's own title so each
    // distinct card (e.g. "Shadowspear" vs "Mardu Shadowspear") gets its own art.
    val images = mutableStateMapOf<String, String>()

    private var searchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    private var ignoreBasicLandsState by mutableStateOf(prefs.getBoolean("ignoreBasicLands", true))
    var ignoreBasicLands: Boolean
        get() = ignoreBasicLandsState
        set(value) {
            ignoreBasicLandsState = value
            prefs.putBoolean("ignoreBasicLands", value)
        }

    private var earlyAccessState by mutableStateOf(prefs.getBoolean("earlyAccess", false))
    var earlyAccess: Boolean
        get() = earlyAccessState
        set(value) {
            earlyAccessState = value
            prefs.putBoolean("earlyAccess", value)
        }

    fun search() {
        val cards = parseCardList(query, ignoreBasicLands)
        if (cards.isEmpty()) return

        searchedCards = cards
        searchJob?.cancel()
        results.clear()
        images.clear()
        completedStores = 0
        storeStatuses.clear()
        storeStatuses.putAll(STORES.keys.associateWith { StoreStatus.PENDING })
        storeCardCounts.clear()
        isSearching = true
        statusText = "Starting search…"

        // Luckshack isn't scraped (Cloudflare). If the user opted in, open each card's Luckshack
        // search in their browser now; otherwise it's available as a click-through link in the UI.
        if (autoOpenLuckshack) openLuckshackSearches(cards)

        searchJob = scope.launch {
            val imageService = CardImageService()
            val requestedTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val imageJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

            try {
                // Resolve art for the (clean) searched card names up front. These are keyed by the
                // card name and act as a reliable fallback in the summary / order list / thumbnails
                // whenever a messy store listing title fails to resolve its own specific printing.
                imageJobs += scope.launch(Dispatchers.IO) {
                    val resolved = imageService.resolveImages(cards.filter { requestedTitles.add(it) })
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Swing) { images.putAll(resolved) }
                }

                runSearch(
                    cards = cards,
                    onProgress = { storeName ->
                        withContext(Dispatchers.Swing) {
                            storeStatuses[storeName] = StoreStatus.CHECKING
                            statusText = "Checking $storeName…"
                        }
                    },
                    onResults = { rows ->
                        withContext(Dispatchers.Swing) {
                            results.addAll(rows)
                            // Each call to onResults is one card resolved at one store.
                            rows.firstOrNull()?.store?.takeIf { it.isNotBlank() }?.let { store ->
                                storeCardCounts[store] = (storeCardCounts[store] ?: 0) + 1
                            }
                        }
                        val newTitles = rows.mapNotNull { it.title }.filter { requestedTitles.add(it) }
                        if (newTitles.isNotEmpty()) {
                            imageJobs += scope.launch(Dispatchers.IO) {
                                val resolved = imageService.resolveImages(newTitles)
                                if (resolved.isNotEmpty()) {
                                    withContext(Dispatchers.Swing) { images.putAll(resolved) }
                                }
                            }
                        }
                    },
                    onStoreComplete = { storeName ->
                        withContext(Dispatchers.Swing) {
                            storeStatuses[storeName] = StoreStatus.DONE
                            completedStores++
                            statusText = "Checked $completedStores / $totalStores stores"
                        }
                    },
                )
                imageJobs.joinAll()
            } finally {
                imageService.close()
            }
            isSearching = false
            statusText = "Done — ${results.count { it.title != null }} listings found"
        }
    }

    companion object {
        private val BASIC_LANDS = setOf("plains", "island", "swamp", "mountain", "forest",
            "wastes", "snow-covered plains", "snow-covered island", "snow-covered swamp",
            "snow-covered mountain", "snow-covered forest")

        // Parses plain "one card per line" format AND section-headed deck lists:
        //   [CREATURES]
        //   1 Shadowspear
        //   2x Lightning Bolt
        fun parseCardList(input: String, ignoreBasicLands: Boolean): List<String> =
            input.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") }
                .mapNotNull { line ->
                    // Strip leading quantity (e.g. "1 ", "2x ", "1x ")
                    val name = line.removePrefix(Regex("""^\d+[xX]?\s+""")) ?: line
                    name.trim().ifEmpty { null }
                }
                .filter { !ignoreBasicLands || it.lowercase() !in BASIC_LANDS }
                .distinct()

        private fun String.removePrefix(regex: Regex): String =
            regex.find(this)?.let { substring(it.value.length) } ?: this
    }

    private fun openLuckshackSearches(cards: List<String>) {
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                cards.forEach { card -> runCatching { desktop.browse(java.net.URI(luckshackSearchUrl(card))) } }
            }
        }
    }

    fun cancel() {
        searchJob?.cancel()
        isSearching = false
        statusText = "Cancelled"
    }
}
