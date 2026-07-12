package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.ui.window.DialogProperties
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay
import data.SearchResult
import data.OrderPlan
import data.StoreOrder
import data.cheapestPlan
import data.fewestStoresPlan
import data.preferExactMatches
import data.luckshackSearchUrl
import data.KNOWN_PLATFORMS
import data.Platform
import network.BrowserSearcher
import kotlinx.coroutines.Dispatchers
import ui.UpdateCheckState
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Desktop
import java.net.URI

// ── Arcane Market Ledger palette (see CLAUDE.md) ───────────────────────────
private val Surface                 = Color(0xFF0B1326)
private val SurfaceContainerLowest  = Color(0xFF060E20)
private val SurfaceContainerLow     = Color(0xFF131B2E)
private val SurfaceContainer        = Color(0xFF171F33)
private val SurfaceContainerHigh    = Color(0xFF222A3D)
private val SurfaceContainerHighest = Color(0xFF2D3449)
private val OnSurface                = Color(0xFFDAE2FD)
private val OnSurfaceVariant         = Color(0xFFD0C5AF)
private val Outline                  = Color(0xFF99907C)
private val OutlineVariant           = Color(0xFF4D4635)
private val Primary                  = Color(0xFFF2CA50)
private val OnPrimary                = Color(0xFF3C2F00)
private val Secondary                = Color(0xFFC0C1FF)
private val OnSecondaryContainer     = Color(0xFFB0B2FF)
private val Tertiary                 = Color(0xFF58E7AA)
private val ErrorColor               = Color(0xFFFFB4AB)
private val HeaderBg                 = Color(0xFF00020C)

private val Mono = FontFamily.Monospace

// Table column widths — sized for the ~1100dp window; Name takes the remainder.
private val COL_THUMB  = 72.dp
private val COL_STORE  = 160.dp
private val COL_STATUS = 110.dp
private val COL_PRICE  = 112.dp
private val COL_PIN    = 130.dp  // "Use this Version" column in search results table
private val COL_LINK   = 100.dp  // "External Link" column in search results table
private val COL_ACTION = 60.dp   // action column in order list rows

private fun List<SearchResult>.sortedByPrice(): List<SearchResult> =
    sortedWith(compareBy({ it.priceZar == null }, { it.priceZar ?: Double.MAX_VALUE }))

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}

@Composable
fun WindowScope.App(
    windowState: WindowState,
    onCloseRequest: () -> Unit = {},
    pendingCrash: String? = null,
) {
    val vm = remember {
        SearchViewModel(
            searchListRepo = data.DesktopSearchListRepo(),
            searchResultRepo = data.DesktopSearchResultRepo(),
            platformActions = PlatformActions(),
            warrenSearcher = network.BrowserSearcher(2),
        )
    }
    val colorScheme = darkColorScheme(
        background   = Surface,
        surface      = SurfaceContainer,
        primary      = Primary,
        onPrimary    = OnPrimary,
        onBackground = OnSurface,
        onSurface    = OnSurface,
    )

    var tab by remember { mutableStateOf(ResultsTab.RESULTS) }
    var showCrashDialog by remember { mutableStateOf(pendingCrash != null) }

    LaunchedEffect(Unit) { vm.checkForUpdates() }
    LaunchedEffect(Unit) { vm.syncOnLaunch() }

    // Auto-dismiss the update status footer after 5 seconds
    LaunchedEffect(vm.updateCheckState) {
        if (vm.updateCheckState == UpdateCheckState.UP_TO_DATE ||
            vm.updateCheckState == UpdateCheckState.UPDATE_FOUND
        ) {
            delay(5_000)
            vm.dismissUpdateStatus()
        }
    }

    // Auto-dismiss the sync status footer after 5 seconds (not while actively syncing)
    LaunchedEffect(vm.syncStatus) {
        if (vm.syncStatus == SyncStatus.SYNCED || vm.syncStatus == SyncStatus.ERROR) {
            delay(5_000)
            vm.dismissSyncStatus()
        }
    }

    MaterialTheme(colorScheme = colorScheme) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().background(Surface).border(1.dp, OutlineVariant)) {
                // Thin draggable title bar — OS controls only
                TitleBar(vm, windowState, onCloseRequest)
                HorizontalDivider(color = OutlineVariant)
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    // Left search panel
                    LeftPanel(vm)
                    VerticalDivider(color = OutlineVariant)
                    // Right content area
                    Column(Modifier.weight(1f)) {
                        // Tab strip is always visible (not gated on hasSearch) — Search Monitors is a
                        // persistent background feature the user should be able to reach and configure
                        // before ever running a search.
                        Box(Modifier.fillMaxWidth().background(HeaderBg)) {
                            FolderTabs(tab, { tab = it }, Modifier.padding(start = 16.dp))
                        }
                        HorizontalDivider(color = OutlineVariant)
                        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                            if (vm.isSearching || vm.statusText.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                StatusRow(vm)
                            }
                            if (vm.searchedCards.isNotEmpty() && tab == ResultsTab.RESULTS) {
                                Spacer(Modifier.height(8.dp))
                                LuckshackLinks(vm.searchedCards)
                            }
                            Spacer(Modifier.height(8.dp))
                            ResultsPane(vm, tab)
                        }
                    }
                }
                // Background task status footer — slides in/out at the very bottom of the window
                AnimatedVisibility(
                    visible = vm.updateCheckState != UpdateCheckState.IDLE,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit  = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                ) {
                    UpdateStatusFooter(vm.updateCheckState)
                }
                AnimatedVisibility(
                    visible = vm.syncStatus == SyncStatus.SYNCING || vm.syncStatus == SyncStatus.SYNCED || vm.syncStatus == SyncStatus.ERROR,
                    enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                    exit  = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
                ) {
                    SyncStatusFooter(vm.syncStatus, vm.syncError)
                }
            }
            // Modal dialogs — rendered on top of the full app
            if (vm.downloadProgress != null) {
                DownloadProgressDialog(
                    phase    = vm.downloadPhase,
                    progress = vm.downloadProgress!!,
                    error    = vm.downloadError,
                    onCancel = { vm.cancelDownload() },
                )
            } else if (vm.updateInfo != null) {
                UpdateDialog(
                    info      = vm.updateInfo!!,
                    vm        = vm,
                    onDismiss = { vm.updateInfo = null },
                    onInstall = { vm.startDownload(vm.updateInfo!!) { kotlin.system.exitProcess(0) } },
                )
            }
            if (showCrashDialog && pendingCrash != null) {
                CrashReportDialog(crashLog = pendingCrash, onDismiss = { showCrashDialog = false })
            }
            if (vm.showAddToSearchDialog) {
                AddToSearchDialog(
                    newCount         = vm.pendingAddCount,
                    totalCount       = vm.pendingTotalCount,
                    unavailableCount = vm.pendingUnavailableCount,
                    onAddNew         = { vm.confirmAddToSearch() },
                    onAddNewAndRefresh = { vm.confirmAddNewAndRefreshUnavailable() },
                    onSearchAll      = { vm.declineAddToSearch() },
                    onDismiss        = { vm.showAddToSearchDialog = false },
                )
            }
            if (vm.showAllAlreadySearchedDialog) {
                AllAlreadySearchedDialog(
                    count               = vm.alreadySearchedCount,
                    unavailableCount    = vm.alreadySearchedUnavailableCount,
                    onRefreshUnavailable = { vm.confirmRefreshUnavailable() },
                    onResearch          = { vm.confirmResearchAll() },
                    onDismiss           = { vm.dismissAllAlreadySearched() },
                )
            }
            if (vm.monitorAlerts.isNotEmpty()) {
                MonitorFoundDialog(
                    hits = vm.monitorAlerts,
                    images = vm.images,
                    onCloseAll = { vm.dismissAllMonitorAlerts() },
                    onGoToStore = { hit -> runCatching { Desktop.getDesktop().browse(URI(hit.url)) } },
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
}

@Composable
private fun WindowScope.TitleBar(vm: SearchViewModel, windowState: WindowState, onCloseRequest: () -> Unit) {
    WindowDraggableArea {
        Surface(color = HeaderBg, modifier = Modifier.fillMaxWidth().height(32.dp)) {
            Box(Modifier.fillMaxSize()) {
                Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                    if (data.BuildInfo.VERSION.contains('-')) {
                        PreReleaseBadge()
                        Spacer(Modifier.width(4.dp))
                    }
                    if (System.getProperty("mtg.debug") == "true") {
                        val debugDir = java.io.File(System.getProperty("user.home"), "zarchive-debug")
                        GhostIconButton(Icons.Default.BugReport, "Debug", tint = OnSurfaceVariant, iconSize = 14.dp) {
                            debugDir.mkdirs(); Desktop.getDesktop().open(debugDir)
                        }
                    }
                    // Low-friction, always-visible entry point to (re)connect/sync -- distinct from
                    // the same actions buried in the Settings menu. Always rendered (toggling its
                    // visibility on syncStatus caused a visible pop-in/out flicker); just dimmed
                    // and inert while a sync is already in flight instead.
                    GhostIconButton(
                        Icons.Default.CloudSync, "Sync now",
                        tint = if (vm.syncStatus == SyncStatus.SYNCING) OnSurfaceVariant.copy(alpha = 0.35f) else OnSurfaceVariant,
                        iconSize = 14.dp,
                    ) {
                        if (vm.syncStatus != SyncStatus.SYNCING) {
                            if (vm.syncStatus == SyncStatus.DISCONNECTED) vm.connectGoogleDrive {} else vm.syncNow()
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                    SettingsMenu(vm)
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.width(1.dp).height(12.dp).background(OutlineVariant.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(2.dp))
                    // Go through Compose's own WindowState (not the raw AWT Frame's extendedState)
                    // so Compose's window-state tracking never disagrees with the OS window --
                    // keeps minimize/maximize/restore from being able to disturb the composition
                    // (and therefore vm's in-memory search/results state) in any way.
                    GhostIconButton(Icons.Default.Remove, "Minimize", tint = OnSurfaceVariant, iconSize = 14.dp) {
                        windowState.isMinimized = true
                    }
                    GhostIconButton(Icons.Default.Fullscreen, "Maximize / Restore", tint = OnSurfaceVariant, iconSize = 14.dp) {
                        windowState.placement = if (windowState.placement == WindowPlacement.Maximized)
                            WindowPlacement.Floating else WindowPlacement.Maximized
                    }
                    GhostIconButton(Icons.Default.Close, "Close", tint = ErrorColor, iconSize = 14.dp) {
                        onCloseRequest()
                    }
                }
            }
        }
    }
}

@Composable
private fun LeftPanel(vm: SearchViewModel) {
    val bannerBitmap = remember {
        runCatching {
            Thread.currentThread().contextClassLoader.getResourceAsStream("banner_logo.png")
                ?.use { javax.imageio.ImageIO.read(it) }
                ?.toComposeImageBitmap()
        }.getOrNull()
    }
    Column(
        Modifier
            .width(240.dp)
            .fillMaxHeight()
            .background(SurfaceContainerLowest),
    ) {
        // Logo
        Box(
            Modifier
                .fillMaxWidth()
                .background(HeaderBg),
        ) {
            HeaderLogo(bannerBitmap)
        }
        HorizontalDivider(color = OutlineVariant)

        var showOptionsDialog by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .height(28.dp)
                .clip(RoundedCornerShape(4.dp))
                .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                .clickable { showOptionsDialog = true },
        ) {
            Icon(Icons.Default.Settings, null, tint = OnSurfaceVariant, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                "Search Options", fontSize = 12.sp, color = OnSurfaceVariant,
                style = TextStyle(lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                )),
            )
        }
        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))
        if (showOptionsDialog) {
            SearchOptionsDialog(vm) { showOptionsDialog = false }
        }

        SavedListsPanel(vm)
        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))

        // Search label
        Text(
            "Cards to search",
            fontSize = 11.sp,
            color = OnSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 4.dp),
        )

        // Search field — fills all remaining vertical space
        PanelSearch(vm, Modifier.weight(1f).padding(horizontal = 12.dp))

        Spacer(Modifier.height(8.dp))

        // Search / Stop button + shortcut hint
        Column(
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Button(
                onClick = { if (vm.isSearching) vm.cancel() else vm.requestSearch() },
                modifier = Modifier.fillMaxWidth().height(36.dp),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (vm.isSearching) ErrorColor.copy(alpha = 0.12f) else Primary.copy(alpha = 0.15f),
                    contentColor   = if (vm.isSearching) ErrorColor else Primary,
                ),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(
                    if (vm.isSearching) "Stop" else "Search",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Text(
                "Alt+Enter",
                fontSize = 9.sp,
                color = OnSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}

@Composable
private fun HeaderLogo(bannerBitmap: ImageBitmap?) {
    if (bannerBitmap != null) {
        Image(
            bitmap = bannerBitmap,
            contentDescription = "ZArchive",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
            alignment = Alignment.CenterStart,
        )
    } else {
        Text("ZArchive", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primary)
    }
}

private enum class SavedPanelTab { LISTS, RESULTS }

@Composable
private fun SavedListsPanel(vm: SearchViewModel) {
    val lists        by vm.savedLists.collectAsState()
    val savedResults by vm.savedResults.collectAsState()
    var activeTab    by remember { mutableStateOf(SavedPanelTab.LISTS) }

    // Lists tab state
    var showSaveListDialog by remember { mutableStateOf(false) }
    var showAllListsDialog by remember { mutableStateOf(false) }
    var saveListNameDraft  by remember { mutableStateOf("") }
    var editingList        by remember { mutableStateOf<data.SavedSearchList?>(null) }
    var overwriteListTarget by remember { mutableStateOf<data.SavedSearchList?>(null) }

    // Results tab state
    var showSaveResultDialog by remember { mutableStateOf(false) }
    var showAllResultsDialog by remember { mutableStateOf(false) }
    var saveResultName       by remember { mutableStateOf("") }
    var saveResultDesc       by remember { mutableStateOf("") }
    var overwriteResultTarget by remember { mutableStateOf<data.SavedResultEntry?>(null) }

    Column(Modifier.fillMaxWidth()) {
        // Header: segmented tab control fills the full width (matching Search Options button);
        // save icon is overlaid at the right edge so it doesn't narrow the control.
        Box(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 6.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(28.dp)
                    .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp)),
            ) {
                SavedPanelTab.entries.forEachIndexed { i, tab ->
                    val isActive = activeTab == tab
                    if (i > 0) VerticalDivider(color = OutlineVariant, modifier = Modifier.fillMaxHeight())
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isActive) SurfaceContainerHighest else Color.Transparent)
                            .clickable { activeTab = tab },
                    ) {
                        Text(
                            if (tab == SavedPanelTab.LISTS) "Lists" else "Results",
                            fontSize = 11.sp,
                            fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (isActive) Primary else OnSurfaceVariant.copy(alpha = 0.5f),
                            style = TextStyle(lineHeightStyle = LineHeightStyle(
                                alignment = LineHeightStyle.Alignment.Center,
                                trim = LineHeightStyle.Trim.Both,
                            )),
                        )
                    }
                }
            }
            // Save icon — floats at the right edge, overlaid on the control
            @OptIn(ExperimentalFoundationApi::class)
            when {
                activeTab == SavedPanelTab.LISTS && vm.query.isNotBlank() ->
                    TooltipArea(
                        tooltip = {
                            Surface(color = SurfaceContainerHighest, shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, OutlineVariant)) {
                                Text("Save current list", fontSize = 11.sp, color = OnSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        },
                        delayMillis = 400,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    ) {
                        Icon(Icons.Default.BookmarkAdd, "Save list", tint = Primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp)
                                .clickable { saveListNameDraft = vm.lastLoadedListName ?: ""; showSaveListDialog = true })
                    }
                activeTab == SavedPanelTab.RESULTS && vm.results.isNotEmpty() ->
                    TooltipArea(
                        tooltip = {
                            Surface(color = SurfaceContainerHighest, shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, OutlineVariant)) {
                                Text("Save current results", fontSize = 11.sp, color = OnSurface,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                        },
                        delayMillis = 400,
                        modifier = Modifier.align(Alignment.CenterEnd).padding(end = 6.dp),
                    ) {
                        Icon(Icons.Default.Archive, "Save results", tint = Primary.copy(alpha = 0.8f),
                            modifier = Modifier.size(14.dp).clickable {
                                saveResultName = vm.lastLoadedResultName ?: ""; saveResultDesc = ""; showSaveResultDialog = true
                            })
                    }
            }
        }

        // Tab content
        when (activeTab) {
            SavedPanelTab.LISTS -> {
                val pinned   = lists.take(3)
                val overflow = lists.size - 3
                if (lists.isEmpty()) {
                    Text("No saved lists yet", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp))
                } else {
                    pinned.forEach { list ->
                        SavedListRow(list,
                            onLoad   = { vm.loadSearchList(list) },
                            onDelete = { vm.deleteSearchList(list.id) },
                            onEdit   = { editingList = list })
                    }
                    if (overflow > 0) {
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (hovered) SurfaceContainerHigh else Color.Transparent)
                                .hoverable(interaction)
                                .clickable { showAllListsDialog = true }
                                .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        ) {
                            Icon(Icons.Default.ExpandMore, null, tint = OnSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("+$overflow more", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
            SavedPanelTab.RESULTS -> {
                val pinned   = savedResults.take(3)
                val overflow = savedResults.size - 3
                if (savedResults.isEmpty()) {
                    Text(
                        "No saved results yet\nSearch for cards then use the archive icon to save",
                        fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.3f),
                        modifier = Modifier.padding(start = 12.dp, bottom = 8.dp),
                    )
                } else {
                    pinned.forEach { entry ->
                        SavedResultRow(entry,
                            onLoad   = { vm.loadSavedResult(entry) },
                            onDelete = { vm.deleteSavedResult(entry.id) })
                    }
                    if (overflow > 0) {
                        val interaction = remember { MutableInteractionSource() }
                        val hovered by interaction.collectIsHoveredAsState()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (hovered) SurfaceContainerHigh else Color.Transparent)
                                .hoverable(interaction)
                                .clickable { showAllResultsDialog = true }
                                .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
                        ) {
                            Icon(Icons.Default.ExpandMore, null, tint = OnSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("+$overflow more", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                        }
                    }
                }
            }
        }
    }

    // ── Dialogs ────────────────────────────────────────────────────────────────

    if (showSaveListDialog) {
        AlertDialog(
            onDismissRequest = { showSaveListDialog = false },
            title = { Text("Save list", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
            text = {
                OutlinedTextField(
                    value = saveListNameDraft,
                    onValueChange = { saveListNameDraft = it },
                    label = { Text("List name", fontSize = 12.sp) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Primary,
                        focusedLabelColor    = Primary,
                        cursorColor          = Primary,
                        unfocusedBorderColor = OutlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed  = saveListNameDraft.trim()
                        val existing = lists.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                        if (existing != null) {
                            overwriteListTarget = existing
                        } else {
                            vm.saveSearchList(trimmed)
                            showSaveListDialog = false
                        }
                    },
                    enabled = saveListNameDraft.isNotBlank(),
                ) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveListDialog = false }) { Text("Cancel", color = OnSurfaceVariant) }
            },
            containerColor    = SurfaceContainerLowest,
            titleContentColor = OnSurface,
        )
    }

    overwriteListTarget?.let { existing ->
        AlertDialog(
            onDismissRequest = { overwriteListTarget = null },
            title = { Text("Overwrite list?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
            text = {
                Text(
                    "A list named \"${existing.name}\" already exists. Overwrite it with the current cards?",
                    fontSize = 13.sp, color = OnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.overwriteSearchList(existing.id, saveListNameDraft.trim())
                    overwriteListTarget = null
                    showSaveListDialog = false
                }) { Text("Overwrite", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { overwriteListTarget = null }) { Text("Cancel", color = OnSurfaceVariant) }
            },
            containerColor    = SurfaceContainerLowest,
            titleContentColor = OnSurface,
        )
    }

    if (showSaveResultDialog) {
        val listingCount = vm.results.count { it.title != null }
        AlertDialog(
            onDismissRequest = { showSaveResultDialog = false },
            title = { Text("Save results", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = saveResultName,
                        onValueChange = { saveResultName = it },
                        label = { Text("Name", fontSize = 12.sp) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Primary,
                            focusedLabelColor    = Primary,
                            cursorColor          = Primary,
                            unfocusedBorderColor = OutlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = saveResultDesc,
                        onValueChange = { saveResultDesc = it },
                        label = { Text("Description (optional)", fontSize = 12.sp) },
                        minLines = 2,
                        maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor   = Primary,
                            focusedLabelColor    = Primary,
                            cursorColor          = Primary,
                            unfocusedBorderColor = OutlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "${vm.searchedCards.size} cards · $listingCount listings",
                        fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val trimmed  = saveResultName.trim()
                        val existing = savedResults.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                        if (existing != null) {
                            overwriteResultTarget = existing
                        } else {
                            vm.saveCurrentResults(trimmed, saveResultDesc.trim())
                            showSaveResultDialog = false
                        }
                    },
                    enabled = saveResultName.isNotBlank(),
                ) { Text("Save", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { showSaveResultDialog = false }) { Text("Cancel", color = OnSurfaceVariant) }
            },
            containerColor    = SurfaceContainerLowest,
            titleContentColor = OnSurface,
        )
    }

    overwriteResultTarget?.let { existing ->
        AlertDialog(
            onDismissRequest = { overwriteResultTarget = null },
            title = { Text("Overwrite result?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
            text = {
                Text(
                    "A saved result named \"${existing.name}\" already exists. Overwrite it with the current results?",
                    fontSize = 13.sp, color = OnSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.overwriteSavedResult(existing.id, saveResultName.trim(), saveResultDesc.trim())
                    overwriteResultTarget = null
                    showSaveResultDialog = false
                }) { Text("Overwrite", color = Primary) }
            },
            dismissButton = {
                TextButton(onClick = { overwriteResultTarget = null }) { Text("Cancel", color = OnSurfaceVariant) }
            },
            containerColor    = SurfaceContainerLowest,
            titleContentColor = OnSurface,
        )
    }

    // Edit dialog for pinned-row list edits (outside the "view all" modal)
    editingList?.let { target ->
        if (!showAllListsDialog) {
            EditListDialog(
                list      = target,
                onSave    = { name, cards -> vm.saveEditedList(target.id, name, cards); editingList = null },
                onDismiss = { editingList = null },
            )
        }
    }

    if (showAllListsDialog) {
        var modalEditTarget by remember { mutableStateOf<data.SavedSearchList?>(null) }
        var listsFilter by remember { mutableStateOf("") }
        val filteredLists = remember(lists, listsFilter) {
            val q = listsFilter.trim()
            if (q.isBlank()) lists
            else lists.filter { list ->
                list.name.contains(q, ignoreCase = true) ||
                    list.cards.any { it.contains(q, ignoreCase = true) }
            }
        }
        AlertDialog(
            onDismissRequest = { showAllListsDialog = false },
            title = { Text("Saved lists", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
            text = {
                Column {
                    FilterField(listsFilter, placeholder = "Search saved lists or cards…") { listsFilter = it }
                    Spacer(Modifier.height(8.dp))
                    if (filteredLists.isEmpty()) {
                        Text("No matching lists", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(filteredLists, key = { it.id }) { list ->
                            SavedListRow(
                                list     = list,
                                onLoad   = { vm.loadSearchList(list); showAllListsDialog = false },
                                onDelete = { vm.deleteSearchList(list.id) },
                                onEdit   = { modalEditTarget = list },
                            )
                            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAllListsDialog = false }) { Text("Close", color = OnSurfaceVariant) }
            },
            containerColor    = SurfaceContainerLowest,
            titleContentColor = OnSurface,
        )
        modalEditTarget?.let { target ->
            EditListDialog(
                list      = target,
                onSave    = { name, cards -> vm.saveEditedList(target.id, name, cards); modalEditTarget = null },
                onDismiss = { modalEditTarget = null },
            )
        }
    }

    if (showAllResultsDialog) {
        var resultsFilter by remember { mutableStateOf("") }
        val filteredResults = remember(savedResults, resultsFilter) {
            val q = resultsFilter.trim()
            if (q.isBlank()) savedResults
            else savedResults.filter { entry ->
                entry.name.contains(q, ignoreCase = true) ||
                    entry.description.contains(q, ignoreCase = true) ||
                    entry.cards.any { it.contains(q, ignoreCase = true) }
            }
        }
        AlertDialog(
            onDismissRequest = { showAllResultsDialog = false },
            title = { Text("Saved results", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
            text = {
                Column {
                    FilterField(resultsFilter, placeholder = "Search saved results or cards…") { resultsFilter = it }
                    Spacer(Modifier.height(8.dp))
                    if (filteredResults.isEmpty()) {
                        Text("No matching results", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 8.dp))
                    }
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                        verticalArrangement = Arrangement.spacedBy(0.dp),
                    ) {
                        items(filteredResults, key = { it.id }) { entry ->
                            SavedResultRow(
                                entry    = entry,
                                onLoad   = { vm.loadSavedResult(entry); showAllResultsDialog = false },
                                onDelete = { vm.deleteSavedResult(entry.id) },
                            )
                            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.2f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAllResultsDialog = false }) { Text("Close", color = OnSurfaceVariant) }
            },
            containerColor    = SurfaceContainerLowest,
            titleContentColor = OnSurface,
        )
    }
}

@Composable
private fun EditListDialog(
    list: data.SavedSearchList,
    onSave: (name: String, cards: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameDraft  by remember(list.id) { mutableStateOf(list.name) }
    var cardsDraft by remember(list.id) { mutableStateOf(TextFieldValue(list.cards.joinToString("\n"))) }
    val cardsScroll = rememberScrollState()
    val cardsFocus = remember { FocusRequester() }
    LaunchedEffect(list.id) { runCatching { cardsFocus.requestFocus() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit list", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value         = nameDraft,
                    onValueChange = { nameDraft = it },
                    label         = { Text("List name", fontSize = 12.sp) },
                    singleLine    = true,
                    colors        = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = Primary,
                        focusedLabelColor    = Primary,
                        cursorColor          = Primary,
                        unfocusedBorderColor = OutlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                // BasicTextField in an explicit scroll Box avoids the Compose Desktop bug where
                // an internally-scrolled OutlinedTextField mis-maps click coordinates against the
                // scroll offset, causing the cursor to land at the wrong position on focus.
                Text("Cards (one per line)", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                // Outer Box clips to 260dp. Inner Box owns verticalScroll so pointer events are
                // offset-corrected before reaching BasicTextField — fixes Compose Desktop's
                // cursor-placement bug when scroll lives on the text field itself.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                ) {
                    Box(modifier = Modifier.fillMaxWidth().verticalScroll(cardsScroll)) {
                        BasicTextField(
                            value         = cardsDraft,
                            onValueChange = { cardsDraft = it },
                            textStyle     = TextStyle(color = OnSurface, fontSize = 13.sp),
                            cursorBrush   = SolidColor(Primary),
                            modifier      = Modifier.fillMaxWidth().padding(10.dp).focusRequester(cardsFocus),
                        )
                    }
                    VerticalScrollbar(
                        adapter  = rememberScrollbarAdapter(cardsScroll),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(vertical = 2.dp),
                        style    = LocalScrollbarStyle.current.copy(unhoverColor = Primary.copy(alpha = 0.25f), hoverColor = Primary.copy(alpha = 0.55f)),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cards = cardsDraft.text.lines().map { it.trim() }.filter { it.isNotBlank() }
                    onSave(nameDraft.trim(), cards)
                },
                enabled = nameDraft.isNotBlank(),
            ) { Text("Save", color = Primary) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurfaceVariant) }
        },
        containerColor    = SurfaceContainerLowest,
        titleContentColor = OnSurface,
    )
}

@Composable
private fun SavedListRow(
    list: data.SavedSearchList,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) SurfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onLoad)
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Icon(Icons.Default.FormatListBulleted, null, tint = OnSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            list.name,
            fontSize = 12.sp,
            color = OnSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(4.dp))
        Text("${list.cards.size}", fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
            fontFamily = Mono)
        Spacer(Modifier.width(4.dp))
        if (onEdit != null) {
            Icon(
                Icons.Default.Edit, "Edit",
                tint = if (hovered) OnSurfaceVariant else Color.Transparent,
                modifier = Modifier.size(12.dp).clickable(onClick = onEdit),
            )
            Spacer(Modifier.width(4.dp))
        }
        Icon(
            Icons.Default.Close, "Delete",
            tint = if (hovered) ErrorColor else Color.Transparent,
            modifier = Modifier.size(12.dp).clickable(onClick = onDelete),
        )
    }
}

@Composable
private fun SavedResultRow(
    entry: data.SavedResultEntry,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val dateStr = remember(entry.savedAt) {
        java.text.SimpleDateFormat("d MMM ''yy, HH:mm").format(java.util.Date(entry.savedAt))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (hovered) SurfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable(onClick = onLoad)
            .padding(start = 12.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Archive, null, tint = OnSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(12.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                entry.name,
                fontSize = 12.sp, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(4.dp))
            Text("${entry.cardCount}", fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                fontFamily = Mono)
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.Close, "Delete",
                tint = if (hovered) ErrorColor else Color.Transparent,
                modifier = Modifier.size(12.dp).clickable(onClick = onDelete),
            )
        }
        Row(Modifier.padding(start = 18.dp)) {
            Text(dateStr, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.4f), fontFamily = Mono)
            if (entry.description.isNotBlank()) {
                Text(" · ", fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.3f))
                Text(
                    entry.description,
                    fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun PanelSearch(vm: SearchViewModel, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focusRequester)
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { e ->
                if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && e.isAltPressed
                ) { vm.requestSearch(); true } else false
            },
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp, color = OnSurface),
        cursorBrush = SolidColor(Primary),
        decorationBox = { innerTextField ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                    .border(1.dp, if (focused) Primary else OutlineVariant, RoundedCornerShape(4.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.TopStart,
            ) {
                if (vm.query.isEmpty()) {
                    Text(
                        "One card name per line\n" +
                        "Adding cards to an existing search only looks up the new ones\n\n" +
                        "Decklist format supported:\n" +
                        "  4x Lightning Bolt\n" +
                        "  1 Shadowspear\n" +
                        "  [Creatures]   ← ignored\n" +
                        "  # comment     ← ignored",
                        color = OnSurfaceVariant.copy(alpha = 0.3f),
                        fontFamily = Mono,
                        fontSize = 12.sp,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
private fun GhostIconButton(icon: ImageVector, desc: String, tint: Color, iconSize: Dp = 22.dp, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) SurfaceContainerHigh else Color.Transparent)
            .hoverable(interaction)
            .clickable { onClick() }
            .padding(8.dp),
    ) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(iconSize))
    }
}


@Composable
private fun PreReleaseBadge() {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val density = LocalDensity.current
    val positionProvider = remember(density) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect, windowSize: IntSize,
                layoutDirection: LayoutDirection, popupContentSize: IntSize,
            ) = IntOffset(
                anchorBounds.right - popupContentSize.width,
                anchorBounds.bottom + with(density) { 6.dp.roundToPx() },
            )
        }
    }
    Box {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier
                .hoverable(interaction)
                .clip(RoundedCornerShape(4.dp))
                .background(Primary.copy(alpha = 0.12f))
                .border(1.dp, Primary.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            Icon(Icons.Default.Warning, contentDescription = null, tint = Primary, modifier = Modifier.size(11.dp))
            Text(
                "PRE-RELEASE",
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.6.sp,
                color = Primary,
            )
        }
        if (hovered) {
            Popup(popupPositionProvider = positionProvider) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = SurfaceContainerHigh,
                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f)),
                    modifier = Modifier.widthIn(max = 260.dp),
                ) {
                    Text(
                        "You are running a pre-release build (v${data.BuildInfo.VERSION}). " +
                            "It may be unstable. Stable builds are available in Settings → Early Access.",
                        fontSize = 11.sp,
                        color = OnSurface,
                        lineHeight = 16.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    )
                }
            }
        }
    }
}

private const val SUPPORT_URL = "https://ko-fi.com/icaruscomplexza"

@Composable
private fun SettingsMenu(vm: SearchViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        GhostIconButton(Icons.Default.Settings, "Settings", tint = OnSurfaceVariant, iconSize = 16.dp) {
            expanded = !expanded
        }
        if (expanded) {
            Popup(
                alignment = Alignment.BottomEnd,
                offset = IntOffset(0, 4),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = true),
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceContainerHigh,
                    border = BorderStroke(1.dp, OutlineVariant),
                    modifier = Modifier.width(252.dp),
                ) {
                    Column(Modifier.padding(6.dp)) {
                        Text(
                            "Settings",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = OnSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f), modifier = Modifier.padding(bottom = 4.dp))
                        SettingsCheckItem(
                            label = "Early Access",
                            sublabel = "Opt in to beta / pre-release builds",
                            checked = vm.earlyAccess,
                            onCheckedChange = { vm.earlyAccess = it },
                        )
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                        SettingsActionItem(
                            label = if (vm.updateCheckState == UpdateCheckState.CHECKING) "Checking…" else "Check for updates",
                            sublabel = "Current version: v${data.BuildInfo.VERSION}",
                            icon = Icons.Default.Refresh,
                            onClick = { if (vm.updateCheckState != UpdateCheckState.CHECKING) { vm.checkForUpdates(); expanded = false } },
                        )
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                        if (vm.syncStatus == SyncStatus.DISCONNECTED) {
                            SettingsActionItem(
                                label = "Connect Google Drive",
                                sublabel = "Sync saved lists & results across devices",
                                icon = Icons.Default.CloudSync,
                                onClick = { vm.connectGoogleDrive {}; expanded = false },
                            )
                        } else {
                            SettingsActionItem(
                                label = "Sync now",
                                sublabel = vm.syncAccountEmail?.let { "Connected as $it" } ?: "Connected to Google Drive",
                                icon = Icons.Default.CloudSync,
                                onClick = { vm.syncNow(); expanded = false },
                            )
                            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                            SettingsActionItem(
                                label = "Disconnect Google Drive",
                                icon = Icons.Default.CloudOff,
                                onClick = { vm.disconnectGoogleDrive(); expanded = false },
                            )
                        }
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                        SettingsActionItem(
                            label = "Report a bug",
                            sublabel = "Open a GitHub issue",
                            icon = Icons.Default.BugReport,
                            onClick = {
                                runCatching { Desktop.getDesktop().browse(URI(network.BUG_REPORT_URL)) }
                                expanded = false
                            },
                        )
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f), modifier = Modifier.padding(vertical = 4.dp))
                        SettingsLinkItem(
                            label = "Support ZArchive",
                            sublabel = "Support on Ko-fi",
                            url = SUPPORT_URL,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsCheckItem(
    label: String,
    sublabel: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) SurfaceContainerHighest else Color.Transparent)
            .hoverable(interaction)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 13.sp, color = OnSurface)
            if (sublabel != null) {
                Text(sublabel, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) Primary else OnSurfaceVariant.copy(alpha = 0.5f),
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun SettingsLinkItem(label: String, sublabel: String? = null, url: String) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) SurfaceContainerHighest else Color.Transparent)
            .hoverable(interaction)
            .clickable { openUrl(url) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(
            Icons.Default.Favorite,
            contentDescription = null,
            tint = if (hovered) ErrorColor else OnSurfaceVariant.copy(alpha = 0.45f),
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 13.sp, color = if (hovered) Primary else OnSurface)
            if (sublabel != null) {
                Text(sublabel, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}


@Composable
private fun SettingsActionItem(label: String, sublabel: String? = null, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) SurfaceContainerHighest else Color.Transparent)
            .hoverable(interaction)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (hovered) Primary else OnSurfaceVariant.copy(alpha = 0.45f), modifier = Modifier.size(18.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 13.sp, color = if (hovered) Primary else OnSurface)
            if (sublabel != null) {
                Text(sublabel, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun UpdateStatusFooter(state: UpdateCheckState) {
    val borderColor = when (state) {
        UpdateCheckState.UPDATE_FOUND -> Primary
        UpdateCheckState.UP_TO_DATE   -> Tertiary
        else                          -> OutlineVariant
    }
    Column(Modifier.fillMaxWidth().background(SurfaceContainerLowest)) {
        HorizontalDivider(color = borderColor.copy(alpha = 0.5f))
        if (state == UpdateCheckState.CHECKING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Primary,
                trackColor = SurfaceContainerHighest,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                UpdateCheckState.CHECKING -> {
                    Icon(Icons.Default.Refresh, contentDescription = null, tint = Primary, modifier = Modifier.size(13.dp))
                    Text("Checking for updates…", fontSize = 11.sp, color = OnSurfaceVariant)
                }
                UpdateCheckState.UPDATE_FOUND -> {
                    Icon(Icons.Default.Info, contentDescription = null, tint = Primary, modifier = Modifier.size(13.dp))
                    Text("Update available — open Settings → Check for updates to download.", fontSize = 11.sp, color = Primary)
                }
                UpdateCheckState.UP_TO_DATE -> {
                    Icon(Icons.Default.Check, contentDescription = null, tint = Tertiary, modifier = Modifier.size(13.dp))
                    Text("Already up to date", fontSize = 11.sp, color = Tertiary)
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun SyncStatusFooter(state: SyncStatus, error: String?) {
    val borderColor = when (state) {
        SyncStatus.SYNCED -> Tertiary
        SyncStatus.ERROR  -> ErrorColor
        else              -> OutlineVariant
    }
    Column(Modifier.fillMaxWidth().background(SurfaceContainerLowest)) {
        HorizontalDivider(color = borderColor.copy(alpha = 0.5f))
        if (state == SyncStatus.SYNCING) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(2.dp),
                color = Primary,
                trackColor = SurfaceContainerHighest,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                SyncStatus.SYNCING -> {
                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = Primary, modifier = Modifier.size(13.dp))
                    Text("Syncing with Google Drive…", fontSize = 11.sp, color = OnSurfaceVariant)
                }
                SyncStatus.SYNCED -> {
                    Icon(Icons.Default.CloudDone, contentDescription = null, tint = Tertiary, modifier = Modifier.size(13.dp))
                    Text("Synced with Google Drive", fontSize = 11.sp, color = Tertiary)
                }
                SyncStatus.ERROR -> {
                    Icon(Icons.Default.CloudOff, contentDescription = null, tint = ErrorColor, modifier = Modifier.size(13.dp))
                    Text("Sync failed" + (error?.let { " — $it" } ?: ""), fontSize = 11.sp, color = ErrorColor)
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun ModalScrim(content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {},
        contentAlignment = Alignment.Center,
        content = content,
    )
}

@Composable
private fun UpdateDialog(
    info: network.UpdateInfo,
    vm: SearchViewModel,
    onDismiss: () -> Unit,
    onInstall: () -> Unit,
) {
    val canAutoInstall = info.downloadUrl != null && vm.canInstallUpdate
    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(400.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Update available", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                Text(
                    "ZArchive ${info.tag} is available. You're on v${data.BuildInfo.VERSION}.",
                    fontSize = 13.sp, color = OnSurface,
                )
                if (canAutoInstall) {
                    Text(
                        "ZArchive will download the update, then close and replace itself. It will relaunch automatically.",
                        fontSize = 12.sp, color = OnSurfaceVariant,
                    )
                } else {
                    Text(
                        "Download the new version from GitHub Releases, extract, and replace your current ZArchive folder.",
                        fontSize = 12.sp, color = OnSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, OutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                    ) { Text("Later", fontSize = 12.sp) }
                    if (canAutoInstall) {
                        Button(
                            onClick = onInstall,
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                        ) { Text("Download & Install", fontSize = 12.sp) }
                    } else {
                        Button(
                            onClick = {
                                runCatching { Desktop.getDesktop().browse(URI(info.releaseUrl)) }
                                onDismiss()
                            },
                            shape = RoundedCornerShape(4.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                        ) { Text("Open release page", fontSize = 12.sp) }
                    }
                }
            }
        }
    }
}

@Composable
private fun AddToSearchDialog(
    newCount: Int,
    totalCount: Int,
    unavailableCount: Int,
    onAddNew: () -> Unit,
    onAddNewAndRefresh: () -> Unit,
    onSearchAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val existingCount = totalCount - newCount
    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(480.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Add to existing results?",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary,
                )
                Text(
                    "$existingCount card${if (existingCount == 1) "" else "s"} already in results. " +
                    "Your query has $newCount new card${if (newCount == 1) "" else "s"}" +
                    if (unavailableCount > 0)
                        ", and $unavailableCount of the existing ${if (unavailableCount == 1) "is" else "are"} still unavailable."
                    else ".",
                    fontSize = 13.sp, color = OnSurface,
                )
                Spacer(Modifier.height(2.dp))
                if (unavailableCount > 0) {
                    Button(
                        onClick = onAddNewAndRefresh,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    ) { Text("Add $newCount new + refresh $unavailableCount unavailable", fontSize = 12.sp) }
                }
                Button(
                    onClick = onAddNew,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = if (unavailableCount > 0)
                        ButtonDefaults.buttonColors(containerColor = SurfaceContainerHighest, contentColor = OnSurface)
                    else ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text("Add $newCount new only", fontSize = 12.sp) }
                OutlinedButton(
                    onClick = onSearchAll,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, OutlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                ) { Text("Search all $totalCount", fontSize = 12.sp) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Cancel", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
private fun FirstListSyncPromptDialog(onConnect: () -> Unit, onDismiss: () -> Unit) {
    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(420.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Sync across devices?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                Text(
                    "Connect Google Drive to keep your saved lists and results in sync between this " +
                        "computer and your phone. You can also do this later from Settings.",
                    fontSize = 13.sp, color = OnSurface,
                )
                Spacer(Modifier.height(2.dp))
                Button(
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text("Connect Google Drive", fontSize = 12.sp) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Not now", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
private fun AllAlreadySearchedDialog(
    count: Int,
    unavailableCount: Int,
    onRefreshUnavailable: () -> Unit,
    onResearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(480.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Already in results",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary,
                )
                Text(
                    "All $count card${if (count == 1) "" else "s"} in your query are already in the current results" +
                    if (unavailableCount > 0)
                        ", and $unavailableCount of ${if (count == 1) "it" else "them"} ${if (unavailableCount == 1) "is" else "are"} still unavailable."
                    else ".",
                    fontSize = 13.sp, color = OnSurface,
                )
                Spacer(Modifier.height(2.dp))
                if (unavailableCount > 0) {
                    Button(
                        onClick = onRefreshUnavailable,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    ) { Text("Refresh $unavailableCount unavailable", fontSize = 12.sp) }
                }
                OutlinedButton(
                    onClick = onResearch,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, OutlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                ) { Text("Re-search all $count", fontSize = 12.sp) }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Keep results", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)) }
            }
        }
    }
}

@Composable
private fun SearchSummaryDialog(vm: SearchViewModel) {
    data class CardBuckets(val inStock: Set<String>, val outOfStock: Set<String>, val notFound: List<String>)
    val buckets = remember(vm.results.size) {
        val byCard = vm.results.groupBy { it.card }
        val inStock = mutableSetOf<String>()
        val outOfStock = mutableSetOf<String>()
        for (card in vm.searchedCards) {
            val listings = byCard[card] ?: emptyList()
            val hasInStock = listings.any { it.title != null && it.available != false }
            val hasTitle = listings.any { it.title != null }
            when {
                hasInStock -> inStock.add(card)
                hasTitle   -> outOfStock.add(card)
            }
        }
        CardBuckets(inStock, outOfStock, vm.searchedCards.filter { it !in inStock && it !in outOfStock })
    }
    val cfStores = vm.cfBlockedStores.toList()
    val totalCards = vm.searchedCards.size

    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(400.dp).heightIn(max = 560.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    "Search complete",
                    fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary,
                )

                // Stats row — always visible, not scrolled
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${buckets.inStock.size}/$totalCards",
                            fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                            color = if (buckets.inStock.size == totalCards) Tertiary else Primary,
                        )
                        Text("in stock", fontSize = 11.sp, color = OnSurfaceVariant)
                    }
                    if (buckets.outOfStock.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${buckets.outOfStock.size}",
                                fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = ErrorColor,
                            )
                            Text("out of stock", fontSize = 11.sp, color = OnSurfaceVariant)
                        }
                    }
                    if (cfStores.isNotEmpty()) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                "${cfStores.size}",
                                fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                                color = ErrorColor,
                            )
                            Text("rate limited", fontSize = 11.sp, color = OnSurfaceVariant)
                        }
                    }
                }

                // Scrollable detail sections
                val scrollState = rememberScrollState()
                Box(Modifier.weight(1f, fill = false)) {
                    Column(
                        Modifier.verticalScroll(scrollState).padding(end = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (buckets.outOfStock.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Out of stock everywhere:",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = ErrorColor.copy(alpha = 0.9f),
                                )
                                buckets.outOfStock.forEach { card ->
                                    Text("· $card", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                        if (buckets.notFound.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "No listings found:",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = OnSurfaceVariant,
                                )
                                buckets.notFound.forEach { card ->
                                    Text("· $card", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                                }
                            }
                        }
                        if (cfStores.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "Rate limited — results may be incomplete:",
                                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                                    color = ErrorColor.copy(alpha = 0.9f),
                                )
                                cfStores.forEach { store ->
                                    Text("· $store", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                                }
                                Text(
                                    "Cloudflare blocked these stores mid-search. Try again with fewer cards, " +
                                    "or search them individually.",
                                    fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f),
                                )
                            }
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(scrollState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                        style = LocalScrollbarStyle.current.copy(
                            unhoverColor = Primary.copy(alpha = 0.25f),
                            hoverColor   = Primary.copy(alpha = 0.55f),
                        ),
                    )
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    Button(
                        onClick = { vm.dismissSearchSummary() },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    ) { Text("OK", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun DownloadProgressDialog(phase: String, progress: Float, error: String?, onCancel: () -> Unit) {
    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(380.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (error != null) {
                    Text("Update failed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor)
                    Text(error, fontSize = 12.sp, color = OnSurfaceVariant)
                    Button(
                        onClick = onCancel,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Close", fontSize = 12.sp) }
                } else {
                    Text(phase, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                    if (progress.isNaN()) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Primary,
                            trackColor = SurfaceContainerHighest,
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { progress.coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                            color = Primary,
                            trackColor = SurfaceContainerHighest,
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            if (progress.isNaN()) "…" else "${(progress * 100).toInt()}%",
                            fontSize = 12.sp, fontFamily = Mono, color = OnSurfaceVariant,
                        )
                        Text(
                            "ZArchive will relaunch automatically when done.",
                            fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                    OutlinedButton(
                        onClick = onCancel,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, OutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Cancel", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun CrashReportDialog(crashLog: String, onDismiss: () -> Unit) {
    var copied by remember { mutableStateOf(false) }

    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(460.dp),
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("ZArchive crashed last session", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor)
                Text(
                    "Would you like to report this? Copy the crash log, then open the GitHub issue page — it'll open pre-labelled and ready to paste into.",
                    fontSize = 13.sp, color = OnSurface,
                )
                Text("Crash log", fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
                Box(
                    Modifier.fillMaxWidth().height(100.dp)
                        .background(SurfaceContainerLowest, RoundedCornerShape(4.dp))
                        .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                        .padding(8.dp)
                ) {
                    val scroll = rememberScrollState()
                    Text(
                        crashLog,
                        fontSize = 10.sp, fontFamily = Mono, color = OnSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.verticalScroll(scroll),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, OutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                        modifier = Modifier.weight(1f),
                    ) { Text("Dismiss", fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = {
                            runCatching {
                                val sel = java.awt.datatransfer.StringSelection(crashLog)
                                java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                copied = true
                            }
                        },
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, if (copied) Tertiary else OutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = if (copied) Tertiary else OnSurface),
                        modifier = Modifier.weight(1f),
                    ) { Text(if (copied) "Copied!" else "Copy log", fontSize = 12.sp) }
                    Button(
                        onClick = {
                            if (!copied) {
                                runCatching {
                                    val sel = java.awt.datatransfer.StringSelection(crashLog)
                                    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(sel, sel)
                                    copied = true
                                }
                            }
                            runCatching { Desktop.getDesktop().browse(URI(network.CRASH_REPORT_URL)) }
                            onDismiss()
                        },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                        modifier = Modifier.weight(1f),
                    ) { Text("Open GitHub", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(vm: SearchViewModel) {
    var storesExpanded by remember { mutableStateOf(false) }

    Column {
        if (vm.isSearching) {
            LinearProgressIndicator(
                progress = { if (vm.totalCardChecks == 0) 0f else vm.completedCardChecks.toFloat() / vm.totalCardChecks },
                modifier = Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Primary,
                trackColor = SurfaceContainerHighest,
            )
            Spacer(Modifier.height(6.dp))
        }

        // Status text + collapsible store grid toggle
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(vm.statusText, fontSize = 12.sp, color = OnSurfaceVariant, modifier = Modifier.weight(1f))
            if (vm.storeStatuses.isNotEmpty()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable { storesExpanded = !storesExpanded }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text(
                        if (storesExpanded) "Hide stores" else "Show stores",
                        fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f),
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        if (storesExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                        null, tint = OnSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(14.dp),
                    )
                }
            }
        }

        AnimatedVisibility(
            visible = storesExpanded && vm.storeStatuses.isNotEmpty(),
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = SurfaceContainerLow,
                border = BorderStroke(1.dp, OutlineVariant),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val entries = vm.storeStatuses.entries.toList()
                    val total = vm.searchedCards.size
                    entries.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { (store, status) ->
                                StoreStatusChip(store, status,
                                    done = vm.storeCardCounts[store] ?: 0,
                                    total = total,
                                    cfBlocked = store in vm.cfBlockedStores,
                                    modifier = Modifier.weight(1f))
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StoreStatusChip(
    store: String,
    status: StoreStatus,
    done: Int,
    total: Int,
    cfBlocked: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val (icon, tint, bg) = when (status) {
        StoreStatus.PENDING  -> Triple(Icons.Default.HourglassEmpty, OnSurfaceVariant.copy(alpha = 0.4f), SurfaceContainerHighest)
        StoreStatus.CHECKING -> Triple(Icons.Default.Sync,           Primary,                             Primary.copy(alpha = 0.08f))
        StoreStatus.DONE     -> Triple(Icons.Default.CheckCircle,     Tertiary,                            Tertiary.copy(alpha = 0.08f))
    }
    val nameColor = when (status) {
        StoreStatus.PENDING  -> OnSurfaceVariant.copy(alpha = 0.5f)
        StoreStatus.CHECKING -> OnSurface
        StoreStatus.DONE     -> OnSurfaceVariant
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(12.dp))
        Spacer(Modifier.width(6.dp))
        Text(store, fontSize = 11.sp, color = nameColor,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (cfBlocked) {
            Spacer(Modifier.width(6.dp))
            Text(
                "rate limited",
                fontFamily = Mono,
                fontSize = 9.sp,
                color = ErrorColor.copy(alpha = 0.85f),
            )
        } else if (total > 0 && status != StoreStatus.PENDING) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$done/$total",
                fontFamily = Mono,
                fontSize = 10.sp,
                color = when (status) {
                    StoreStatus.DONE     -> Tertiary.copy(alpha = 0.8f)
                    StoreStatus.CHECKING -> Primary.copy(alpha = 0.8f)
                    else                 -> OnSurfaceVariant.copy(alpha = 0.4f)
                },
            )
        }
    }
}

private enum class ResultsTab(val label: String, val icon: ImageVector) {
    RESULTS("Search Results", Icons.Default.Storefront),
    ORDERS("Order Lists", Icons.Default.ShoppingCart),
    MONITORS("Search Monitors", Icons.Default.Notifications),
}

@Composable
private fun ResultsPane(vm: SearchViewModel, tab: ResultsTab) {
    // Monitors is a persistent config screen, independent of whether a search has run.
    if (tab == ResultsTab.MONITORS) {
        SearchMonitorsPane(vm)
        return
    }
    if (vm.searchedCards.isEmpty() && vm.results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Enter a card name and press Search", color = OnSurfaceVariant, fontSize = 14.sp)
        }
        return
    }
    // Hoist scroll + summary state here so tab switches don't reset them.
    val listState       = rememberLazyListState()
    var summaryExpanded by remember { mutableStateOf(true) }
    var summaryFilter   by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                    vm.hoverOnThumbnailOnly = !vm.hoverOnThumbnailOnly
                },
        ) {
            Text("Preview card art on thumbnail hover only", fontSize = 12.sp, color = OnSurfaceVariant)
            Spacer(Modifier.width(6.dp))
            Icon(
                if (vm.hoverOnThumbnailOnly) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (vm.hoverOnThumbnailOnly) Primary else OutlineVariant,
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        when (tab) {
            ResultsTab.RESULTS -> SearchResultsTab(
                vm, listState,
                summaryExpanded, { summaryExpanded = it },
                summaryFilter,   { summaryFilter   = it },
            )
            ResultsTab.ORDERS  -> OrderListsPane(vm)
            ResultsTab.MONITORS -> Unit // handled by the early return above
        }
    }
}

// Folder-tab strip that sits at the bottom of the header banner, just above the divider line.
// The active tab is filled with the content-area colour and top-rounded so it reads as a folder
// tab connected to the panel below the line.
@Composable
private fun FolderTabs(selected: ResultsTab, onSelect: (ResultsTab) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        ResultsTab.entries.forEach { t ->
            val active = t == selected
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) Surface else SurfaceContainerLowest)
                    .border(
                        1.dp,
                        if (active) OutlineVariant else OutlineVariant.copy(alpha = 0.4f),
                        shape,
                    )
                    .clickable { onSelect(t) }
                    .padding(horizontal = 16.dp, vertical = if (active) 9.dp else 7.dp),
            ) {
                Icon(t.icon, null, tint = if (active) Primary else OnSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(15.dp))
                Text(
                    t.label,
                    fontSize = 13.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) OnSurface else OnSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun SearchMonitorsPane(vm: SearchViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Background search monitor", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Text(
                    when {
                        !vm.monitorEnabled -> "Off"
                        vm.monitorLastCheckedAt == 0L -> "On — checking soon…"
                        else -> "On — last checked " +
                            java.text.SimpleDateFormat("HH:mm").format(java.util.Date(vm.monitorLastCheckedAt)) +
                            (vm.monitorStatusText.takeIf { it.isNotEmpty() }?.let { " · $it" } ?: "")
                    },
                    fontSize = 12.sp,
                    color = OnSurfaceVariant,
                )
            }
            Switch(
                checked = vm.monitorEnabled,
                onCheckedChange = { vm.monitorEnabled = it },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Primary,
                    checkedTrackColor = Primary.copy(alpha = 0.4f),
                ),
            )
        }
        HorizontalDivider(color = OutlineVariant)

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Card(s) to monitor", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            var focused by remember { mutableStateOf(false) }
            BasicTextField(
                value = vm.monitorQuery,
                onValueChange = { vm.monitorQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp, color = OnSurface),
                cursorBrush = SolidColor(Primary),
                decorationBox = { innerTextField ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                            .border(1.dp, if (focused) Primary else OutlineVariant, RoundedCornerShape(4.dp))
                            .padding(12.dp),
                        contentAlignment = Alignment.TopStart,
                    ) {
                        if (vm.monitorQuery.isEmpty()) {
                            Text(
                                "One card name per line — same format as the main search",
                                color = OnSurfaceVariant.copy(alpha = 0.3f),
                                fontFamily = Mono,
                                fontSize = 12.sp,
                            )
                        }
                        innerTextField()
                    }
                },
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Check every", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .width(64.dp)
                        .height(32.dp)
                        .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                        .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    var intervalText by remember(vm.monitorIntervalHours) { mutableStateOf(vm.monitorIntervalHours.toString()) }
                    BasicTextField(
                        value = intervalText,
                        onValueChange = { raw ->
                            val digits = raw.filter { it.isDigit() }
                            intervalText = digits
                            digits.toIntOrNull()?.let { if (it >= 1) vm.monitorIntervalHours = it }
                        },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = OnSurface, fontFamily = Mono, fontSize = 13.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Text("hour(s) — minimum 1", fontSize = 12.sp, color = OnSurfaceVariant)
            }
        }

        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OptionsSectionHeader("Stores to monitor")
            val allStores = data.STORES.keys.sorted()
            val allSelected = allStores.all { it in vm.monitorStores }
            Surface(
                shape = RoundedCornerShape(6.dp),
                color = SurfaceContainerHigh,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            vm.monitorStores = if (allSelected) emptySet() else allStores.toSet()
                        }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Text(
                        if (allSelected) "Deselect all stores" else "Select all stores",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = OnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (allSelected) Primary else OutlineVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            allStores.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth()) {
                    pair.forEach { store ->
                        OptionToggle(
                            checked = store in vm.monitorStores,
                            label = store,
                            onChange = { vm.setMonitorStoreEnabled(store, it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

// Monitor thumbnail is shown at 2x+ the normal 46x64 table-row size (also matches MTG card
// aspect ratio ~63:88 more closely) since it's the focal point of the found-card list.
private val MONITOR_THUMB_W = 100.dp
private val MONITOR_THUMB_H = 140.dp

@Composable
private fun MonitorFoundDialog(
    hits: List<SearchResult>,
    images: Map<String, String>,
    onCloseAll: () -> Unit,
    onGoToStore: (SearchResult) -> Unit,
) {
    ModalScrim {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(560.dp).heightIn(max = 640.dp),
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HeaderBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Notifications, null, tint = Tertiary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "${hits.size} card${if (hits.size == 1) "" else "s"} found",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurface,
                        modifier = Modifier.weight(1f),
                    )
                }
                HorizontalDivider(color = OutlineVariant)
                LazyColumn(modifier = Modifier.weight(1f, fill = false).padding(horizontal = 20.dp)) {
                    itemsIndexed(
                        hits,
                        key = { _, hit -> "${hit.store}|${hit.title}|${hit.priceZar}" },
                    ) { index, hit ->
                        if (index > 0) HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
                        val imagePath = hit.title?.let { images[it] } ?: images[hit.card]
                        MonitorFoundRow(hit, imagePath, onGoToStore = { onGoToStore(hit) })
                    }
                }
                HorizontalDivider(color = OutlineVariant)
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = onCloseAll,
                        shape = RoundedCornerShape(4.dp),
                        border = BorderStroke(1.dp, OutlineVariant),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                    ) { Text("Close", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun MonitorFoundRow(hit: SearchResult, imagePath: String?, onGoToStore: () -> Unit) {
    val density = LocalDensity.current
    val thumbInteraction = remember { MutableInteractionSource() }
    val thumbHovered by thumbInteraction.collectIsHoveredAsState()
    val popupInteraction = remember { MutableInteractionSource() }
    val popupHovered by popupInteraction.collectIsHoveredAsState()
    val showPopup = thumbHovered || popupHovered

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
    ) {
        Box(Modifier.hoverable(thumbInteraction)) {
            CardThumbnail(
                imagePath, dimmed = false,
                width = MONITOR_THUMB_W, height = MONITOR_THUMB_H,
                showShimmerWhenMissing = true,
            )
            // Enlarged card art on hover — to the right of the thumbnail by default, falls to the
            // left if it won't fit. popupInteraction keeps it alive when the cursor crosses from
            // the thumbnail into the popup itself.
            if (showPopup && imagePath != null) {
                val bmp = imageCache[imagePath]
                if (bmp != null) {
                    val provider = remember(density) {
                        object : PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: IntRect,
                                windowSize: IntSize,
                                layoutDirection: LayoutDirection,
                                popupContentSize: IntSize,
                            ): IntOffset {
                                val gap = with(density) { 4.dp.roundToPx() }
                                val x = anchorBounds.right + gap
                                val clampedX =
                                    if (x + popupContentSize.width <= windowSize.width) x
                                    else (anchorBounds.left - gap - popupContentSize.width).coerceAtLeast(0)
                                val y = (anchorBounds.top)
                                    .coerceAtMost(windowSize.height - popupContentSize.height)
                                    .coerceAtLeast(0)
                                return IntOffset(clampedX, y)
                            }
                        }
                    }
                    Popup(popupPositionProvider = provider) {
                        Box(Modifier.hoverable(popupInteraction)) { CardImagePopup(bmp) }
                    }
                }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                hit.title ?: hit.card,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = OnSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(hit.store, fontSize = 12.sp, color = OnSecondaryContainer)
            hit.priceZar?.let {
                Text(
                    formatZar(it),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = Mono,
                    color = Primary,
                )
            }
        }
        Button(
            onClick = onGoToStore,
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
        ) { Text("Go to store", fontSize = 12.sp) }
    }
}

// Per-card click-through links to Luckshack, which we don't scrape (Cloudflare-protected).
// Lives outside the normal results so it's clearly "just a link", never a search result.
@Composable
private fun LuckshackLinks(cards: List<String>) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SurfaceContainerLow,
        border = BorderStroke(1.dp, OutlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(Icons.Default.OpenInNew, null, tint = OnSecondaryContainer, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text("Luckshack (not searched — open directly):", fontSize = 11.sp,
                color = OnSurfaceVariant, maxLines = 1)
            Spacer(Modifier.width(10.dp))
            // weight(1f) + horizontalScroll: chips lay out at natural width and scroll within the row.
            // pointerInput remaps the vertical wheel to horizontal so no Shift is needed.
            val hScroll = rememberScrollState()
            val hScrollScope = rememberCoroutineScope()
            Row(
                modifier = Modifier.weight(1f)
                    .horizontalScroll(hScroll)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val scrollDelta = event.changes.firstOrNull()?.scrollDelta ?: continue
                                if (scrollDelta.y != 0f) {
                                    event.changes.forEach { it.consume() }
                                    hScrollScope.launch {
                                        hScroll.scrollTo((hScroll.value + scrollDelta.y * 60).toInt().coerceIn(0, hScroll.maxValue))
                                    }
                                }
                            }
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                cards.forEach { card ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceContainerHighest)
                            .border(1.dp, OutlineVariant.copy(alpha = 0.5f), RoundedCornerShape(4.dp))
                            .clickable { openUrl(luckshackSearchUrl(card)) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(card, fontSize = 11.sp, color = OnSecondaryContainer, softWrap = false)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.OpenInNew, "Open $card on Luckshack",
                            tint = OnSurfaceVariant, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchResultsTab(
    vm: SearchViewModel,
    listState: LazyListState,
    summaryExpanded: Boolean,
    onSummaryExpandedChange: (Boolean) -> Unit,
    summaryFilter: String,
    onSummaryFilterChange: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()

    // Summary tracks the full searched list from the moment search() fires.
    // Results list shows cards that have received at least one response, or are
    // currently being individually refreshed (so they stay in place during refresh).
    val summaryCards = vm.searchedCards.ifEmpty { vm.results.map { it.card }.distinct() }
    val hasResults   = vm.results.map { it.card }.toSet()
    val resultCards  = summaryCards.filter { it in hasResults || vm.refreshingCards.containsKey(it) }

    Column {
        if (summaryCards.size > 1) {
            CardSummaryPanel(
                cards = summaryCards,
                results = vm.results.toList(),
                images = vm.images,
                isSearching = vm.isSearching,
                onCardClick = { card ->
                    val idx = resultCards.indexOf(card)
                    if (idx >= 0) scope.launch { listState.scrollToItem(idx) }
                },
                includePartialMatches = vm.includePartialMatches,
                expanded = summaryExpanded,
                onExpandedChange = onSummaryExpandedChange,
                filter = summaryFilter,
                onFilterChange = onSummaryFilterChange,
                showCardOnHover = vm.showCardOnHover,
                onShowCardOnHoverChange = { vm.showCardOnHover = it },
            )
        }
        if (resultCards.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = 10.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(resultCards) { card ->
                    CardSection(
                        card = card,
                        results = vm.results.filter { it.card == card },
                        images = vm.images,
                        isSearching = vm.isSearching,
                        isRefreshing = card in vm.refreshingCards,
                        hoverOnThumbnailOnly = vm.hoverOnThumbnailOnly,
                        pinnedUrl = vm.pinnedListings[card],
                        onTogglePin = { url ->
                            if (vm.pinnedListings[card] == url) vm.pinnedListings.remove(card)
                            else vm.pinnedListings[card] = url
                        },
                        includePartialMatches = vm.includePartialMatches,
                        excludedFromOrder = vm.excludedCards.containsKey(card),
                        onToggleExcludeFromOrder = {
                            if (vm.excludedCards.containsKey(card)) vm.excludedCards.remove(card)
                            else vm.excludedCards[card] = Unit
                        },
                        onRefresh = { vm.refreshCard(card) },
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
            VerticalScrollbar(
                adapter = rememberScrollbarAdapter(listState),
                modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                style   = LocalScrollbarStyle.current.copy(unhoverColor = Primary.copy(alpha = 0.25f), hoverColor = Primary.copy(alpha = 0.55f)),
            )
            } // Box
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchOptionsDialog(vm: SearchViewModel, onDismiss: () -> Unit) {
    BasicAlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainer,
            border = BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.width(560.dp),
        ) {
            Column {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(HeaderBg)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Settings, null, tint = Primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Search Options",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = OnSurface,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.Close, "Close", tint = OnSurfaceVariant,
                        modifier = Modifier.size(16.dp).clickable { onDismiss() },
                    )
                }
                HorizontalDivider(color = OutlineVariant)

                Column(
                    modifier = Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // ── Stores ────────────────────────────────────────────────
                    OptionsSectionHeader("Stores")
                    val allStores = data.STORES.keys.sorted()
                    val allSelected = allStores.all { it in vm.enabledStores }
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = SurfaceContainerHigh,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                                    if (allSelected) vm.enabledStores = emptySet()
                                    else vm.enabledStores = allStores.toSet()
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                if (allSelected) "Deselect all stores" else "Select all stores",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = OnSurface,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null,
                                tint = if (allSelected) Primary else OutlineVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    allStores.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth()) {
                            pair.forEach { store ->
                                OptionToggle(
                                    checked = store in vm.enabledStores,
                                    label = store,
                                    onChange = { vm.setStoreEnabled(store, it) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }

                    HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

                    // ── Search behaviour ──────────────────────────────────────
                    OptionsSectionHeader("Search Behaviour")
                    OptionToggle(
                        checked = vm.ignoreBasicLands,
                        label = "Ignore Basic Lands",
                        sublabel = "Plains, Island, Swamp, Mountain, Forest, Wastes",
                        onChange = { vm.ignoreBasicLands = it },
                    )
                    OptionToggle(
                        checked = vm.includePartialMatches,
                        label = "Include partial name matches",
                        sublabel = "Show listings whose name contains your search term, not just exact card name matches. " +
                            "Useful when searching a word that appears in many card names (e.g. \"Kraken\").",
                        onChange = { vm.includePartialMatches = it },
                    )

                    // ── Luckshack ─────────────────────────────────────────────
                    OptionsSectionHeader("Luckshack")
                    OptionToggle(
                        checked = vm.autoOpenLuckshack,
                        label = "Auto-open in browser on search",
                        sublabel = "Luckshack is Cloudflare-protected and cannot be scraped. " +
                            "When enabled, opens each card's Luckshack search page automatically.",
                        onChange = { vm.autoOpenLuckshack = it },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsSectionHeader(title: String) {
    Text(
        title.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = OnSurfaceVariant.copy(alpha = 0.5f),
        letterSpacing = 1.sp,
    )
}

@Composable
private fun OptionToggle(
    checked: Boolean,
    label: String,
    onChange: (Boolean) -> Unit,
    sublabel: String? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = if (sublabel != null) Alignment.Top else Alignment.CenterVertically,
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onChange(!checked) }
            .padding(top = 3.dp, bottom = 3.dp, start = 4.dp, end = 8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 12.sp, color = OnSurface)
            if (sublabel != null) {
                Text(sublabel, fontSize = 10.sp, color = OnSurfaceVariant, lineHeight = 14.sp)
            }
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            contentDescription = null,
            tint = if (checked) Primary else Outline,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun FilterField(value: String, placeholder: String = "Filter results…", onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(34.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceContainerLow)
            .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp),
    ) {
        Icon(Icons.Default.FilterList, null, tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, color = OnSurfaceVariant.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(color = OnSurface, fontFamily = Mono, fontSize = 13.sp),
                cursorBrush = SolidColor(Primary),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Default.Close, "Clear filter", tint = OnSurfaceVariant,
                modifier = Modifier.size(16.dp).clip(RoundedCornerShape(2.dp)).clickable { onChange("") })
        }
    }
}

@Composable
private fun CardSection(
    card: String,
    results: List<SearchResult>,
    images: Map<String, String>,
    isSearching: Boolean = false,
    isRefreshing: Boolean = false,
    hoverOnThumbnailOnly: Boolean = false,
    pinnedUrl: String? = null,
    onTogglePin: ((String) -> Unit)? = null,
    includePartialMatches: Boolean = false,
    excludedFromOrder: Boolean = false,
    onToggleExcludeFromOrder: () -> Unit = {},
    onRefresh: () -> Unit = {},
) {
    var cardFilter by remember { mutableStateOf("") }
    val cardFilterQ = cardFilter.trim().lowercase()

    val allListings = preferExactMatches(card, results.filter { it.title != null }, exactOnly = !includePartialMatches)
    val listings = if (cardFilterQ.isEmpty()) allListings else allListings.filter { r ->
        r.title!!.lowercase().contains(cardFilterQ) || r.store.lowercase().contains(cardFilterQ)
    }
    val inStock    = listings.filter { it.available != false }.sortedByPrice()
    val outOfStock = listings.filter { it.available == false }.sortedByPrice()
    val notStocked = results.filter { it.title == null && it.note == "not stocked" }.map { it.store }.sorted()
    val errors     = results.filter { it.title == null && it.note != "not stocked" }

    var inStockExpanded  by remember { mutableStateOf(true) }
    var outStockExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerLowest,
        border = BorderStroke(1.dp, OutlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
        // Section header — no inner border; the Surface shape clips it cleanly
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(HeaderBg)
                .clickable { inStockExpanded = !inStockExpanded }
                .padding(horizontal = 12.dp, vertical = 9.dp),
        ) {
            Icon(Icons.Default.Token, null, tint = Primary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                buildString { append("Matches: \""); append(card); append('"') },
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
            )
            Spacer(Modifier.width(8.dp))
            CountBadge("${allListings.size}")
            if (isSearching || isRefreshing) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = Primary,
                    strokeWidth = 1.5.dp,
                )
            }
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(enabled = !isRefreshing, onClick = onRefresh)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Text(
                    "Refresh search",
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    color = if (isRefreshing) Primary.copy(alpha = 0.4f) else OnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.alignBy { it.measuredHeight / 2 },
                )
                Icon(
                    Icons.Default.Refresh,
                    contentDescription = null,
                    tint = if (isRefreshing) Primary.copy(alpha = 0.4f) else OnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp).alignBy { it.measuredHeight / 2 },
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .clickable(onClick = onToggleExcludeFromOrder)
                    .padding(horizontal = 7.dp, vertical = 4.dp),
            ) {
                Text(
                    if (excludedFromOrder) "Excluded from order" else "Exclude from order",
                    fontSize = 11.sp,
                    lineHeight = 11.sp,
                    color = if (excludedFromOrder) ErrorColor else OnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.alignBy { it.measuredHeight / 2 },
                )
                Icon(
                    if (excludedFromOrder) Icons.Default.RemoveShoppingCart else Icons.Default.ShoppingCart,
                    contentDescription = null,
                    tint = if (excludedFromOrder) ErrorColor else OnSurfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.size(13.dp).alignBy { it.measuredHeight / 2 },
                )
            }
            Spacer(Modifier.width(4.dp))
            Icon(
                if (inStockExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                null, tint = OnSurfaceVariant, modifier = Modifier.size(16.dp),
            )
        }
        HorizontalDivider(color = OutlineVariant)

        AnimatedVisibility(
            visible = inStockExpanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)) {
                FilterField(cardFilter) { cardFilter = it }
                Spacer(Modifier.height(8.dp))

                if (allListings.isEmpty()) {
                    ListingTable(emptyList(), images, dimmed = false,
                        emptyMessage = if (isSearching) "Searching…" else "No listings found at any store", card = card,
                        hoverOnThumbnailOnly = hoverOnThumbnailOnly, pinnedUrl = pinnedUrl, onTogglePin = onTogglePin)
                } else if (listings.isEmpty()) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No listings match \"$cardFilter\"",
                            color = OnSurfaceVariant, fontSize = 13.sp,
                        )
                    }
                } else {
                    if (inStock.isNotEmpty()) {
                        ListingTable(inStock, images, dimmed = false, emptyMessage = null, card = card,
                            hoverOnThumbnailOnly = hoverOnThumbnailOnly, pinnedUrl = pinnedUrl, onTogglePin = onTogglePin)
                    }
                    if (outOfStock.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(4.dp))
                                .background(HeaderBg)
                                .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                                .clickable { outStockExpanded = !outStockExpanded }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                        ) {
                            Icon(Icons.Default.VisibilityOff, null, tint = OnSurfaceVariant, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Out of Stock", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurfaceVariant)
                            Spacer(Modifier.width(8.dp))
                            CountBadge("${outOfStock.size}", muted = true)
                            Spacer(Modifier.weight(1f))
                            Icon(
                                if (outStockExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                null, tint = OnSurfaceVariant, modifier = Modifier.size(16.dp),
                            )
                        }
                        AnimatedVisibility(
                            visible = outStockExpanded,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut(),
                        ) {
                            ListingTable(outOfStock, images, dimmed = true, emptyMessage = null, card = card,
                                hoverOnThumbnailOnly = hoverOnThumbnailOnly, pinnedUrl = pinnedUrl, onTogglePin = onTogglePin)
                        }
                    }
                }

                if (errors.isNotEmpty() || notStocked.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    errors.forEach { err ->
                        Text("⚠ ${err.store}: ${err.note}", fontSize = 11.sp, color = ErrorColor,
                            modifier = Modifier.padding(vertical = 1.dp))
                    }
                    if (notStocked.isNotEmpty()) {
                        Text("Not stocked: ${notStocked.joinToString(", ")}", fontSize = 11.sp,
                            color = OnSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }
        }
        } // Column
    } // Surface
}

@Composable
private fun CountBadge(text: String, muted: Boolean = false) {
    Text(
        text,
        fontSize = 12.sp,
        color = if (muted) OnSurfaceVariant.copy(alpha = 0.6f) else OnSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
            .background(SurfaceContainerHighest)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun ListingTable(rows: List<SearchResult>, images: Map<String, String>, dimmed: Boolean, emptyMessage: String?, card: String = "", hoverOnThumbnailOnly: Boolean = false, pinnedUrl: String? = null, onTogglePin: ((String) -> Unit)? = null) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (dimmed) SurfaceContainerLowest else SurfaceContainerLow,
        border = BorderStroke(1.dp, if (dimmed) OutlineVariant.copy(alpha = 0.5f) else OutlineVariant),
        modifier = Modifier.fillMaxWidth().then(if (dimmed) Modifier.alpha(0.7f) else Modifier),
    ) {
        Column {
            TableHeaderRow()
            HorizontalDivider(color = OutlineVariant)
            if (emptyMessage != null) {
                Text(emptyMessage, color = OnSurfaceVariant, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp))
            }
            rows.forEachIndexed { idx, r ->
                if (idx > 0) HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
                ListingRow(
                    result = r,
                    imagePath = (r.title?.let { images[it] }) ?: images[card],
                    dimmed = dimmed,
                    hoverOnThumbnailOnly = hoverOnThumbnailOnly,
                    isPinned = pinnedUrl == r.url,
                    onTogglePin = onTogglePin?.let { fn -> { fn(r.url) } },
                )
            }
        }
    }
}

@Composable
private fun TableHeaderRow() {
    Row(
        modifier = Modifier.fillMaxWidth().background(SurfaceContainer).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HeaderCell("Card", Modifier.width(COL_THUMB))
        HeaderCell("Name & Set", Modifier.weight(1f))
        HeaderCell("Store", Modifier.width(COL_STORE))
        HeaderCell("Status", Modifier.width(COL_STATUS))
        HeaderCell("Price", Modifier.width(COL_PRICE), alignEnd = true)
        HeaderCell("Use this Version", Modifier.width(COL_PIN), center = true)
        HeaderCell("External Link", Modifier.width(COL_LINK), center = true)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, alignEnd: Boolean = false, center: Boolean = false) {
    Box(modifier, contentAlignment = if (center) Alignment.Center else if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = OnSurfaceVariant, letterSpacing = 0.8.sp)
    }
}

// Standard icon button with a tooltip on hover. All interactive icons in the UI should use
// this so their purpose is always discoverable. Pass onClick = null for display-only icons.
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun IconTooltip(
    icon: ImageVector,
    tooltip: String,
    tint: Color,
    size: Dp = 16.dp,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    TooltipArea(
        tooltip = {
            Surface(
                color = SurfaceContainerHighest,
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, OutlineVariant),
            ) {
                Text(tooltip, modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    fontSize = 11.sp, color = OnSurface)
            }
        },
        delayMillis = 500,
        modifier = modifier,
    ) {
        Icon(
            icon,
            contentDescription = tooltip,
            tint = tint,
            modifier = Modifier.size(size).then(
                if (onClick != null) Modifier.clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ) else Modifier
            ),
        )
    }
}

@Composable
private fun ListingRow(result: SearchResult, imagePath: String?, dimmed: Boolean, hoverOnThumbnailOnly: Boolean = false, isPinned: Boolean = false, onTogglePin: (() -> Unit)? = null) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val thumbInteraction = remember { MutableInteractionSource() }
    val thumbHovered by thumbInteraction.collectIsHoveredAsState()
    val popupInteraction = remember { MutableInteractionSource() }
    val popupHovered by popupInteraction.collectIsHoveredAsState()
    val rowTriggered = if (hoverOnThumbnailOnly) thumbHovered else hovered
    val showPopup = rowTriggered || popupHovered

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(when {
                    isPinned && !dimmed -> Primary.copy(alpha = 0.07f)
                    hovered && !dimmed  -> SurfaceContainerHigh
                    else                -> Color.Transparent
                })
                .hoverable(interaction)
                .clickable { openUrl(result.url) }
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Card thumbnail
            Box(Modifier.width(COL_THUMB).then(if (hoverOnThumbnailOnly) Modifier.hoverable(thumbInteraction) else Modifier)) {
                CardThumbnail(imagePath, dimmed)
            }
            // Name & Set
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(result.title ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (dimmed) OnSurfaceVariant else OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val subtitle = result.note.takeIf { it.isNotBlank() && it !in setOf("In stock", "Out of stock", "not stocked") }
                if (subtitle != null) {
                    Text(subtitle, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            // Store
            Box(Modifier.width(COL_STORE).padding(end = 12.dp)) {
                Text(result.store, fontSize = 13.sp, fontWeight = FontWeight.Medium,
                    color = if (dimmed) OnSurfaceVariant else OnSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            // Status
            Box(Modifier.width(COL_STATUS)) { StatusChip(result.available) }
            // Price
            Box(Modifier.width(COL_PRICE), contentAlignment = Alignment.CenterEnd) {
                Text(
                    text = result.priceZar?.let { formatZar(it) } ?: "N/A",
                    fontFamily = Mono,
                    fontSize = if (result.priceZar != null) 16.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = when {
                        result.priceZar == null -> OnSurfaceVariant.copy(alpha = 0.6f)
                        dimmed                   -> OnSurfaceVariant
                        else                     -> Primary
                    },
                )
            }
            // Use this Version
            Box(Modifier.width(COL_PIN), contentAlignment = Alignment.Center) {
                if (onTogglePin != null) {
                    IconTooltip(
                        icon = if (isPinned) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        tooltip = if (isPinned) "Locked to this version — click to unlock" else "Lock to this version for order lists",
                        tint = when {
                            isPinned -> Primary
                            hovered  -> OnSurfaceVariant.copy(alpha = 0.7f)
                            else     -> OnSurfaceVariant.copy(alpha = 0.3f)
                        },
                        size = 16.dp,
                        onClick = onTogglePin,
                    )
                }
            }
            // External Link
            Box(Modifier.width(COL_LINK), contentAlignment = Alignment.Center) {
                if (!dimmed) {
                    IconTooltip(
                        icon = Icons.Default.OpenInNew,
                        tooltip = "Open listing in browser",
                        tint = if (hovered) Primary else OnSurfaceVariant,
                        size = 18.dp,
                    )
                }
            }
        }

        // Card image popup — above by default, below if too close to top of window.
        // popupInteraction keeps it alive when the cursor crosses from the row into the popup.
        if (showPopup && imagePath != null) {
            val bmp = imageCache[imagePath]
            if (bmp != null) {
                val provider = remember(density) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val gap = with(density) { 4.dp.roundToPx() }
                            val x = (anchorBounds.left + with(density) { 24.dp.roundToPx() })
                                .coerceAtMost(windowSize.width - popupContentSize.width)
                            val aboveY = anchorBounds.top - popupContentSize.height - gap
                            if (aboveY >= 0) return IntOffset(x, aboveY)
                            return IntOffset(x, anchorBounds.bottom + gap)
                        }
                    }
                }
                Popup(popupPositionProvider = provider) {
                    Box(Modifier.hoverable(popupInteraction)) { CardImagePopup(bmp) }
                }
            }
        }
    }
}


@Composable
private fun StatusChip(available: Boolean?) {
    val (text, color, filled) = when (available) {
        true  -> Triple("In Stock", Tertiary, true)
        false -> Triple("Sold Out", OnSurfaceVariant, false)
        null  -> Triple("Unknown", OnSurfaceVariant, false)
    }
    Text(
        text.uppercase(),
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        color = color,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .then(if (filled) Modifier.background(color.copy(alpha = 0.1f)) else Modifier)
            .border(1.dp, if (filled) color.copy(alpha = 0.25f) else OutlineVariant, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

// Cache loaded card art so the same image isn't re-fetched per row.
private val imageCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
private val grayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun CardThumbnail(
    path: String?,
    dimmed: Boolean,
    width: Dp = 46.dp,
    height: Dp = 64.dp,
    showShimmerWhenMissing: Boolean = false,
) {
    var bitmap by remember(path) { mutableStateOf(path?.let { imageCache[it] }) }
    LaunchedEffect(path) {
        if (path != null && bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(path)
                    if (f.exists()) javax.imageio.ImageIO.read(f)?.toComposeImageBitmap() else null
                }.getOrNull()
            }
            if (loaded != null) {
                imageCache[path] = loaded
                bitmap = loaded
            }
        }
    }
    val bmp = bitmap
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(width, height).clip(shape)
            .background(SurfaceContainerHighest)
            .border(1.dp, OutlineVariant, shape),
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (dimmed) grayscaleFilter else null,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (path != null || showShimmerWhenMissing) {
            // Either the path is known but not yet loaded from disk, or (when opted in) the path
            // itself hasn't resolved yet — either way, art may still be on its way.
            ShimmerOverlay()
        }
    }
}

@Composable
private fun ShimmerOverlay() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
        ),
        label = "shimmerProgress",
    )
    // Sweep a faint highlight band diagonally across the placeholder
    val sweep = progress * 900f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0f),
                    ),
                    start = Offset(sweep - 250f, 0f),
                    end = Offset(sweep, 400f),
                )
            )
    )
}

// MTG card aspect ratio ≈ 63×88mm
private val POPUP_CARD_W = 240.dp
private val POPUP_CARD_H = 336.dp       // full-size for results table hover
private val SUMMARY_POPUP_W = POPUP_CARD_W
private val SUMMARY_POPUP_H = POPUP_CARD_H

@Composable
private fun CardImagePopup(bitmap: ImageBitmap, width: Dp = POPUP_CARD_W, height: Dp = POPUP_CARD_H) {
    Box(
        Modifier
            .size(width, height)
            .shadow(24.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
    ) {
        Image(bitmap, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun CardSummaryPanel(
    cards: List<String>,
    results: List<SearchResult>,
    images: Map<String, String>,
    isSearching: Boolean,
    onCardClick: (String) -> Unit,
    includePartialMatches: Boolean = false,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
    showCardOnHover: Boolean = false,
    onShowCardOnHoverChange: (Boolean) -> Unit = {},
) {
    val summaryFilter = filter
    val filterQ = summaryFilter.trim().lowercase()
    val shownCards = if (filterQ.isEmpty()) cards else cards.filter { it.lowercase().contains(filterQ) }
    val foundCount = cards.count { card ->
        preferExactMatches(card, results.filter { it.card == card && it.title != null }, exactOnly = !includePartialMatches)
            .any { it.available != false }
    }
    val pendingCount = if (isSearching) cards.count { card -> results.none { it.card == card } } else 0

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerLow,
        border = BorderStroke(1.dp, OutlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Collapsible header — single Row so all text and icons share one centerline
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("Card Summary", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Spacer(Modifier.width(10.dp))
                Text(
                    "$foundCount / ${cards.size} found",
                    fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Tertiary,
                )
                if (pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "$pendingCount pending",
                        fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "click to collapse" else "click to expand",
                    fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {
                            onShowCardOnHoverChange(!showCardOnHover)
                        }
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        "Show card on hover",
                        fontSize = 10.sp,
                        color = OnSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        if (showCardOnHover) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        contentDescription = null,
                        tint = if (showCardOnHover) Primary else OnSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(16.dp),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column {
                    HorizontalDivider(color = OutlineVariant.copy(alpha = 0.6f))
                    Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                        FilterField(summaryFilter, placeholder = "Find a card in the list…") { onFilterChange(it) }
                    }
                    if (shownCards.isEmpty()) {
                        Box(
                            Modifier.fillMaxWidth().padding(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("No cards match \"$summaryFilter\"", color = OnSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    // Scrollable grid capped at ~240dp so 100-card lists don't push results off screen
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        shownCards.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                pair.forEach { card ->
                                    val cardResults = results.filter { it.card == card }
                                    val imagePath = preferExactMatches(card, cardResults.filter { it.title != null }, exactOnly = !includePartialMatches)
                                        .mapNotNull { r -> r.title?.let { images[it] } }
                                        .firstOrNull() ?: images[card]
                                    CardSummaryEntry(
                                        card = card,
                                        results = cardResults,
                                        imagePath = imagePath,
                                        isSearching = isSearching,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onCardClick(card) },
                                        includePartialMatches = includePartialMatches,
                                        showCardOnHover = showCardOnHover,
                                    )
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CardSummaryEntry(
    card: String,
    results: List<SearchResult>,
    imagePath: String? = null,
    isSearching: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    includePartialMatches: Boolean = false,
    showCardOnHover: Boolean = false,
) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hoveredRaw by interaction.collectIsHoveredAsState()
    val popupInteraction = remember { MutableInteractionSource() }
    val popupHovered by popupInteraction.collectIsHoveredAsState()
    val showPopup = showCardOnHover && (hoveredRaw || popupHovered)

    // Eagerly load the image into the in-memory cache so the popup is instant on first hover.
    LaunchedEffect(imagePath) {
        if (imagePath != null && imageCache[imagePath] == null) {
            withContext(Dispatchers.IO) {
                val f = java.io.File(imagePath)
                if (f.exists()) {
                    javax.imageio.ImageIO.read(f)?.toComposeImageBitmap()
                        ?.let { imageCache[imagePath] = it }
                }
            }
        }
    }

    val listings = preferExactMatches(card, results.filter { it.title != null }, exactOnly = !includePartialMatches)
    val hasInStock = listings.any { it.available != false }
    val hasOutOfStock = !hasInStock && listings.any { it.available == false }
    // Don't finalise Out of Stock / Not Found until searching is done — a card that's OOS at
    // one fast store might be in stock at a slower one still running.
    val (statusText, statusColor) = when {
        hasInStock               -> "Found"        to Tertiary
        isSearching              -> "…"             to OnSurfaceVariant.copy(alpha = 0.35f)
        hasOutOfStock            -> "Out of Stock"  to ErrorColor
        else                     -> "Not Found"     to OnSurfaceVariant
    }

    Box(modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerHighest)
                .hoverable(interaction)
                .clickable(indication = null, interactionSource = interaction) { onClick() }
                .padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Text(
                card,
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OnSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                statusText.uppercase(),
                fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp,
                color = statusColor,
                modifier = Modifier
                    .clip(RoundedCornerShape(2.dp))
                    .background(statusColor.copy(alpha = 0.1f))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.KeyboardArrowRight, null,
                tint = OnSurfaceVariant.copy(alpha = 0.3f),
                modifier = Modifier.size(14.dp),
            )
        }

        if (showPopup && imagePath != null) {
            val bmp = imageCache[imagePath]
            if (bmp != null) {
                // Smart side positioning: right if it fits, left otherwise, clamped to screen.
                val provider = remember(density) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val gap = with(density) { 8.dp.roundToPx() }
                            val popW = popupContentSize.width
                            val popH = popupContentSize.height
                            val y = (anchorBounds.top + (anchorBounds.height - popH) / 2)
                                .coerceIn(0, (windowSize.height - popH).coerceAtLeast(0))
                            val rightX = anchorBounds.right + gap
                            if (rightX + popW <= windowSize.width) return IntOffset(rightX, y)
                            val leftX = anchorBounds.left - gap - popW
                            if (leftX >= 0) return IntOffset(leftX, y)
                            return IntOffset((windowSize.width - popW).coerceAtLeast(0), y)
                        }
                    }
                }
                Popup(popupPositionProvider = provider) {
                    Box(Modifier.hoverable(popupInteraction)) {
                        CardImagePopup(bmp, width = SUMMARY_POPUP_W, height = SUMMARY_POPUP_H)
                    }
                }
            }
        }
    }
}

// OrderStrategy moved to jvmCommonMain/kotlin/ui/OrderStrategy.kt (Phase 4) — SearchViewModel
// (now shared) needs it; same `ui` package, no import needed here.

@Composable
private fun OrderListsPane(vm: SearchViewModel) {
    val strategy = vm.orderStrategy
    val unchecked = vm.uncheckedOrderLines
    val excludedCards = vm.excludedCards
    val priceMax = vm.orderPriceFilter.toDoubleOrNull()
    val orderListState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Recomputed automatically whenever results stream in (reads the observable list).
    val cheapest by remember { derivedStateOf { cheapestPlan(vm.searchedCards, vm.results.toList(), vm.pinnedListings, vm.includePartialMatches) } }
    val fewest   by remember { derivedStateOf { fewestStoresPlan(vm.searchedCards, vm.results.toList(), vm.pinnedListings, vm.includePartialMatches) } }
    val plan = if (strategy == OrderStrategy.CHEAPEST) cheapest else fewest

    val anyInStock = cheapest.storeOrders.isNotEmpty()

    // Totals filtered to only active lines (checked, not card-excluded, within price filter)
    val isLineActive = { line: data.OrderLine ->
        !unchecked.containsKey(line.listing.url) &&
        !excludedCards.containsKey(line.card) &&
        (priceMax == null || (line.listing.priceZar ?: 0.0) <= priceMax)
    }
    val activeStores = plan.storeOrders.count { so -> so.lines.any { isLineActive(it) } }
    val activeItems  = plan.storeOrders.sumOf { so -> so.lines.count { isLineActive(it) } }
    val activeTotal  = plan.storeOrders.sumOf { so -> so.lines.filter { isLineActive(it) }.sumOf { it.listing.priceZar ?: 0.0 } }

    Column(Modifier.fillMaxSize()) {
        // Strategy switcher
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            OrderStrategy.entries.forEach { s ->
                val active = s == strategy
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (active) Primary.copy(alpha = 0.12f) else SurfaceContainerLow)
                        .border(1.dp, if (active) Primary.copy(alpha = 0.5f) else OutlineVariant, RoundedCornerShape(4.dp))
                        .clickable { vm.orderStrategy = s }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                ) {
                    Icon(s.icon, null, tint = if (active) Primary else OnSurfaceVariant, modifier = Modifier.size(15.dp))
                    Text(s.label, fontSize = 12.sp,
                        fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (active) Primary else OnSurfaceVariant)
                }
            }
            if (vm.isSearching) {
                Spacer(Modifier.width(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), color = Primary, strokeWidth = 1.5.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("updating live…", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(strategy.blurb, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
        Spacer(Modifier.height(10.dp))

        if (!anyInStock) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.isSearching) "No in-stock cards yet — building the list as results arrive…"
                    else "No in-stock listings to build an order from.",
                    color = OnSurfaceVariant, fontSize = 13.sp,
                )
            }
            return
        }

        // Plan totals + price filter
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            PlanStat("$activeStores", if (activeStores == 1) "store" else "stores")
            Spacer(Modifier.width(20.dp))
            PlanStat("$activeItems", if (activeItems == 1) "card" else "cards")
            Spacer(Modifier.width(20.dp))
            PlanStat(formatZar(activeTotal), "total", valueColor = Primary)
            if (!vm.isSearching && plan.uncoveredCards.isNotEmpty()) {
                Spacer(Modifier.width(20.dp))
                PlanStat(
                    "${plan.uncoveredCards.size}", "unavailable", valueColor = ErrorColor,
                    onClick = { scope.launch { orderListState.animateScrollToItem(plan.storeOrders.size) } },
                )
            }
            Spacer(Modifier.weight(1f))
            // Price filter
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(SurfaceContainerLow)
                    .border(1.dp, if (priceMax != null) Primary.copy(alpha = 0.5f) else OutlineVariant, RoundedCornerShape(4.dp))
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Icon(Icons.Default.FilterList, null, tint = if (priceMax != null) Primary else OnSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp).alignBy { it.measuredHeight / 2 })
                Spacer(Modifier.width(8.dp))
                Text("Max R", fontSize = 12.sp, lineHeight = 12.sp, color = OnSurfaceVariant, fontFamily = Mono, modifier = Modifier.alignBy { it.measuredHeight / 2 })
                Spacer(Modifier.width(6.dp))
                Box(Modifier.width(64.dp), contentAlignment = Alignment.CenterStart) {
                    if (vm.orderPriceFilter.isEmpty()) {
                        Text("any", color = OnSurfaceVariant.copy(alpha = 0.4f), fontFamily = Mono, fontSize = 12.sp, lineHeight = 12.sp)
                    }
                    BasicTextField(
                        value = vm.orderPriceFilter,
                        onValueChange = { vm.orderPriceFilter = it.filter { c -> c.isDigit() || c == '.' } },
                        singleLine = true,
                        textStyle = androidx.compose.ui.text.TextStyle(color = if (priceMax != null) Primary else OnSurface, fontFamily = Mono, fontSize = 12.sp, lineHeight = 12.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (vm.orderPriceFilter.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.Close, "Clear price filter", tint = OnSurfaceVariant,
                        modifier = Modifier.size(13.dp).clip(RoundedCornerShape(2.dp)).clickable { vm.orderPriceFilter = "" }.alignBy { it.measuredHeight / 2 })
                }
            }
        }

        Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = orderListState,
            modifier = Modifier.fillMaxSize().padding(end = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(plan.storeOrders, key = { it.store }) { so ->
                StoreOrderCard(so, vm.images, unchecked, excludedCards, priceMax, vm.hoverOnThumbnailOnly)
            }
            if (!vm.isSearching && plan.uncoveredCards.isNotEmpty()) {
                item { UncoveredCard(plan.uncoveredCards) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(orderListState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style   = LocalScrollbarStyle.current.copy(unhoverColor = Primary.copy(alpha = 0.25f), hoverColor = Primary.copy(alpha = 0.55f)),
        )
        } // Box
    }
}

@Composable
private fun PlanStat(value: String, label: String, valueColor: Color = OnSurface, onClick: (() -> Unit)? = null) {
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = if (onClick != null) {
            Modifier.clip(RoundedCornerShape(4.dp)).clickable(onClick = onClick).padding(horizontal = 4.dp, vertical = 2.dp)
        } else Modifier,
    ) {
        Text(value, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            color = valueColor, textDecoration = if (onClick != null) TextDecoration.Underline else null)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = OnSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun StoreOrderCard(order: StoreOrder, images: Map<String, String>, unchecked: MutableMap<String, Unit>, excludedCards: MutableMap<String, Unit>, priceMax: Double? = null, hoverOnThumbnailOnly: Boolean = false) {
    val activeLines  = order.lines
        .filter { !unchecked.containsKey(it.listing.url) && !excludedCards.containsKey(it.card) && (priceMax == null || (it.listing.priceZar ?: 0.0) <= priceMax) }
        .distinctBy { it.listing.variantId ?: it.listing.url }
    val displayCount = activeLines.size
    val displayTotal = activeLines.sumOf { it.listing.priceZar ?: 0.0 }

    val allHaveIds = activeLines.isNotEmpty() && activeLines.all { it.listing.variantId != null }
    val platform = KNOWN_PLATFORMS[order.storeUrl]
    // WC_STORE_API (The Hidden Realm) is also WooCommerce under the hood — variation IDs are
    // resolved at search time from the product page, so the same ?add-to-cart= URL works.
    // BigCommerce uses GET /cart.php?action=add&product_id=X — same browser-session pattern.
    // PrestaShop uses GET /cart?add=1&id_product=X&qty=1&token=STATIC_TOKEN&action=update.
    val isWooCart = platform == Platform.WOOCOMMERCE || platform == Platform.WC_STORE_API
    val isBigCommerceCart = platform == Platform.BIGCOMMERCE
    val isPrestaCart = platform == Platform.PRESTASHOP &&
        activeLines.isNotEmpty() && activeLines.first().listing.cartToken != null
    val isWarren = order.store == "The Warren"
    val showCart = allHaveIds && (platform == Platform.SHOPIFY || isWooCart || isBigCommerceCart || isPrestaCart) && !isWarren

    var wooCartLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val cartTooltip = when {
        isWooCart -> "Adds one card at a time (WooCommerce limitation).\nOpens your cart automatically after all cards are added."
        isBigCommerceCart -> "Adds one card at a time (BigCommerce limitation).\nOpens your cart automatically after all cards are added."
        isPrestaCart -> "Adds one card at a time (PrestaShop limitation).\nOpens your cart automatically after all cards are added."
        else -> "Open cart with all items pre-added."
    }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerLowest,
        border = BorderStroke(1.dp, OutlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            // Store header — click opens the store
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(HeaderBg)
                    .clickable { openUrl(order.storeUrl) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Icon(Icons.Default.Storefront, null, tint = Secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(order.store, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSecondaryContainer)
                Spacer(Modifier.width(8.dp))
                CountBadge("$displayCount")
                Spacer(Modifier.weight(1f))
                Text(formatZar(displayTotal), fontFamily = Mono, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = Primary)
                Spacer(Modifier.width(10.dp))
                if (showCart) {
                    val base = order.storeUrl.trimEnd('/')
                    if (wooCartLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Primary,
                            strokeWidth = 2.dp,
                        )
                    } else {
                        val wooCartUrl = if (platform == Platform.WC_STORE_API) "$base/basket/" else "$base/cart/"
                        val cartOnClick: () -> Unit = when {
                            isWooCart -> {
                                {
                                    wooCartLoading = true
                                    scope.launch {
                                        activeLines.forEachIndexed { idx, line ->
                                            if (idx > 0) delay(6000)
                                            openUrl("$base/?add-to-cart=${line.listing.variantId}&quantity=1")
                                        }
                                        delay(6000)
                                        openUrl(wooCartUrl)
                                        wooCartLoading = false
                                    }
                                }
                            }
                            isBigCommerceCart -> {
                                {
                                    wooCartLoading = true
                                    scope.launch {
                                        activeLines.forEachIndexed { idx, line ->
                                            if (idx > 0) delay(2000)
                                            openUrl("$base/cart.php?action=add&product_id=${line.listing.variantId}")
                                        }
                                        delay(3000)
                                        openUrl("$base/cart.php")
                                        wooCartLoading = false
                                    }
                                }
                            }
                            isPrestaCart -> {
                                {
                                    val token = activeLines.first().listing.cartToken!!
                                    wooCartLoading = true
                                    scope.launch {
                                        activeLines.forEachIndexed { idx, line ->
                                            if (idx > 0) delay(2000)
                                            openUrl("$base/cart?add=1&id_product=${line.listing.variantId}&qty=1&token=$token&action=update")
                                        }
                                        delay(3000)
                                        openUrl("$base/cart")
                                        wooCartLoading = false
                                    }
                                }
                            }
                            else -> {
                                {
                                    val url = "$base/cart/" + activeLines.joinToString(",") { "${it.listing.variantId}:1" }
                                    openUrl(url)
                                }
                            }
                        }
                        @OptIn(ExperimentalFoundationApi::class)
                        TooltipArea(
                            tooltip = {
                                Surface(
                                    color = SurfaceContainerHighest,
                                    shape = RoundedCornerShape(4.dp),
                                    border = BorderStroke(1.dp, OutlineVariant),
                                ) {
                                    Text(
                                        cartTooltip,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                        fontSize = 11.sp,
                                        color = OnSurface,
                                    )
                                }
                            },
                            delayMillis = 400,
                        ) {
                            Icon(
                                Icons.Default.ShoppingCart,
                                contentDescription = "Add all to cart",
                                tint = Primary,
                                modifier = Modifier.size(16.dp).clickable(onClick = cartOnClick),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
                Icon(Icons.Default.OpenInNew, "Open store", tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            HorizontalDivider(color = OutlineVariant)
            // Column header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Include in order", fontSize = 9.sp, color = OnSurfaceVariant.copy(alpha = 0.4f),
                    letterSpacing = 0.3.sp, modifier = Modifier.weight(1f))
                Box(Modifier.width(COL_PRICE), contentAlignment = Alignment.CenterEnd) {
                    Text("Price", fontSize = 9.sp, color = OnSurfaceVariant.copy(alpha = 0.4f))
                }
                Spacer(Modifier.width(COL_ACTION))
            }
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
            if (isWarren) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                        .background(SurfaceContainerHigh.copy(alpha = 0.4f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Icon(Icons.Default.Info, null, tint = OnSurfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Cart must be filled manually on The Warren — Cloudflare protection prevents automation.",
                        fontSize = 11.sp,
                        color = OnSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
            }
            order.lines.forEachIndexed { idx, line ->
                if (idx > 0) HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
                val lineChecked = !unchecked.containsKey(line.listing.url) && !excludedCards.containsKey(line.card) && (priceMax == null || (line.listing.priceZar ?: 0.0) <= priceMax)
                OrderLineRow(
                    line = line,
                    imagePath = line.listing.title?.let { images[it] } ?: images[line.card],
                    checked = lineChecked,
                    onToggle = { isChecked ->
                        if (!isChecked) unchecked[line.listing.url] = Unit
                        else {
                            unchecked.remove(line.listing.url)
                            excludedCards.remove(line.card)
                        }
                    },
                    hoverOnThumbnailOnly = hoverOnThumbnailOnly,
                )
            }
        }
    }
}

@Composable
private fun OrderLineRow(line: data.OrderLine, imagePath: String?, checked: Boolean, onToggle: (Boolean) -> Unit, hoverOnThumbnailOnly: Boolean = false) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val thumbInteraction = remember { MutableInteractionSource() }
    val thumbHovered by thumbInteraction.collectIsHoveredAsState()
    val showPopup = if (hoverOnThumbnailOnly) thumbHovered else hovered
    val contentAlpha = if (checked) 1f else 0.35f

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hovered) SurfaceContainerHigh else Color.Transparent)
                .hoverable(interaction)
                .clickable { openUrl(line.listing.url) }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Checkbox — click consumed here, does not propagate to the row's clickable
            @OptIn(ExperimentalMaterial3Api::class)
            CompositionLocalProvider(LocalMinimumInteractiveComponentEnforcement provides false) {
                Checkbox(
                    checked = checked,
                    onCheckedChange = onToggle,
                    modifier = Modifier.size(20.dp),
                    colors = CheckboxDefaults.colors(
                        checkedColor = Primary,
                        uncheckedColor = OutlineVariant,
                        checkmarkColor = Color(0xFF3C2F00),
                    ),
                )
            }
            Spacer(Modifier.width(8.dp))
            // Content dims when unchecked
            Box(Modifier.width(COL_THUMB).alpha(contentAlpha).then(if (hoverOnThumbnailOnly) Modifier.hoverable(thumbInteraction) else Modifier)) { CardThumbnail(imagePath, dimmed = false) }
            Column(Modifier.weight(1f).padding(end = 12.dp).alpha(contentAlpha)) {
                Text(line.card, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                line.listing.title?.takeIf { it != line.card }?.let {
                    Text(it, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.width(COL_PRICE).alpha(contentAlpha), contentAlignment = Alignment.CenterEnd) {
                Text(line.listing.priceZar?.let { formatZar(it) } ?: "N/A", fontFamily = Mono,
                    fontSize = if (line.listing.priceZar != null) 15.sp else 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (line.listing.priceZar == null) OnSurfaceVariant.copy(alpha = 0.6f) else Primary)
            }
            Box(Modifier.width(COL_ACTION), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.OpenInNew, "Open listing",
                    tint = if (hovered) Primary else OnSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }

        if (showPopup && imagePath != null) {
            val bmp = imageCache[imagePath]
            if (bmp != null) {
                // Prefer above the row; fall back to below if it would clip the top of the window.
                val provider = remember(density) {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset {
                            val gap = with(density) { 6.dp.roundToPx() }
                            val x = (anchorBounds.left + with(density) { 24.dp.roundToPx() })
                                .coerceAtMost(windowSize.width - popupContentSize.width)
                            val aboveY = anchorBounds.top - popupContentSize.height - gap
                            if (aboveY >= 0) return IntOffset(x, aboveY)
                            return IntOffset(x, anchorBounds.bottom + gap)
                        }
                    }
                }
                Popup(popupPositionProvider = provider) { CardImagePopup(bmp) }
            }
        }
    }
}

@Composable
private fun UncoveredCard(cards: List<String>) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerLowest,
        border = BorderStroke(1.dp, ErrorColor.copy(alpha = 0.4f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.ErrorOutline, null, tint = ErrorColor, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("Not available anywhere (${cards.size})", fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold, color = ErrorColor)
            }
            Spacer(Modifier.height(6.dp))
            Text(cards.joinToString(", "), fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}

private fun openUrl(url: String) {
    Thread {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (_: Exception) {}
    }.also { it.isDaemon = true }.start()
}
