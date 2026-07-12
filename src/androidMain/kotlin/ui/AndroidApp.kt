package ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.BuildInfo
import ui.theme.HeaderBg
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.Surface
import ui.theme.ZArchiveTheme

private enum class ResultsTab(val label: String, val icon: ImageVector) {
    RESULTS("Search Results", Icons.Default.Storefront),
    ORDERS("Order Lists", Icons.Default.ShoppingCart),
    MONITORS("Search Monitors", Icons.Default.Notifications),
}

/**
 * Android app shell (Phase 6) — a Scaffold + TopAppBar replacing desktop's custom draggable
 * TitleBar (window chrome concepts don't apply on Android). The 3 top-level sections are a bottom
 * `NavigationBar` (native Android/Material3 convention, added in Phase 8), not desktop's top
 * `FolderTabs` row — desktop is unaffected, this only changes `AndroidApp`.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidApp(vm: SearchViewModel) {
    var tab by remember { mutableStateOf(ResultsTab.RESULTS) }
    var summaryExpanded by remember { mutableStateOf(true) }
    var summaryFilter by remember { mutableStateOf("") }
    var expandedImagePath by remember { mutableStateOf<String?>(null) }
    var showListsDialog by remember { mutableStateOf(false) }
    var showResultsDialog by remember { mutableStateOf(false) }
    val platformActions = remember { PlatformActions() }
    val resultsScrollState = rememberScrollState()
    var scrollRootY by remember { mutableStateOf(0) }

    ZArchiveTheme {
        Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "ZArchive",
                                color = Primary,
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
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderBg),
                )
            },
            bottomBar = {
                NavigationBar(containerColor = HeaderBg) {
                    ResultsTab.entries.forEach { t ->
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
                            SearchInputScreen(vm)
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
                        ResultsTab.ORDERS -> Text(
                            "Order lists (Phase 10).",
                            color = OnSurfaceVariant.copy(alpha = 0.6f),
                            fontSize = 13.sp,
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
    }
}
