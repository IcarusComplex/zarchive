package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
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
import ui.theme.SurfaceContainerLowest
import ui.theme.ZArchiveTheme

private enum class ResultsTab(val label: String, val icon: ImageVector) {
    RESULTS("Search Results", Icons.Default.Storefront),
    ORDERS("Order Lists", Icons.Default.ShoppingCart),
    MONITORS("Search Monitors", Icons.Default.Notifications),
}

/**
 * Android app shell (Phase 6) — a Scaffold + TopAppBar replacing desktop's custom draggable
 * TitleBar (window chrome concepts don't apply on Android), with the same folder-tab navigation.
 * Only the Search Results tab has real content so far (search input + live progress); Order Lists
 * (Phase 10) and Search Monitors (deferred, Phase 14) show placeholders until their phases land.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AndroidApp(vm: SearchViewModel) {
    var tab by remember { mutableStateOf(ResultsTab.RESULTS) }

    ZArchiveTheme {
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = HeaderBg),
                )
            },
            containerColor = Surface,
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(HeaderBg)
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    FolderTabs(tab, { tab = it })
                }
                HorizontalDivider(color = OutlineVariant)
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                ) {
                    when (tab) {
                        ResultsTab.RESULTS -> {
                            SearchInputScreen(vm)
                            if (vm.isSearching || vm.statusText.isNotEmpty()) {
                                Spacer(Modifier.height(12.dp))
                                StatusRow(vm)
                            }
                            Spacer(Modifier.height(16.dp))
                            Text(
                                "Results will appear here (Phase 7).",
                                color = OnSurfaceVariant.copy(alpha = 0.6f),
                                fontSize = 13.sp,
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
    }
}

@Composable
private fun FolderTabs(selected: ResultsTab, onSelect: (ResultsTab) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        ResultsTab.entries.forEach { t ->
            val active = t == selected
            val shape = RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .clip(shape)
                    .background(if (active) Surface else SurfaceContainerLowest)
                    .border(1.dp, if (active) OutlineVariant else OutlineVariant.copy(alpha = 0.4f), shape)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = if (active) 9.dp else 7.dp),
            ) {
                Icon(
                    t.icon, null,
                    tint = if (active) Primary else OnSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    t.label,
                    fontSize = 12.sp,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (active) OnSurface else OnSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    softWrap = false,
                )
            }
        }
    }
}
