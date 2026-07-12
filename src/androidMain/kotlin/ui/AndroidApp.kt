package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.za.zarchive.R
import data.BuildInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ui.theme.Cinzel
import ui.theme.HeaderBg
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.Surface
import ui.theme.ZArchiveTheme

// Fixed LazyColumn item index where the RESULTS tab's per-card sections start: SearchInputScreen,
// StatusRow slot, LuckshackLinks slot, a Spacer, and the CardSummaryPanel slot (5 leading items,
// each always present as a slot even when its content conditionally renders nothing) -- see the
// RESULTS branch below.
private const val RESULTS_CARD_ITEMS_START_INDEX = 5

// hidden: Search Monitors has no real implementation yet (Phase 14, deferred) -- the tab and its
// placeholder screen stay in place, just excluded from the bottom NavigationBar, so re-enabling it
// once Phase 14 lands is a one-line flip back to `hidden = false`.
private enum class ResultsTab(val label: String, val icon: ImageVector, val hidden: Boolean = false) {
    RESULTS("Search Results", Icons.Default.Storefront),
    ORDERS("Order Lists", Icons.Default.ShoppingCart),
    MONITORS("Search Monitors", Icons.Default.Notifications, hidden = true),
}

/**
 * Android app shell (Phase 6) — a Scaffold + TopAppBar replacing desktop's custom draggable
 * TitleBar (window chrome concepts don't apply on Android). The 3 top-level sections are a bottom
 * `NavigationBar` (native Android/Material3 convention, added in Phase 8), not desktop's top
 * `FolderTabs` row — desktop is unaffected, this only changes `AndroidApp`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidApp(vm: SearchViewModel, pendingCrash: String? = null) {
    var tab by remember { mutableStateOf(ResultsTab.RESULTS) }
    var summaryExpanded by remember { mutableStateOf(true) }
    var summaryFilter by remember { mutableStateOf("") }
    var expandedImagePath by remember { mutableStateOf<String?>(null) }
    var detailResult by remember { mutableStateOf<data.SearchResult?>(null) }
    // Order-list rows already are the chosen listing for that card, so their detail modal skips
    // the "Use this version" pin toggle -- only the search-results tab's taps allow pinning.
    var detailResultAllowPin by remember { mutableStateOf(true) }
    var showListsDialog by remember { mutableStateOf(false) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var showSearchOptionsDialog by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(pendingCrash != null) }
    val platformActions = remember { PlatformActions() }
    val resultsListState = rememberLazyListState()
    val resultsScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.restoreSessionIfAny() }
    LaunchedEffect(Unit) { vm.checkForUpdates() }
    LaunchedEffect(Unit) { vm.syncOnLaunch() }
    LaunchedEffect(vm.updateCheckState) {
        if (vm.updateCheckState == UpdateCheckState.UP_TO_DATE || vm.updateCheckState == UpdateCheckState.UPDATE_FOUND) {
            delay(5_000)
            vm.dismissUpdateStatus()
        }
    }
    LaunchedEffect(vm.syncStatus) {
        if (vm.syncStatus == SyncStatus.SYNCED || vm.syncStatus == SyncStatus.ERROR) {
            delay(5_000)
            vm.dismissSyncStatus()
        }
    }

    ZArchiveTheme {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(id = R.drawable.zarchive_logo),
                                contentDescription = null,
                                modifier = Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "ZArchive",
                                color = Primary,
                                fontFamily = Cinzel,
                                fontWeight = FontWeight.Bold,
                                fontSize = 20.sp,
                            )
                            if (BuildInfo.VERSION.contains('-')) {
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "PRE-RELEASE",
                                    color = OnSurfaceVariant,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = { showListsDialog = true }) {
                            Icon(Icons.Default.FormatListBulleted, "Saved lists", tint = OnSurfaceVariant)
                        }
                        IconButton(onClick = { showResultsDialog = true }) {
                            Icon(Icons.Default.Archive, "Saved results", tint = OnSurfaceVariant)
                        }
                        // Low-friction, always-visible entry point to (re)connect/sync -- distinct
                        // from the same actions buried in the Settings menu. Always rendered
                        // (toggling visibility on syncStatus caused a pop-in/out flicker); just
                        // dimmed and inert while a sync is already in flight instead.
                        IconButton(onClick = {
                            if (vm.syncStatus != SyncStatus.SYNCING) {
                                if (vm.syncStatus == SyncStatus.DISCONNECTED) vm.connectGoogleDrive {} else vm.syncNow()
                            }
                        }) {
                            Icon(
                                Icons.Default.CloudSync, "Sync now",
                                tint = if (vm.syncStatus == SyncStatus.SYNCING) OnSurfaceVariant.copy(alpha = 0.35f) else OnSurfaceVariant,
                            )
                        }
                        SettingsMenu(vm, onOpenUrl = platformActions::openUrl)
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderBg),
                )
            },
            bottomBar = {
                Column {
                    AnimatedVisibility(visible = vm.updateCheckState != UpdateCheckState.IDLE) {
                        UpdateStatusFooter(vm.updateCheckState)
                    }
                    AnimatedVisibility(
                        visible = vm.syncStatus == SyncStatus.SYNCING || vm.syncStatus == SyncStatus.SYNCED || vm.syncStatus == SyncStatus.ERROR,
                    ) {
                        SyncStatusFooter(vm.syncStatus, vm.syncError)
                    }
                    NavigationBar(containerColor = HeaderBg) {
                        ResultsTab.entries.filter { !it.hidden }.forEach { t ->
                            val active = t == tab
                            NavigationBarItem(
                                selected = active,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(t.label, fontSize = 11.sp, maxLines = 1) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = Primary,
                                    selectedTextColor = Primary,
                                    unselectedIconColor = OnSurfaceVariant.copy(alpha = 0.7f),
                                    unselectedTextColor = OnSurfaceVariant.copy(alpha = 0.7f),
                                    indicatorColor = Primary.copy(alpha = 0.15f),
                                ),
                            )
                        }
                    }
                }
            },
            containerColor = Surface,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                when (tab) {
                    ResultsTab.RESULTS -> {
                        // LazyColumn (Compose's built-in recycled/virtualized list -- the KMP
                        // equivalent of e.g. React Native's FlashList) instead of a plain Column +
                        // verticalScroll: a saved result with many searched cards was composing/
                        // laying out every card's entire results section simultaneously with zero
                        // recycling, which was extremely laggy. Only on-screen (+ a small buffer
                        // of) items are ever composed now.
                        //
                        // Fixed slot indices (used by the summary panel's "scroll to this card"
                        // click, see onCardClick below) -- each slot always occupies its index
                        // regardless of whether its content conditionally renders anything, so the
                        // card region always starts at a constant offset instead of a shifting one.
                        val summaryCards = vm.searchedCards.ifEmpty { vm.results.map { it.card }.distinct() }
                        val hasResults = vm.results.map { it.card }.toSet()
                        val resultCards = summaryCards.filter { it in hasResults || vm.refreshingCards.containsKey(it) }

                        LazyColumn(state = resultsListState, modifier = Modifier.fillMaxSize().padding(16.dp)) {
                            item { SearchInputScreen(vm, onOpenSearchOptions = { showSearchOptionsDialog = true }) }
                            item {
                                if (vm.isSearching || vm.statusText.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    StatusRow(vm)
                                }
                            }
                            item {
                                if (vm.searchedCards.isNotEmpty()) {
                                    Spacer(Modifier.height(12.dp))
                                    LuckshackLinks(vm.searchedCards, onOpenUrl = platformActions::openUrl)
                                }
                            }
                            item { Spacer(Modifier.height(16.dp)) }
                            item {
                                if (summaryCards.size > 1) {
                                    CardSummaryPanel(
                                        cards = summaryCards,
                                        results = vm.results.toList(),
                                        images = vm.images,
                                        isSearching = vm.isSearching,
                                        onCardClick = { card ->
                                            val idx = resultCards.indexOf(card)
                                            if (idx >= 0) {
                                                resultsScope.launch { resultsListState.animateScrollToItem(RESULTS_CARD_ITEMS_START_INDEX + idx) }
                                            }
                                        },
                                        onImageTap = { expandedImagePath = it },
                                        includePartialMatches = vm.includePartialMatches,
                                        expanded = summaryExpanded,
                                        onExpandedChange = { summaryExpanded = it },
                                        filter = summaryFilter,
                                        onFilterChange = { summaryFilter = it },
                                    )
                                    Spacer(Modifier.height(10.dp))
                                }
                            }
                            items(resultCards, key = { it }) { card ->
                                CardSection(
                                    card = card,
                                    results = vm.results.filter { it.card == card },
                                    images = vm.images,
                                    isSearching = vm.isSearching,
                                    isRefreshing = card in vm.refreshingCards,
                                    pinnedUrl = vm.pinnedListings[card],
                                    onTogglePin = { url -> if (vm.pinnedListings[card] == url) vm.pinnedListings.remove(card) else vm.pinnedListings[card] = url },
                                    includePartialMatches = vm.includePartialMatches,
                                    excludedFromOrder = vm.excludedCards.containsKey(card),
                                    onToggleExcludeFromOrder = { if (vm.excludedCards.containsKey(card)) vm.excludedCards.remove(card) else vm.excludedCards[card] = Unit },
                                    onRefresh = { vm.refreshCard(card) },
                                    onOpenUrl = platformActions::openUrl,
                                    onCardTap = { detailResultAllowPin = true; detailResult = it },
                                )
                                Spacer(Modifier.height(12.dp))
                            }
                        }
                    }
                    ResultsTab.ORDERS -> Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    ) {
                        OrderListsScreen(
                            vm = vm,
                            onOpenUrl = platformActions::openUrl,
                            onImageTap = { expandedImagePath = it },
                            onCardTap = { detailResultAllowPin = false; detailResult = it },
                        )
                    }
                    ResultsTab.MONITORS -> Text(
                        "Search monitors — deferred (Phase 14).",
                        color = OnSurfaceVariant.copy(alpha = 0.6f),
                        fontSize = 13.sp,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        if (expandedImagePath != null) {
            ModalScrim(onDismiss = { expandedImagePath = null }) {
                EnlargedCardPreview(expandedImagePath!!, onDismiss = { expandedImagePath = null })
            }
        }
        detailResult?.let { result ->
            ModalScrim(onDismiss = { detailResult = null }) {
                CardDetailModal(
                    result = result,
                    imagePath = (result.title?.let { vm.images[it] }) ?: vm.images[result.card],
                    isPinned = vm.pinnedListings[result.card] == result.url,
                    onTogglePin = if (detailResultAllowPin) {
                        {
                            if (vm.pinnedListings[result.card] == result.url) vm.pinnedListings.remove(result.card)
                            else vm.pinnedListings[result.card] = result.url
                        }
                    } else null,
                    onOpenUrl = platformActions::openUrl,
                    onDismiss = { detailResult = null },
                )
            }
        }
        }
        if (showListsDialog) {
            SavedListsDialog(vm, onDismiss = { showListsDialog = false })
        }
        if (showResultsDialog) {
            SavedResultsDialog(vm, onDismiss = { showResultsDialog = false })
        }
        if (showSearchOptionsDialog) {
            SearchOptionsDialog(vm, onDismiss = { showSearchOptionsDialog = false })
        }
        if (showCrashDialog && pendingCrash != null) {
            CrashReportDialog(
                crashLog = pendingCrash,
                onCopyToClipboard = platformActions::copyToClipboard,
                onOpenUrl = platformActions::openUrl,
                onDismiss = { showCrashDialog = false },
            )
        }
        if (vm.downloadProgress != null) {
            DownloadProgressDialog(
                phase = vm.downloadPhase,
                progress = vm.downloadProgress!!,
                error = vm.downloadError,
                onCancel = { vm.cancelDownload() },
            )
        } else if (vm.updateInfo != null) {
            UpdateDialog(
                info = vm.updateInfo!!,
                canAutoInstall = vm.updateInfo!!.downloadUrl != null && vm.canInstallUpdate,
                onOpenUrl = platformActions::openUrl,
                onInstall = { vm.startDownload(vm.updateInfo!!) {} },
                onDismiss = { vm.updateInfo = null },
            )
        }
        if (vm.showAddToSearchDialog) {
            AddToSearchDialog(
                newCount = vm.pendingAddCount,
                totalCount = vm.pendingTotalCount,
                unavailableCount = vm.pendingUnavailableCount,
                onAddNew = { vm.confirmAddToSearch() },
                onAddNewAndRefresh = { vm.confirmAddNewAndRefreshUnavailable() },
                onSearchAll = { vm.declineAddToSearch() },
                onDismiss = { vm.showAddToSearchDialog = false },
            )
        }
        if (vm.showAllAlreadySearchedDialog) {
            AllAlreadySearchedDialog(
                count = vm.alreadySearchedCount,
                unavailableCount = vm.alreadySearchedUnavailableCount,
                onRefreshUnavailable = { vm.confirmRefreshUnavailable() },
                onResearch = { vm.confirmResearchAll() },
                onDismiss = { vm.dismissAllAlreadySearched() },
            )
        }
        if (vm.showSearchSummary) {
            SearchSummaryDialog(vm)
        }
        if (vm.showFirstListSyncPrompt) {
            FirstListSyncPromptDialog(
                onConnect = { vm.acceptFirstListSyncPrompt() },
                onDismiss = { vm.dismissFirstListSyncPrompt() },
            )
        }
    }
}
