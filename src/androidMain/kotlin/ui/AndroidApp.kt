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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import co.za.zarchive.R
import data.BuildInfo
import kotlinx.coroutines.delay
import ui.theme.Cinzel
import ui.theme.HeaderBg
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.Surface
import ui.theme.ZArchiveTheme

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
    var showListsDialog by remember { mutableStateOf(false) }
    var showResultsDialog by remember { mutableStateOf(false) }
    var showSearchOptionsDialog by remember { mutableStateOf(false) }
    var showCrashDialog by remember { mutableStateOf(pendingCrash != null) }
    val platformActions = remember { PlatformActions() }
    val resultsScrollState = rememberScrollState()
    var scrollRootY by remember { mutableStateOf(0) }

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
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(resultsScrollState)
                        .onGloballyPositioned { scrollRootY = it.positionInRoot().y.toInt() }
                        .padding(16.dp),
                ) {
                    when (tab) {
                        ResultsTab.RESULTS -> {
                            SearchInputScreen(vm, onOpenSearchOptions = { showSearchOptionsDialog = true })
                            if (vm.isSearching || vm.statusText.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                StatusRow(vm)
                            }
                            if (vm.searchedCards.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                LuckshackLinks(vm.searchedCards, onOpenUrl = platformActions::openUrl)
                            }
                            Spacer(Modifier.height(16.dp))
                            ResultsScreen(
                                vm = vm,
                                scrollState = resultsScrollState,
                                scrollRootY = scrollRootY,
                                summaryExpanded = summaryExpanded,
                                onSummaryExpandedChange = { summaryExpanded = it },
                                summaryFilter = summaryFilter,
                                onSummaryFilterChange = { summaryFilter = it },
                                onOpenUrl = platformActions::openUrl,
                                onImageTap = { expandedImagePath = it },
                            )
                        }
                        ResultsTab.ORDERS -> OrderListsScreen(
                            vm = vm,
                            onOpenUrl = platformActions::openUrl,
                            onImageTap = { expandedImagePath = it },
                        )
                        ResultsTab.MONITORS -> Text(
                            "Search monitors — deferred (Phase 14).",
                            color = OnSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }
        }
        if (expandedImagePath != null) {
            ModalScrim(onDismiss = { expandedImagePath = null }) {
                EnlargedCardPreview(expandedImagePath!!, onDismiss = { expandedImagePath = null })
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
    }
}
