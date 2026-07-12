package ui

import androidx.compose.runtime.*
import data.SearchListRepo
import data.SearchResult
import data.SearchResultRepo
import data.STORES
import data.SettingsStore
import data.luckshackSearchUrl
import engine.runSearch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.BrowserBackedSearcher
import network.CardImageService
import network.UpdateInfo
import network.checkForUpdate

enum class StoreStatus { PENDING, CHECKING, DONE }
enum class UpdateCheckState { IDLE, CHECKING, UP_TO_DATE, UPDATE_FOUND }

class SearchViewModel(
    private val searchListRepo: SearchListRepo,
    private val searchResultRepo: SearchResultRepo,
    private val platformActions: PlatformActions,
    // Long-lived browser-backed searcher for The Warren (Playwright on desktop, WebView on
    // Android from Phase 11) so its session survives between search clicks. Null skips
    // browser-backed stores entirely (matches SearchEngine.runSearch's existing behavior).
    private val warrenSearcher: BrowserBackedSearcher? = null,
) {
    var query by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var statusText by mutableStateOf("")
    var completedStores by mutableStateOf(0)
    var searchedCards by mutableStateOf<List<String>>(emptyList())
    var totalStores by mutableStateOf(STORES.size)
    // Progress counters: every card-store completion increments completedCardChecks.
    var completedCardChecks by mutableStateOf(0)
    var totalCardChecks by mutableStateOf(0)

    // Add-to-search dialog: shown when the new query overlaps existing results.
    var showAddToSearchDialog by mutableStateOf(false)
    var pendingAddCount by mutableStateOf(0)       // how many new (unsearched) cards
    var pendingTotalCount by mutableStateOf(0)     // total cards in the current query
    var pendingUnavailableCount by mutableStateOf(0) // how many of the overlapping cards are currently unavailable
    private var pendingAddCards: List<String> = emptyList()
    private var pendingUnavailableCards: List<String> = emptyList()

    // "All already searched" dialog: shown when every card in the new query is already in results.
    var showAllAlreadySearchedDialog by mutableStateOf(false)
    var alreadySearchedCount by mutableStateOf(0)
    var alreadySearchedUnavailableCount by mutableStateOf(0)
    private var alreadySearchedUnavailableCards: List<String> = emptyList()

    // Search summary modal: shown when a search completes.
    var showSearchSummary by mutableStateOf(false)
    val storeStatuses = mutableStateMapOf<String, StoreStatus>()
    // How many card-queries have returned for each store (i.e. how many out of searchedCards.size).
    val storeCardCounts = mutableStateMapOf<String, Int>()
    // Stores that were rate-limited by Cloudflare during the current search.
    val cfBlockedStores = mutableStateListOf<String>()

    private var autoOpenLuckshackState by mutableStateOf(SettingsStore.getSettingBoolean("autoOpenLuckshack", false))
    var autoOpenLuckshack: Boolean
        get() = autoOpenLuckshackState
        set(value) { autoOpenLuckshackState = value; SettingsStore.setSettingBoolean("autoOpenLuckshack", value) }

    private var includePartialMatchesState by mutableStateOf(SettingsStore.getSettingBoolean("includePartialMatches", false))
    var includePartialMatches: Boolean
        get() = includePartialMatchesState
        set(value) { includePartialMatchesState = value; SettingsStore.setSettingBoolean("includePartialMatches", value) }

    val results = mutableStateListOf<SearchResult>()

    // Listing title → local image file path. Keyed by the listing's own title so each
    // distinct card (e.g. "Shadowspear" vs "Mardu Shadowspear") gets its own art.
    val images = mutableStateMapOf<String, String>()

    private var searchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var ignoreBasicLandsState by mutableStateOf(SettingsStore.getSettingBoolean("ignoreBasicLands", true))
    var ignoreBasicLands: Boolean
        get() = ignoreBasicLandsState
        set(value) { ignoreBasicLandsState = value; SettingsStore.setSettingBoolean("ignoreBasicLands", value) }

    private var enabledStoresState: Set<String> by mutableStateOf(loadEnabledStores())
    var enabledStores: Set<String>
        get() = enabledStoresState
        set(value) {
            enabledStoresState = value
            val disabled = STORES.keys.filter { it !in value }
            SettingsStore.setSetting("disabledStores", disabled.joinToString(","))
        }

    fun setStoreEnabled(store: String, enabled: Boolean) {
        enabledStores = if (enabled) enabledStores + store else enabledStores - store
    }

    private fun loadEnabledStores(): Set<String> {
        val raw = SettingsStore.getSetting("disabledStores", "").ifBlank { null }
        return if (raw != null) {
            val disabled = raw.split(",").filter { it.isNotBlank() }.toSet()
            STORES.keys.filter { it !in disabled }.toSet()
        } else {
            val warrenOn = SettingsStore.getSettingBoolean("includeWarren", true)
            STORES.keys.filter { it != "The Warren" || warrenOn }.toSet()
        }
    }

    private var earlyAccessState by mutableStateOf(SettingsStore.getSettingBoolean("earlyAccess", false))
    var earlyAccess: Boolean
        get() = earlyAccessState
        set(value) { earlyAccessState = value; SettingsStore.setSettingBoolean("earlyAccess", value) }

    // ── Order list UI state (hoisted here so tab switches don't reset it) ───────
    val pinnedListings = mutableStateMapOf<String, String>()  // card name -> pinned listing URL
    val uncheckedOrderLines = mutableStateMapOf<String, Unit>()
    val excludedCards = mutableStateMapOf<String, Unit>()     // card name -> excluded from order plans
    val refreshingCards = mutableStateMapOf<String, Unit>()   // cards currently being individually re-searched
    var orderPriceFilter by mutableStateOf("")                // numeric string; lines above this price are inactive
    private var orderStrategyState by mutableStateOf(OrderStrategy.CHEAPEST)
    var orderStrategy: OrderStrategy
        get() = orderStrategyState
        set(value) { orderStrategyState = value }

    private var hoverOnThumbnailOnlyState by mutableStateOf(SettingsStore.getSettingBoolean("hoverOnThumbnailOnly", false))
    var hoverOnThumbnailOnly: Boolean
        get() = hoverOnThumbnailOnlyState
        set(value) { hoverOnThumbnailOnlyState = value; SettingsStore.setSettingBoolean("hoverOnThumbnailOnly", value) }

    private var showCardOnHoverState by mutableStateOf(SettingsStore.getSettingBoolean("showCardOnHover", false))
    var showCardOnHover: Boolean
        get() = showCardOnHoverState
        set(value) { showCardOnHoverState = value; SettingsStore.setSettingBoolean("showCardOnHover", value) }

    // ── Search monitor (background interval checker) ────────────────────────────
    // One shared config: a card query + a store selection + an interval, checked on a
    // repeating timer while the app is open. New/changed listings queue a popup alert;
    // still-in-stock listings already alerted on are deduped via monitorSeenKeys.
    private val monitorJson = Json { ignoreUnknownKeys = true }

    private var monitorQueryState by mutableStateOf(SettingsStore.getSetting("monitorCardQuery", ""))
    private var monitorQueryDebounceJob: Job? = null
    var monitorQuery: String
        get() = monitorQueryState
        set(value) {
            monitorQueryState = value
            SettingsStore.setSetting("monitorCardQuery", value)
            // Debounce: wait for typing to settle for 10s, then recheck immediately with the new
            // query and reset the interval countdown, so edits don't queue up a check per keystroke.
            monitorQueryDebounceJob?.cancel()
            if (monitorEnabled) {
                monitorQueryDebounceJob = scope.launch {
                    delay(10_000)
                    stopMonitorLoop()
                    startMonitorLoop()
                }
            }
        }

    private var monitorIntervalHoursState by mutableStateOf(
        (SettingsStore.getSetting("monitorIntervalHours", "1").toIntOrNull() ?: 1).coerceAtLeast(1)
    )
    var monitorIntervalHours: Int
        get() = monitorIntervalHoursState
        set(value) {
            val v = value.coerceAtLeast(1)
            monitorIntervalHoursState = v
            SettingsStore.setSetting("monitorIntervalHours", v.toString())
        }

    private var monitorEnabledState by mutableStateOf(SettingsStore.getSettingBoolean("monitorEnabled", false))
    var monitorEnabled: Boolean
        get() = monitorEnabledState
        set(value) {
            monitorEnabledState = value
            SettingsStore.setSettingBoolean("monitorEnabled", value)
            if (value) {
                startMonitorLoop()
            } else {
                monitorQueryDebounceJob?.cancel()
                stopMonitorLoop()
            }
        }

    private var monitorStoresState: Set<String> by mutableStateOf(loadMonitorStores())
    var monitorStores: Set<String>
        get() = monitorStoresState
        set(value) {
            monitorStoresState = value
            val disabled = STORES.keys.filter { it !in value }
            SettingsStore.setSetting("monitorDisabledStores", disabled.joinToString(","))
        }

    fun setMonitorStoreEnabled(store: String, enabled: Boolean) {
        monitorStores = if (enabled) monitorStores + store else monitorStores - store
    }

    private fun loadMonitorStores(): Set<String> {
        val disabled = SettingsStore.getSetting("monitorDisabledStores", "")
            .split(",").filter { it.isNotBlank() }.toSet()
        return STORES.keys.filter { it !in disabled }.toSet()
    }

    var monitorLastCheckedAt by mutableStateOf(SettingsStore.getSetting("monitorLastCheckedAt", "0").toLongOrNull() ?: 0L)
        private set
    var monitorStatusText by mutableStateOf("")
        private set

    // Dedup keys ("store|title|price") for listings already alerted on — persisted so a
    // still-in-stock listing doesn't re-alert every interval; only new/changed ones do.
    private val monitorSeenKeys: MutableSet<String> = runCatching {
        monitorJson.decodeFromString<List<String>>(SettingsStore.getSetting("monitorSeenListingsJson", "[]"))
    }.getOrDefault(emptyList()).toMutableSet()

    private fun saveMonitorSeenKeys() {
        SettingsStore.setSetting("monitorSeenListingsJson", monitorJson.encodeToString(monitorSeenKeys.toList()))
    }

    // Found-card alerts waiting to be shown — all surfaced together in one scrollable modal.
    // Art is looked up reactively from the shared `images` map (keyed by listing title, falling
    // back to the plain card name), not baked into the alert, so a thumbnail that resolves after
    // the alert is already queued still pops in once its image job completes.
    val monitorAlerts = mutableStateListOf<SearchResult>()
    fun dismissAllMonitorAlerts() { monitorAlerts.clear() }

    private var monitorLoopJob: Job? = null

    // Runs on the scope's default dispatcher (Main) — same convention as search()/refreshCard().
    // Compose state (monitorIntervalHours, monitorQuery, monitorStores, monitorAlerts, ...) is only
    // ever read/written from this thread; only the actual network/disk work below hops to IO.
    private fun startMonitorLoop() {
        if (monitorLoopJob?.isActive == true) return
        monitorLoopJob = scope.launch {
            while (isActive) {
                runCatching { runMonitorCheck() }
                delay(monitorIntervalHours.coerceAtLeast(1) * 3_600_000L)
            }
        }
    }

    private fun stopMonitorLoop() {
        monitorLoopJob?.cancel()
        monitorLoopJob = null
    }

    private suspend fun runMonitorCheck() {
        val cards = parseCardList(monitorQuery, ignoreBasicLands)
        val storesToSearch = STORES.filterKeys { it in monitorStores }
        if (cards.isEmpty() || storesToSearch.isEmpty()) return

        monitorStatusText = "Checking…"
        val imageService = CardImageService()
        val requestedTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val imageJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        var foundCount = 0
        try {
            // Card-name fallback art (mirrors search()) — resolved up front so a listing whose own
            // messy title fails to resolve still has the plain card's art to fall back to.
            imageJobs += scope.launch(Dispatchers.IO) {
                val resolved = imageService.resolveImages(cards.filter { requestedTitles.add(it) })
                if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
            }

            runSearch(
                cards = cards,
                stores = storesToSearch,
                sharedBrowserSearcher = warrenSearcher,
                onProgress = {},
                onResults = { rows ->
                    // runSearch fans results out from concurrent per-store IO coroutines, so hop
                    // back to Main before touching monitorSeenKeys/monitorAlerts (neither is
                    // thread-safe, and monitorAlerts is Compose-observed state).
                    withContext(Dispatchers.Main) {
                        rows.filter { it.title != null && it.available != false && it.store.isNotBlank() }
                            .forEach { r ->
                                val key = "${r.store}|${r.title}|${r.priceZar}"
                                if (monitorSeenKeys.add(key)) {
                                    monitorAlerts.add(r)
                                    foundCount++
                                }
                            }
                    }
                    // Resolve art for newly-seen listing titles as results stream in, same as search().
                    val newTitleHints = rows.mapNotNull { r -> r.title?.let { it to r.setHint } }
                        .filter { (title, _) -> requestedTitles.add(title) }
                        .toMap()
                    if (newTitleHints.isNotEmpty()) {
                        imageJobs += scope.launch(Dispatchers.IO) {
                            val resolved = imageService.resolveImages(newTitleHints)
                            if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                        }
                    }
                },
                onStoreComplete = {},
            )
            if (foundCount > 0) saveMonitorSeenKeys()
            imageJobs.joinAll()
        } finally {
            imageService.close()
        }
        val now = System.currentTimeMillis()
        SettingsStore.setSetting("monitorLastCheckedAt", now.toString())
        monitorLastCheckedAt = now
        monitorStatusText =
            if (foundCount == 0) "No new listings found"
            else "$foundCount new listing${if (foundCount == 1) "" else "s"} found"
    }

    init {
        if (monitorEnabledState) startMonitorLoop()
    }

    // ── Saved search lists ─────────────────────────────────────────────────────
    val savedLists: StateFlow<List<data.SavedSearchList>> get() = searchListRepo.lists

    // Name of the last list loaded (or saved) this session — used to pre-fill the "Save list"
    // name field so re-saving the same working list doesn't require retyping its name.
    var lastLoadedListName by mutableStateOf<String?>(null)
        private set

    // ── Saved search results ───────────────────────────────────────────────────
    val savedResults: StateFlow<List<data.SavedResultEntry>> get() = searchResultRepo.entries

    // Name of the last saved result loaded (or saved) this session — same purpose as
    // lastLoadedListName above, but for the Results tab.
    var lastLoadedResultName by mutableStateOf<String?>(null)
        private set

    fun saveCurrentResults(name: String, description: String) {
        val cards   = searchedCards.toList()
        val current = results.toList()
        if (cards.isEmpty() || current.isEmpty()) return
        lastLoadedResultName = name
        scope.launch(Dispatchers.IO) {
            searchResultRepo.save(
                name, description, cards, current,
                excludedCards    = excludedCards.keys.toSet(),
                uncheckedLines   = uncheckedOrderLines.keys.toSet(),
                pinnedListings   = pinnedListings.toMap(),
            )
        }
    }

    // Overwrites an existing saved result (same id) instead of creating a new entry —
    // used when the user confirms replacing a result that shares a name with another save.
    fun overwriteSavedResult(id: Int, name: String, description: String) {
        val cards   = searchedCards.toList()
        val current = results.toList()
        if (cards.isEmpty() || current.isEmpty()) return
        lastLoadedResultName = name
        scope.launch(Dispatchers.IO) {
            searchResultRepo.overwrite(
                id, name, description, cards, current,
                excludedCards    = excludedCards.keys.toSet(),
                uncheckedLines   = uncheckedOrderLines.keys.toSet(),
                pinnedListings   = pinnedListings.toMap(),
            )
        }
    }

    fun loadSavedResult(entry: data.SavedResultEntry) {
        val id = entry.id
        lastLoadedResultName = entry.name
        searchJob?.cancel()
        isSearching = false
        scope.launch {
            val loaded = withContext(Dispatchers.IO) { searchResultRepo.load(id) } ?: return@launch
            val cards        = loaded.cards
            val loadedResults = loaded.results
            // Populate the VM synchronously on the Main thread before resolving images.
            query         = cards.joinToString("\n")
            searchedCards = cards
            results.clear()
            results.addAll(loadedResults)
            images.clear()
            storeStatuses.clear()
            storeCardCounts.clear()
            completedStores      = 0
            completedCardChecks  = 0
            totalCardChecks      = 0
            totalStores          = 0
            // Restore order-list UI state
            excludedCards.clear()
            loaded.excludedCards.forEach { excludedCards[it] = Unit }
            uncheckedOrderLines.clear()
            loaded.uncheckedLines.forEach { uncheckedOrderLines[it] = Unit }
            pinnedListings.clear()
            pinnedListings.putAll(loaded.pinnedListings)
            statusText = "Loaded — ${loadedResults.count { it.title != null }} listings"
            // Resolve images per-store in parallel (mirrors live-search behaviour) so
            // thumbnails trickle in one store-group at a time instead of all at once.
            val imageService = network.CardImageService()
            try {
                val seenTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                // Card-name arts (fallback thumbnails) — one batch, runs concurrently with stores
                val cardNamesJob = scope.launch(Dispatchers.IO) {
                    val keys = cards.filter { seenTitles.add(it) }
                    if (keys.isEmpty()) return@launch
                    val resolved = imageService.resolveImages(keys)
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                }

                // Listing-title arts grouped by store — each store is its own concurrent batch
                val storeJobs = loadedResults
                    .filter { it.title != null }
                    .groupBy { it.store }
                    .map { (_, storeResults) ->
                        scope.launch(Dispatchers.IO) {
                            val hints = storeResults
                                .mapNotNull { r -> r.title?.takeIf { seenTitles.add(it) }?.let { it to r.setHint } }
                                .toMap()
                            if (hints.isEmpty()) return@launch
                            val resolved = imageService.resolveImages(hints)
                            if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                        }
                    }

                (storeJobs + cardNamesJob).forEach { it.join() }
            } finally {
                imageService.close()
            }
        }
    }

    fun deleteSavedResult(id: Int) {
        scope.launch(Dispatchers.IO) { searchResultRepo.delete(id) }
    }

    fun saveSearchList(name: String) {
        val cards = query.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (cards.isEmpty()) return
        lastLoadedListName = name
        scope.launch(Dispatchers.IO) { searchListRepo.create(name, cards) }
    }

    // Overwrites an existing list (same id) instead of creating a duplicate — used when the
    // user confirms replacing a list that shares a name with another saved list.
    fun overwriteSearchList(id: Int, name: String) {
        val cards = query.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (cards.isEmpty()) return
        lastLoadedListName = name
        scope.launch(Dispatchers.IO) { searchListRepo.update(id, name, cards) }
    }

    fun loadSearchList(list: data.SavedSearchList) {
        query = list.cards.joinToString("\n")
        lastLoadedListName = list.name
        scope.launch(Dispatchers.IO) { searchListRepo.touch(list.id) }
    }

    fun deleteSearchList(id: Int) {
        scope.launch(Dispatchers.IO) { searchListRepo.delete(id) }
    }

    fun saveEditedList(id: Int, name: String, cards: List<String>) {
        scope.launch(Dispatchers.IO) { searchListRepo.update(id, name, cards) }
    }

    var updateInfo by mutableStateOf<UpdateInfo?>(null)
    var updateCheckState by mutableStateOf(UpdateCheckState.IDLE)

    // null = idle, 0–1 = progress, -1 = error
    var downloadProgress by mutableStateOf<Float?>(null)
    var downloadPhase    by mutableStateOf("Downloading update…")
    var downloadError    by mutableStateOf<String?>(null)
    private var downloadJob: Job? = null

    // Resolved once at startup — false in dev mode, on Android (until Phase 12), or if desktop
    // can't find the installed exe next to the jar.
    val canInstallUpdate: Boolean = platformActions.canInstallUpdate()

    fun startDownload(info: UpdateInfo, onReadyToInstall: () -> Unit) {
        val url = info.downloadUrl ?: return
        downloadProgress = 0f
        downloadPhase    = "Downloading update…"
        downloadError    = null
        downloadJob = scope.launch(Dispatchers.IO) {
            val result = platformActions.triggerUpdateInstall(
                downloadUrl = url,
                onProgress = { p -> scope.launch(Dispatchers.Main) { if (downloadProgress != null) downloadProgress = p } },
                onPhase = { phase -> scope.launch(Dispatchers.Main) { downloadPhase = phase } },
            )
            withContext(Dispatchers.Main) {
                result.onSuccess { onReadyToInstall() }
                    .onFailure { e -> downloadProgress = -1f; downloadError = e.message ?: "Update failed" }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadProgress = null
        downloadPhase    = "Downloading update…"
        downloadError    = null
    }

    fun checkForUpdates() {
        if (updateCheckState == UpdateCheckState.CHECKING) return
        updateCheckState = UpdateCheckState.CHECKING
        scope.launch(Dispatchers.IO) {
            val info = runCatching { checkForUpdate(earlyAccess) }.getOrNull()
            withContext(Dispatchers.Main) {
                updateInfo = info
                updateCheckState = if (info != null) UpdateCheckState.UPDATE_FOUND else UpdateCheckState.UP_TO_DATE
            }
        }
    }

    fun dismissUpdateStatus() { updateCheckState = UpdateCheckState.IDLE }

    fun search() {
        val cards = parseCardList(query, ignoreBasicLands)
        if (cards.isEmpty()) return

        val storesToSearch = STORES.filterKeys { it in enabledStores }

        searchedCards = cards
        searchJob?.cancel()
        results.clear()
        images.clear()
        completedStores = 0
        completedCardChecks = 0
        totalCardChecks = cards.size * storesToSearch.size
        totalStores = storesToSearch.size
        storeStatuses.clear()
        storeStatuses.putAll(storesToSearch.keys.associateWith { StoreStatus.PENDING })
        storeCardCounts.clear()
        cfBlockedStores.clear()
        isSearching = true
        statusText = "Starting search…"

        // Luckshack isn't scraped (Cloudflare). If the user opted in, open each card's Luckshack
        // search in their browser now; otherwise it's available as a click-through link in the UI.
        if (autoOpenLuckshack) openLuckshackSearches(cards)

        launchCardSearch(cards, storesToSearch)
    }

    // Shared launcher for search()/searchAdditional()/refreshUnavailable() — all three only
    // differ in how they prep `results`/`searchedCards` beforehand and which cards to query.
    private fun launchCardSearch(cardsToQuery: List<String>, storesToSearch: Map<String, String>) {
        searchJob = scope.launch {
            val imageService = CardImageService()
            val requestedTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val imageJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

            try {
                // Resolve art for the (clean) searched card names up front. These are keyed by the
                // card name and act as a reliable fallback in the summary / order list / thumbnails
                // whenever a messy store listing title fails to resolve its own specific printing.
                imageJobs += scope.launch(Dispatchers.IO) {
                    val resolved = imageService.resolveImages(cardsToQuery.filter { requestedTitles.add(it) })
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                }

                runSearch(
                    cards = cardsToQuery,
                    stores = storesToSearch,
                    sharedBrowserSearcher = warrenSearcher,
                    onProgress = { storeName ->
                        withContext(Dispatchers.Main) {
                            storeStatuses[storeName] = StoreStatus.CHECKING
                            statusText = "Checking $storeName…"
                        }
                    },
                    onResults = { rows ->
                        withContext(Dispatchers.Main) {
                            results.addAll(rows)
                            // Each call to onResults is one card resolved at one store.
                            rows.firstOrNull()?.store?.takeIf { it.isNotBlank() }?.let { store ->
                                storeCardCounts[store] = (storeCardCounts[store] ?: 0) + 1
                            }
                            completedCardChecks++
                            statusText = "Checked $completedCardChecks / $totalCardChecks cards"
                        }
                        val newTitleHints = rows.mapNotNull { r -> r.title?.let { it to r.setHint } }
                            .filter { (title, _) -> requestedTitles.add(title) }
                            .toMap()
                        if (newTitleHints.isNotEmpty()) {
                            imageJobs += scope.launch(Dispatchers.IO) {
                                val resolved = imageService.resolveImages(newTitleHints)
                                if (resolved.isNotEmpty()) {
                                    withContext(Dispatchers.Main) { images.putAll(resolved) }
                                }
                            }
                        }
                    },
                    onStoreComplete = { storeName ->
                        withContext(Dispatchers.Main) {
                            storeStatuses[storeName] = StoreStatus.DONE
                            completedStores++
                        }
                    },
                    onStoreCfBlocked = { storeName ->
                        scope.launch(Dispatchers.Main) {
                            if (storeName !in cfBlockedStores) cfBlockedStores.add(storeName)
                        }
                    },
                )
                imageJobs.joinAll()
            } finally {
                imageService.close()
            }
            isSearching = false
            statusText = "Done — ${results.count { it.title != null }} listings found"
            showSearchSummary = searchedCards.size > 5
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
        cards.forEach { card -> platformActions.openUrl(luckshackSearchUrl(card)) }
    }

    // Entry point from the UI. Detects overlap with existing results and either shows
    // the add-to-search dialog or falls through to a normal full search.
    fun requestSearch() {
        val cards = parseCardList(query, ignoreBasicLands)
        if (cards.isEmpty()) return

        if (results.isNotEmpty() && searchedCards.isNotEmpty()) {
            // Map back to the canonical (originally-searched) casing so lookups against
            // results/searchedCards — both keyed by that original string — actually hit.
            val existingByLower = searchedCards.associateBy { it.lowercase() }
            val newCards = cards.filter { it.lowercase() !in existingByLower }
            val overlapCount = cards.size - newCards.size
            if (overlapCount > 0 && newCards.isEmpty()) {
                // Every card in the new query is already in the current results.
                val overlapCards = cards.mapNotNull { existingByLower[it.lowercase()] }
                alreadySearchedCount = cards.size
                alreadySearchedUnavailableCards = data.unavailableCards(overlapCards, results, includePartialMatches)
                alreadySearchedUnavailableCount = alreadySearchedUnavailableCards.size
                showAllAlreadySearchedDialog = true
                return
            }
            if (overlapCount > 0 && newCards.isNotEmpty()) {
                val overlapCards = cards.mapNotNull { existingByLower[it.lowercase()] }
                pendingAddCards = newCards
                pendingAddCount = newCards.size
                pendingTotalCount = cards.size
                pendingUnavailableCards = data.unavailableCards(overlapCards, results, includePartialMatches)
                pendingUnavailableCount = pendingUnavailableCards.size
                showAddToSearchDialog = true
                return
            }
        }

        search()
    }

    fun confirmAddToSearch() {
        showAddToSearchDialog = false
        searchAdditional(pendingAddCards)
    }

    fun confirmAddNewAndRefreshUnavailable() {
        showAddToSearchDialog = false
        refreshUnavailable(pendingUnavailableCards, pendingAddCards)
    }

    fun declineAddToSearch() {
        showAddToSearchDialog = false
        search()
    }

    fun dismissAllAlreadySearched() { showAllAlreadySearchedDialog = false }
    fun confirmResearchAll() { showAllAlreadySearchedDialog = false; search() }
    fun confirmRefreshUnavailable() { showAllAlreadySearchedDialog = false; refreshUnavailable(alreadySearchedUnavailableCards) }
    fun dismissSearchSummary() { showSearchSummary = false }

    private fun searchAdditional(newCards: List<String>) {
        if (newCards.isEmpty()) return
        val storesToSearch = STORES.filterKeys { it in enabledStores }

        searchJob?.cancel()

        // Append new cards to the already-searched set; keep existing results and images.
        searchedCards = searchedCards + newCards

        completedStores = 0
        completedCardChecks = 0
        totalCardChecks = newCards.size * storesToSearch.size
        totalStores = storesToSearch.size
        storeStatuses.clear()
        storeStatuses.putAll(storesToSearch.keys.associateWith { StoreStatus.PENDING })
        cfBlockedStores.clear()
        // storeCardCounts intentionally NOT cleared — accumulates from the previous search.
        isSearching = true
        statusText = "Adding ${newCards.size} new cards…"

        if (autoOpenLuckshack) openLuckshackSearches(newCards)

        launchCardSearch(newCards, storesToSearch)
    }

    /**
     * Re-runs cards that are already in [searchedCards] but currently have no in-stock listing
     * anywhere (see [data.unavailableCards]) — e.g. after clicking Search again on a query that's
     * already fully covered. Optionally also folds in [cardsToAdd] (brand-new cards from the same
     * query) in one pass. Old rows for [cardsToRefresh] are discarded before re-querying so stale
     * "not stocked" placeholders don't linger alongside fresh results.
     */
    fun refreshUnavailable(cardsToRefresh: List<String>, cardsToAdd: List<String> = emptyList()) {
        val allCards = (cardsToRefresh + cardsToAdd).distinct()
        if (allCards.isEmpty()) return
        val storesToSearch = STORES.filterKeys { it in enabledStores }

        searchJob?.cancel()

        // storeCardCounts already counted one onResults call per (card, store) pair for these
        // cards from their earlier search — back that out so "done/total" in the store grid
        // doesn't over-count once they're re-checked below.
        val staleCounts = results.filter { it.card in cardsToRefresh && it.store.isNotBlank() }
            .distinctBy { it.card to it.store }
            .groupingBy { it.store }
            .eachCount()
        staleCounts.forEach { (store, n) -> storeCardCounts[store] = ((storeCardCounts[store] ?: 0) - n).coerceAtLeast(0) }
        results.removeAll { it.card in cardsToRefresh }
        if (cardsToAdd.isNotEmpty()) searchedCards = searchedCards + cardsToAdd

        completedStores = 0
        completedCardChecks = 0
        totalCardChecks = allCards.size * storesToSearch.size
        totalStores = storesToSearch.size
        storeStatuses.clear()
        storeStatuses.putAll(storesToSearch.keys.associateWith { StoreStatus.PENDING })
        cfBlockedStores.clear()
        isSearching = true
        statusText = "Rechecking ${allCards.size} card${if (allCards.size == 1) "" else "s"}…"

        if (autoOpenLuckshack) openLuckshackSearches(allCards)

        launchCardSearch(allCards, storesToSearch)
    }

    fun refreshCard(card: String) {
        if (refreshingCards.containsKey(card)) return
        val storesToSearch = STORES.filterKeys { it in enabledStores }
        results.removeAll { it.card == card }
        refreshingCards[card] = Unit

        scope.launch {
            val imageService = CardImageService()
            val requestedTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val imageJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
            try {
                imageJobs += scope.launch(Dispatchers.IO) {
                    val resolved = imageService.resolveImages(listOf(card).filter { requestedTitles.add(it) })
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                }
                runSearch(
                    cards = listOf(card),
                    stores = storesToSearch,
                    sharedBrowserSearcher = warrenSearcher,
                    onProgress = {},
                    onResults = { rows ->
                        withContext(Dispatchers.Main) { results.addAll(rows) }
                        val newHints = rows.mapNotNull { r -> r.title?.let { it to r.setHint } }
                            .filter { (title, _) -> requestedTitles.add(title) }
                            .toMap()
                        if (newHints.isNotEmpty()) {
                            imageJobs += scope.launch(Dispatchers.IO) {
                                val resolved = imageService.resolveImages(newHints)
                                if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                            }
                        }
                    },
                    onStoreComplete = {},
                )
                imageJobs.forEach { it.join() }
            } finally {
                imageService.close()
                withContext(Dispatchers.Main) { refreshingCards.remove(card) }  // Unit value, key removal
            }
        }
    }

    fun cancel() {
        searchJob?.cancel()
        isSearching = false
        statusText = "Cancelled"
    }
}
