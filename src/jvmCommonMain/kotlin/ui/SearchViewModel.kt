package ui

import androidx.compose.runtime.*
import collection.CollectionImportEngine
import data.CollectionGroupSummary
import data.CollectionRepo
import data.SearchListRepo
import data.SearchResult
import data.SearchResultRepo
import data.STORES
import data.SettingsStore
import data.luckshackSearchUrl
import engine.runSearch
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.BrowserBackedSearcher
import network.CardImageService
import network.UpdateInfo
import network.UpdateCheckResult
import network.checkForUpdate
import sync.SyncEngine

enum class StoreStatus { PENDING, CHECKING, DONE }
enum class UpdateCheckState { IDLE, CHECKING, UP_TO_DATE, UPDATE_FOUND, CHECK_FAILED }
enum class SyncStatus { DISCONNECTED, IDLE, SYNCING, SYNCED, ERROR }
enum class CollectionImportStatus { IDLE, IMPORTING, ERROR }

private const val SYNC_MIN_INTERVAL_MS = 5 * 60 * 1000L

class SearchViewModel(
    private val searchListRepo: SearchListRepo,
    private val searchResultRepo: SearchResultRepo,
    private val collectionRepo: CollectionRepo,
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

    // Order-list filter: skip cards already in the imported collection when building a buying
    // plan, since there's no need to order what you already own.
    private var excludeOwnedFromOrdersState by mutableStateOf(SettingsStore.getSettingBoolean("orders.excludeOwned", false))
    var excludeOwnedFromOrders: Boolean
        get() = excludeOwnedFromOrdersState
        set(value) { excludeOwnedFromOrdersState = value; SettingsStore.setSettingBoolean("orders.excludeOwned", value) }

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
            // Only applies where the monitor runs as an in-process loop (desktop) -- Android's
            // WorkManager-driven monitor reads monitorQuery fresh on every scheduled run instead.
            monitorQueryDebounceJob?.cancel()
            if (monitorEnabled && monitor.runsMonitorLoopInProcess) {
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
            // The in-process loop only runs on desktop -- Android relies solely on a WorkManager
            // periodic job (scheduled/cancelled reactively from AndroidApp) so backgrounding or
            // killing the app doesn't stop monitor checks.
            if (monitor.runsMonitorLoopInProcess) {
                if (value) {
                    startMonitorLoop()
                } else {
                    monitorQueryDebounceJob?.cancel()
                    stopMonitorLoop()
                }
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
    var monitorStatusText by mutableStateOf(SettingsStore.getSetting("monitorLastCheckStatus", ""))
        private set

    // On Android the monitor actually runs out-of-process in MonitorWorker (see
    // monitor/MonitorPlatform.android.kt), which persists its own "last checked"/status directly to
    // SettingsStore -- this ViewModel instance has no live view into that. Not worth a continuous
    // sync channel for a status line; re-reading once on app start (called from AndroidApp's
    // startup LaunchedEffect block) is enough to reflect the latest background check whenever the
    // user opens the app.
    fun refreshMonitorStatusFromSettings() {
        monitorLastCheckedAt = SettingsStore.getSetting("monitorLastCheckedAt", "0").toLongOrNull() ?: 0L
        monitorStatusText = SettingsStore.getSetting("monitorLastCheckStatus", "")
        refreshMonitorHistory()
    }

    // Trail of the last ~10 completed checks (success and failure), newest first -- see
    // monitor/MonitorHistory.kt. Lets a user who can't tell whether background checks are actually
    // happening (Android especially, since MonitorWorker runs out-of-process) see exactly when each
    // one ran and what it found, instead of only ever seeing the single latest status line.
    val monitorHistory = mutableStateListOf<monitor.MonitorCheckEntry>().apply { addAll(monitor.loadMonitorHistory()) }
    fun refreshMonitorHistory() {
        monitorHistory.clear()
        monitorHistory.addAll(monitor.loadMonitorHistory())
    }

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

    // Android's monitor hits can arrive from MonitorWorker -- an out-of-process check that never
    // touches this ViewModel's `images` map at all (unlike runMonitorCheck()'s in-process wrapper
    // below, which resolves art as part of the same check). Called from AndroidApp whenever hits
    // reach the UI via MonitorHitBus or a tapped notification's deep link, so their thumbnails
    // don't stay permanently blank in a freshly (re)launched process.
    fun resolveImagesForMonitorHits(hits: List<SearchResult>) {
        if (hits.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val imageService = CardImageService()
            try {
                val titleHints = hits.mapNotNull { r -> r.title?.let { it to r.setHint } }.toMap()
                if (titleHints.isNotEmpty()) {
                    val resolved = imageService.resolveImages(titleHints)
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                }
                // Card-name fallback (mirrors search()/runMonitorCheck()) for any hit whose own
                // messy listing title fails to resolve.
                val resolvedCards = imageService.resolveImages(hits.map { it.card }.distinct())
                if (resolvedCards.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolvedCards) }
            } finally {
                imageService.close()
            }
        }
    }

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

    // Thin wrapper around the shared, headless monitor/MonitorCheck.kt logic (also used by
    // Android's WorkManager-driven MonitorWorker) -- this method owns everything Compose-observed
    // (monitorStatusText, images, monitorAlerts) that the shared function has no business touching.
    private suspend fun runMonitorCheck() {
        val cards = parseCardList(monitorQuery, ignoreBasicLands)
        val storesToSearch = STORES.filterKeys { it in monitorStores }
        if (cards.isEmpty() || storesToSearch.isEmpty()) return

        monitorStatusText = "Checking…"
        val imageService = CardImageService()
        val requestedTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        val imageJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()
        var foundCount = 0
        // A check that throws (network blip, browser hiccup, etc.) must still land a status/history
        // entry -- previously an uncaught exception here skipped straight past the
        // monitorLastCheckedAt/monitorStatusText writes below, was swallowed by startMonitorLoop's
        // runCatching, and left no trace that the run ever happened.
        var failure: Throwable? = null
        try {
            // Card-name fallback art (mirrors search()) — resolved up front so a listing whose own
            // messy title fails to resolve still has the plain card's art to fall back to.
            imageJobs += scope.launch(Dispatchers.IO) {
                val resolved = imageService.resolveImages(cards.filter { requestedTitles.add(it) })
                if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
            }

            foundCount = monitor.runMonitorCheck(
                query = monitorQuery,
                enabledStores = monitorStores,
                ignoreBasicLands = ignoreBasicLands,
                browserSearcher = warrenSearcher,
                seenKeys = monitorSeenKeys,
                onHit = { r -> withContext(Dispatchers.Main) { monitorAlerts.add(r) } },
                onRows = { rows ->
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
            )
            if (foundCount > 0) saveMonitorSeenKeys()
            imageJobs.joinAll()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            imageService.close()
        }
        val now = System.currentTimeMillis()
        monitorStatusText = when {
            failure != null -> "Check failed: ${failure.message ?: failure::class.simpleName}"
            foundCount == 0 -> "No new listings found"
            else -> "$foundCount new listing${if (foundCount == 1) "" else "s"} found"
        }
        SettingsStore.setSetting("monitorLastCheckedAt", now.toString())
        monitorLastCheckedAt = now
        // Persisted alongside monitorLastCheckedAt so refreshMonitorStatusFromSettings() (Android's
        // app-start resync, since MonitorWorker runs out-of-process) has a real status to show, not
        // just a timestamp.
        SettingsStore.setSetting("monitorLastCheckStatus", monitorStatusText)
        monitor.recordMonitorCheck(now, monitorStatusText, foundCount, failure == null)
        refreshMonitorHistory()
    }

    init {
        if (monitorEnabledState && monitor.runsMonitorLoopInProcess) startMonitorLoop()

        // Android can kill this whole process while the app is merely backgrounded (Samsung's
        // battery manager is particularly aggressive about it) -- unlike a config change
        // (Phase-13's configChanges fix), that's not something any Activity-lifecycle flag can
        // prevent, so the only real fix is persisting the in-progress session and restoring it on
        // the next cold launch. Reactive + debounced (not "only after a full search completes") so
        // a kill mid-search still leaves a recoverable snapshot. Desktop writes this too (harmless,
        // cheap) but never reads it back -- its process only ever ends via an explicit user quit.
        scope.launch {
            sessionSnapshotFlow().collect { (q, cards, res) -> persistSession(q, cards, res) }
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun sessionSnapshotFlow() =
        snapshotFlow { Triple(query, searchedCards.toList(), results.toList()) }.debounce(2_000)

    private val sessionJson = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class SessionSnapshot(val query: String, val searchedCards: List<String>, val results: List<SearchResult>)

    private fun persistSession(q: String, cards: List<String>, res: List<SearchResult>) {
        SettingsStore.setSetting("session.snapshot", sessionJson.encodeToString(SessionSnapshot(q, cards, res)))
    }

    /**
     * Restores the last in-progress search after this process was killed and relaunched from
     * scratch (Android only -- see the [init] block comment). A no-op if there's nothing to
     * restore or a search is already in flight (e.g. called twice, or a hot launch).
     */
    fun restoreSessionIfAny() {
        if (query.isNotEmpty() || results.isNotEmpty()) return
        val raw = SettingsStore.getSetting("session.snapshot", "").ifBlank { return }
        val snapshot = runCatching { sessionJson.decodeFromString<SessionSnapshot>(raw) }.getOrNull() ?: return
        if (snapshot.results.isEmpty() && snapshot.searchedCards.isEmpty()) return

        query = snapshot.query
        searchedCards = snapshot.searchedCards
        results.addAll(snapshot.results)
        statusText = "Restored — ${snapshot.results.count { it.title != null }} listings"

        // Image paths aren't persisted (they're deterministic disk-cache lookups, cheap to redo)
        // -- mirrors loadSavedResult's per-store-batched resolution.
        scope.launch {
            val imageService = CardImageService()
            try {
                val seenTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
                val cardNamesJob = scope.launch(Dispatchers.IO) {
                    val keys = snapshot.searchedCards.filter { seenTitles.add(it) }
                    if (keys.isEmpty()) return@launch
                    val resolved = imageService.resolveImages(keys)
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Main) { images.putAll(resolved) }
                }
                val storeJobs = snapshot.results
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

    // ── Google Drive sync ────────────────────────────────────────────────────────
    private val syncEngine = SyncEngine(searchListRepo, searchResultRepo)

    var syncStatus by mutableStateOf(if (syncEngine.isConnected) SyncStatus.IDLE else SyncStatus.DISCONNECTED)
        private set
    var syncAccountEmail by mutableStateOf(syncEngine.accountEmail)
        private set
    var syncError by mutableStateOf<String?>(null)
        private set
    private var syncDebounceJob: Job? = null

    // Passive/automatic sync triggers (app launch, coming back to the foreground -- e.g. Android
    // bringing the app back after the user alt-tabs, or desktop minimize/restore) are cooldown-limited
    // to at most once per 5 minutes so they don't hammer Drive on every window focus change.
    // Persisted (not just in-memory) so a relaunch shortly after the last sync doesn't immediately
    // fire another one either. Explicit user actions -- editing/saving a list (markDirtyAndScheduleSync)
    // or "Sync now" -- always bypass this cooldown; the user just made a change and expects it to sync.
    private var lastSyncAttemptAt: Long
        get() = SettingsStore.getSetting("sync.lastAttemptAt", "0").toLongOrNull() ?: 0L
        set(value) = SettingsStore.setSetting("sync.lastAttemptAt", value.toString())

    /** Called once on app launch (next to [checkForUpdates]) -- silently does nothing if not connected. */
    fun syncOnLaunch() {
        if (!syncEngine.isConnected) return
        scope.launch(Dispatchers.IO) { runSyncThrottled() }
    }

    // Called at the end of every list/result mutation. Debounced so a burst of edits (e.g. typing
    // out a new list) coalesces into one round-trip instead of one per keystroke/click. This is an
    // explicit user edit, not an automatic background trigger, so it bypasses the 5-minute cooldown
    // and always syncs once the debounce settles.
    private fun markDirtyAndScheduleSync() {
        if (!syncEngine.isConnected) return
        syncDebounceJob?.cancel()
        syncDebounceJob = scope.launch(Dispatchers.IO) {
            delay(3_000)
            runSync()
        }
    }

    private suspend fun runSyncThrottled() {
        if (System.currentTimeMillis() - lastSyncAttemptAt < SYNC_MIN_INTERVAL_MS) return
        runSync()
    }

    private suspend fun runSync() {
        lastSyncAttemptAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) { syncStatus = SyncStatus.SYNCING; syncError = null }
        val result = syncEngine.syncNow()
        withContext(Dispatchers.Main) {
            result.onSuccess { syncStatus = SyncStatus.SYNCED }
                .onFailure { e -> syncStatus = SyncStatus.ERROR; syncError = e.message }
        }
    }

    fun connectGoogleDrive(onDone: (Result<Unit>) -> Unit) {
        syncStatus = SyncStatus.SYNCING
        syncError = null
        scope.launch(Dispatchers.IO) {
            val result = syncEngine.connect()
            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    syncAccountEmail = syncEngine.accountEmail
                } else {
                    syncStatus = SyncStatus.DISCONNECTED
                    syncError = result.exceptionOrNull()?.message
                }
                onDone(result)
            }
            if (result.isSuccess) runSync()
        }
    }

    fun disconnectGoogleDrive() {
        scope.launch(Dispatchers.IO) {
            syncEngine.disconnect()
            withContext(Dispatchers.Main) {
                syncStatus = SyncStatus.DISCONNECTED
                syncAccountEmail = null
                syncError = null
            }
        }
    }

    /** Manual "Sync now" action from the settings menu -- bypasses the mutation debounce. */
    fun syncNow() {
        if (!syncEngine.isConnected) return
        syncDebounceJob?.cancel()
        scope.launch(Dispatchers.IO) { runSync() }
    }

    /** Hides the transient sync-status footer a few seconds after it settles (mirrors [dismissUpdateStatus]). */
    fun dismissSyncStatus() {
        if (syncStatus != SyncStatus.SYNCING) {
            syncStatus = if (syncEngine.isConnected) SyncStatus.IDLE else SyncStatus.DISCONNECTED
        }
    }

    // ── Collection import ───────────────────────────────────────────────────────
    // Separate, explicitly-triggered feature (Settings -> Collection Import) -- never runs as
    // part of runSync() above. See collection.CollectionImportEngine's class doc for why.
    private val collectionImportEngine = CollectionImportEngine(collectionRepo, syncEngine)

    val ownedCardNames: StateFlow<Set<String>> get() = collectionImportEngine.ownedCardNames
    val collectionGroups: StateFlow<List<CollectionGroupSummary>> get() = collectionImportEngine.groups
    val collectionFormatLabel: String get() = collectionImportEngine.format.label
    val collectionDriveFileName: String get() = collectionImportEngine.driveFileName
    val collectionLastImportedAt: Long? get() = collectionImportEngine.lastImportedAt

    var collectionImportStatus by mutableStateOf(CollectionImportStatus.IDLE)
        private set
    var collectionImportError by mutableStateOf<String?>(null)
        private set

    fun importCollectionFromDrive() {
        if (!syncEngine.isConnected) return
        collectionImportStatus = CollectionImportStatus.IMPORTING
        collectionImportError = null
        scope.launch(Dispatchers.IO) {
            val result = collectionImportEngine.importFromDrive()
            withContext(Dispatchers.Main) {
                result.onSuccess { collectionImportStatus = CollectionImportStatus.IDLE }
                    .onFailure { e -> collectionImportStatus = CollectionImportStatus.ERROR; collectionImportError = e.message }
            }
        }
    }

    fun importCollectionFromFile(file: File) {
        collectionImportStatus = CollectionImportStatus.IMPORTING
        collectionImportError = null
        scope.launch(Dispatchers.IO) {
            val result = runCatching { file.readText() }
                .fold(onSuccess = { collectionImportEngine.importFromText(it, alsoUploadToDrive = true) }, onFailure = { Result.failure(it) })
            withContext(Dispatchers.Main) {
                result.onSuccess { collectionImportStatus = CollectionImportStatus.IDLE }
                    .onFailure { e -> collectionImportStatus = CollectionImportStatus.ERROR; collectionImportError = e.message }
            }
        }
    }

    fun setCollectionGroupIncluded(groupName: String, included: Boolean) {
        collectionImportEngine.setGroupIncluded(groupName, included)
    }

    fun setCollectionGroupsOfTypeIncluded(groupType: String, included: Boolean) {
        collectionImportEngine.setGroupsOfTypeIncluded(groupType, included)
    }

    // One-time nudge shown the first time ever a user saves a list, if they haven't already
    // connected Google Drive -- guarded by a persisted flag (SettingsStore) so it never shows
    // again after that, regardless of whether they accept or dismiss it.
    var showFirstListSyncPrompt by mutableStateOf(false)
        private set

    private fun maybePromptFirstListSync(wasFirstList: Boolean) {
        if (!wasFirstList || syncEngine.isConnected) return
        if (SettingsStore.getSettingBoolean("sync.firstListPromptShown", false)) return
        SettingsStore.setSettingBoolean("sync.firstListPromptShown", true)
        showFirstListSyncPrompt = true
    }

    fun dismissFirstListSyncPrompt() {
        showFirstListSyncPrompt = false
    }

    fun acceptFirstListSyncPrompt() {
        showFirstListSyncPrompt = false
        connectGoogleDrive {}
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
        markDirtyAndScheduleSync()
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
        markDirtyAndScheduleSync()
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
        markDirtyAndScheduleSync()
    }

    fun saveSearchList(name: String) {
        val cards = query.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (cards.isEmpty()) return
        val wasFirstList = savedLists.value.isEmpty()
        lastLoadedListName = name
        scope.launch(Dispatchers.IO) { searchListRepo.create(name, cards) }
        markDirtyAndScheduleSync()
        maybePromptFirstListSync(wasFirstList)
    }

    // Overwrites an existing list (same id) instead of creating a duplicate — used when the
    // user confirms replacing a list that shares a name with another saved list.
    fun overwriteSearchList(id: Int, name: String) {
        val cards = query.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (cards.isEmpty()) return
        lastLoadedListName = name
        scope.launch(Dispatchers.IO) { searchListRepo.update(id, name, cards) }
        markDirtyAndScheduleSync()
    }

    fun loadSearchList(list: data.SavedSearchList) {
        query = list.cards.joinToString("\n")
        lastLoadedListName = list.name
        scope.launch(Dispatchers.IO) { searchListRepo.touch(list.id) }
        markDirtyAndScheduleSync()
    }

    fun deleteSearchList(id: Int) {
        scope.launch(Dispatchers.IO) { searchListRepo.delete(id) }
        markDirtyAndScheduleSync()
    }

    fun saveEditedList(id: Int, name: String, cards: List<String>) {
        scope.launch(Dispatchers.IO) { searchListRepo.update(id, name, cards) }
        markDirtyAndScheduleSync()
    }

    var updateInfo by mutableStateOf<UpdateInfo?>(null)
    var updateCheckState by mutableStateOf(UpdateCheckState.IDLE)
    var updateCheckError by mutableStateOf<String?>(null)

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
        updateCheckError = null
        scope.launch(Dispatchers.IO) {
            val result = checkForUpdate(earlyAccess)
            withContext(Dispatchers.Main) {
                when (result) {
                    is UpdateCheckResult.UpdateFound -> {
                        updateInfo = result.info
                        updateCheckState = UpdateCheckState.UPDATE_FOUND
                    }
                    is UpdateCheckResult.UpToDate -> {
                        updateInfo = null
                        updateCheckState = UpdateCheckState.UP_TO_DATE
                    }
                    is UpdateCheckResult.Failed -> {
                        updateInfo = null
                        updateCheckError = result.reason
                        updateCheckState = UpdateCheckState.CHECK_FAILED
                    }
                }
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
