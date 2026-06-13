package ui

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.window.WindowScope
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
import kotlinx.coroutines.Dispatchers
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
private val HeaderBg                 = Color(0xFF00060E)

private val Mono = FontFamily.Monospace

// Table column widths — sized for the ~1100dp window; Name takes the remainder.
private val COL_THUMB  = 72.dp
private val COL_STORE  = 160.dp
private val COL_STATUS = 110.dp
private val COL_PRICE  = 112.dp
private val COL_ACTION = 60.dp

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
    onCloseRequest: () -> Unit = {},
) {
    val vm = remember { SearchViewModel() }
    val colorScheme = darkColorScheme(
        background   = Surface,
        surface      = SurfaceContainer,
        primary      = Primary,
        onPrimary    = OnPrimary,
        onBackground = OnSurface,
        onSurface    = OnSurface,
    )

    var tab by remember { mutableStateOf(ResultsTab.RESULTS) }
    val hasSearch = vm.searchedCards.isNotEmpty() || vm.results.isNotEmpty()

    MaterialTheme(colorScheme = colorScheme) {
        Column(
            Modifier.fillMaxSize().background(Surface)
                .border(1.dp, OutlineVariant)
        ) {
            AppHeader(vm, onCloseRequest, tab.takeIf { hasSearch }) { tab = it }
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
}

@Composable
private fun WindowScope.AppHeader(
    vm: SearchViewModel,
    onCloseRequest: () -> Unit,
    tab: ResultsTab?,
    onSelectTab: (ResultsTab) -> Unit,
) {
    val bannerBitmap = remember {
        runCatching {
            Thread.currentThread().contextClassLoader.getResourceAsStream("banner_logo.png")
                ?.use { javax.imageio.ImageIO.read(it) }
                ?.toComposeImageBitmap()
        }.getOrNull()
    }

    WindowDraggableArea {
        Surface(color = HeaderBg, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth()) {
                // ── Window controls — top-right, standard Windows position ──
                Row(
                    Modifier.align(Alignment.TopEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (System.getProperty("mtg.debug") == "true") {
                        val debugDir = java.io.File(System.getProperty("user.home"), "zarchive-debug")
                        GhostIconButton(Icons.Default.BugReport, "Debug", tint = OnSurfaceVariant, iconSize = 16.dp) {
                            debugDir.mkdirs(); Desktop.getDesktop().open(debugDir)
                        }
                    }
                    SettingsMenu(vm)
                    Spacer(Modifier.width(2.dp))
                    Box(Modifier.width(1.dp).height(14.dp).background(OutlineVariant.copy(alpha = 0.5f)))
                    Spacer(Modifier.width(2.dp))
                    GhostIconButton(Icons.Default.Remove, "Minimize", tint = OnSurfaceVariant, iconSize = 16.dp) {
                        runCatching { (window as? java.awt.Frame)?.extendedState = java.awt.Frame.ICONIFIED }
                    }
                    GhostIconButton(Icons.Default.Fullscreen, "Maximize / Restore", tint = OnSurfaceVariant, iconSize = 16.dp) {
                        runCatching {
                            val f = window as? java.awt.Frame ?: return@GhostIconButton
                            f.extendedState = if (f.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0)
                                java.awt.Frame.NORMAL else java.awt.Frame.MAXIMIZED_BOTH
                        }
                    }
                    GhostIconButton(Icons.Default.Close, "Close", tint = ErrorColor, iconSize = 16.dp) {
                        onCloseRequest()
                    }
                }

                // ── Main content ─────────────────────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .padding(start = 20.dp, end = 96.dp, top = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Left 1/3: logo with folder tabs overlaid at its bottom edge
                    Box(Modifier.weight(1f).background(Color(0xFF00040B))) {
                        HeaderLogo(bannerBitmap)
                        if (tab != null) {
                            FolderTabs(tab, onSelectTab, Modifier.align(Alignment.BottomStart))
                        }
                    }

                    // Right 2/3: search row + checkboxes
                    Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            HeaderSearch(vm, Modifier.weight(1f))
                            SearchInfoIcon()
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy((-6).dp),
                            ) {
                                if (vm.isSearching) {
                                    GhostIconButton(Icons.Default.Close, "Cancel", tint = ErrorColor, iconSize = 18.dp) { vm.cancel() }
                                } else {
                                    GhostIconButton(Icons.Default.Search, "Search", tint = Primary, iconSize = 18.dp) { vm.search() }
                                }
                                Text("Alt+Enter", fontSize = 8.sp, color = OnSurfaceVariant.copy(alpha = 0.35f))
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.padding(bottom = 6.dp),
                        ) {
                            IgnoreBasicLandsToggle(vm.ignoreBasicLands) { vm.ignoreBasicLands = it }
                            LuckshackToggle(vm.autoOpenLuckshack) { vm.autoOpenLuckshack = it }
                        }
                    }
                }
            }
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
            modifier = Modifier.fillMaxWidth(),  // width-constrained; height follows aspect ratio
            alignment = Alignment.CenterStart,
        )
    } else {
        Text("ZArchive", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Primary)
    }
}

@Composable
private fun HeaderSearch(vm: SearchViewModel, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    OutlinedTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        modifier = modifier.focusRequester(focusRequester).onPreviewKeyEvent { e ->
            if (e.key == Key.Enter && e.type == KeyEventType.KeyDown && e.isAltPressed
            ) { vm.search(); true } else false
        },
        placeholder = { Text("Search card name…  (one per line for multiple)", color = OnSurfaceVariant.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 13.sp) },
        leadingIcon = { Icon(Icons.Default.Search, null, tint = OnSurfaceVariant) },
        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = Mono, fontSize = 13.sp),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { if (!vm.isSearching) vm.search() }),
        maxLines = 5,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor      = OnSurface,
            unfocusedTextColor    = OnSurface,
            focusedContainerColor   = SurfaceContainerLow,
            unfocusedContainerColor = SurfaceContainerLow,
            focusedBorderColor    = Primary,
            unfocusedBorderColor  = OutlineVariant,
            cursorColor           = Primary,
        ),
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
private fun SearchInfoIcon() {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(Modifier.hoverable(interaction).padding(4.dp)) {
        Icon(
            Icons.Default.HelpOutline,
            contentDescription = "Search help",
            tint = OnSurfaceVariant.copy(alpha = if (hovered) 0.75f else 0.35f),
            modifier = Modifier.size(16.dp),
        )
        if (hovered) {
            Popup(alignment = Alignment.BottomEnd, offset = IntOffset(-240, 16)) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = SurfaceContainerHigh,
                    border = BorderStroke(1.dp, OutlineVariant),
                    modifier = Modifier.width(296.dp),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Search format", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.5f))
                        InfoLine("One card name per line")
                        InfoLine("Alt+Enter or click ⌕ to search")
                        Spacer(Modifier.height(2.dp))
                        Text("Decklist format — quantity prefix is stripped:", fontSize = 11.sp, color = OnSurfaceVariant)
                        Surface(color = SurfaceContainerLowest, shape = RoundedCornerShape(4.dp)) {
                            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                                listOf(
                                    "4x Lightning Bolt",
                                    "1 Shadowspear",
                                    "[Creatures]   ← section header, ignored",
                                    "# comment     ← ignored",
                                ).forEach {
                                    Text(it, fontSize = 11.sp, fontFamily = Mono,
                                        color = if (it.startsWith("[") || it.startsWith("#"))
                                            OnSurfaceVariant.copy(alpha = 0.45f) else OnSurface)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val SUPPORT_URL = "https://buymeacoffee.com/icaruscomplexza"

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
                        SettingsLinkItem(
                            label = "Support ZArchive",
                            sublabel = "Buy me a coffee ☕",
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(if (hovered) SurfaceContainerHighest else Color.Transparent)
            .hoverable(interaction)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = null,
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                checkmarkColor = OnPrimary,
                uncheckedColor = OnSurfaceVariant.copy(alpha = 0.5f),
            ),
            modifier = Modifier.size(18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 13.sp, color = OnSurface)
            if (sublabel != null) {
                Text(sublabel, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
            }
        }
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
            .clickable { runCatching { Desktop.getDesktop().browse(URI(url)) } }
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
private fun InfoLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("•", fontSize = 11.sp, color = Primary)
        Text(text, fontSize = 11.sp, color = OnSurfaceVariant)
    }
}

@Composable
private fun StatusRow(vm: SearchViewModel) {
    var storesExpanded by remember { mutableStateOf(false) }

    Column {
        if (vm.isSearching) {
            LinearProgressIndicator(
                progress = { if (vm.totalStores == 0) 0f else vm.completedStores.toFloat() / vm.totalStores },
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
        if (total > 0 && status != StoreStatus.PENDING) {
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
}

@Composable
private fun ResultsPane(vm: SearchViewModel, tab: ResultsTab) {
    if (vm.searchedCards.isEmpty() && vm.results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Enter a card name and press Search", color = OnSurfaceVariant, fontSize = 14.sp)
        }
        return
    }
    when (tab) {
        ResultsTab.RESULTS -> SearchResultsTab(vm)
        ResultsTab.ORDERS  -> OrderListsPane(vm)
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
private fun SearchResultsTab(vm: SearchViewModel) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Summary tracks the full searched list from the moment search() fires.
    // Results list only shows cards that have received at least one response.
    val summaryCards = vm.searchedCards.ifEmpty { vm.results.map { it.card }.distinct() }
    val resultCards  = vm.results.map { it.card }.distinct()

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
            )
        }
        if (resultCards.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                items(resultCards) { card ->
                    CardSection(
                        card = card,
                        results = vm.results.filter { it.card == card },
                        images = vm.images,
                        isSearching = vm.isSearching,
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
private fun IgnoreBasicLandsToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onChange(!checked) }
            .padding(end = 4.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                uncheckedColor = Outline,
                checkmarkColor = OnPrimary,
            ),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text("Ignore basic lands", fontSize = 12.sp, color = OnSurfaceVariant)
    }
}

@Composable
private fun LuckshackToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onChange(!checked) }
            .padding(end = 8.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onChange,
            colors = CheckboxDefaults.colors(
                checkedColor = Primary,
                uncheckedColor = Outline,
                checkmarkColor = OnPrimary,
            ),
            modifier = Modifier.size(28.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text("Auto-open Luckshack", fontSize = 12.sp, color = OnSurfaceVariant)
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
) {
    var cardFilter by remember { mutableStateOf("") }
    val cardFilterQ = cardFilter.trim().lowercase()

    val allListings = preferExactMatches(card, results.filter { it.title != null })
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
            if (isSearching) {
                Spacer(Modifier.width(8.dp))
                CircularProgressIndicator(
                    modifier = Modifier.size(12.dp),
                    color = Primary,
                    strokeWidth = 1.5.dp,
                )
            }
            Spacer(Modifier.weight(1f))
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
                        emptyMessage = if (isSearching) "Searching…" else "No listings found at any store", card = card)
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
                        ListingTable(inStock, images, dimmed = false, emptyMessage = null, card = card)
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
                            ListingTable(outOfStock, images, dimmed = true, emptyMessage = null, card = card)
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
private fun ListingTable(rows: List<SearchResult>, images: Map<String, String>, dimmed: Boolean, emptyMessage: String?, card: String = "") {
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
                ListingRow(r, (r.title?.let { images[it] }) ?: images[card], dimmed = dimmed)
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
        HeaderCell("Action", Modifier.width(COL_ACTION), center = true)
    }
}

@Composable
private fun HeaderCell(text: String, modifier: Modifier, alignEnd: Boolean = false, center: Boolean = false) {
    Box(modifier, contentAlignment = if (center) Alignment.Center else if (alignEnd) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(text.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            color = OnSurfaceVariant, letterSpacing = 0.8.sp)
    }
}

@Composable
private fun ListingRow(result: SearchResult, imagePath: String?, dimmed: Boolean) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(if (hovered && !dimmed) SurfaceContainerHigh else Color.Transparent)
                .hoverable(interaction)
                .clickable { openUrl(result.url) }
                .padding(horizontal = 16.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Card thumbnail
            Box(Modifier.width(COL_THUMB)) {
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
            // Action
            Box(Modifier.width(COL_ACTION), contentAlignment = Alignment.Center) {
                if (!dimmed) {
                    Icon(Icons.Default.OpenInNew, contentDescription = "Open in browser",
                        tint = if (hovered) Primary else OnSurfaceVariant, modifier = Modifier.size(18.dp))
                }
            }
        }

        // Card image popup above the row on hover
        if (hovered && imagePath != null) {
            val bmp = imageCache[imagePath]
            if (bmp != null) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = with(density) {
                        IntOffset(
                            24.dp.roundToPx(),
                            -(POPUP_CARD_H + 6.dp).roundToPx(),
                        )
                    },
                ) { CardImagePopup(bmp) }
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
private fun CardThumbnail(path: String?, dimmed: Boolean) {
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
        Modifier.size(46.dp, 64.dp).clip(shape)
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
        }
    }
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
) {
    var expanded by remember { mutableStateOf(true) }
    var summaryFilter by remember { mutableStateOf("") }
    val filterQ = summaryFilter.trim().lowercase()
    val shownCards = if (filterQ.isEmpty()) cards else cards.filter { it.lowercase().contains(filterQ) }
    val foundCount = cards.count { card ->
        preferExactMatches(card, results.filter { it.card == card && it.title != null })
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
            // Collapsible header
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
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
                }
                Text(
                    if (expanded) "click to collapse" else "click to expand",
                    fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.4f),
                    modifier = Modifier.align(Alignment.Center),
                )
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = OnSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterEnd).size(18.dp),
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
                        FilterField(summaryFilter, placeholder = "Find a card in the list…") { summaryFilter = it }
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
                                    val imagePath = preferExactMatches(card, cardResults.filter { it.title != null })
                                        .mapNotNull { r -> r.title?.let { images[it] } }
                                        .firstOrNull() ?: images[card]
                                    CardSummaryEntry(
                                        card = card,
                                        results = cardResults,
                                        imagePath = imagePath,
                                        isSearching = isSearching,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onCardClick(card) },
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
) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hoveredRaw by interaction.collectIsHoveredAsState()
    val showPopup = hoveredRaw

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

    val listings = preferExactMatches(card, results.filter { it.title != null })
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
                .background(if (hoveredRaw) SurfaceContainerHigh else SurfaceContainerHighest)
                .hoverable(interaction)
                .clickable { onClick() }
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
                tint = if (hoveredRaw) Primary else OnSurfaceVariant.copy(alpha = 0.3f),
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
                    CardImagePopup(bmp, width = SUMMARY_POPUP_W, height = SUMMARY_POPUP_H)
                }
            }
        }
    }
}

private enum class OrderStrategy(val label: String, val icon: ImageVector, val blurb: String) {
    CHEAPEST("Cheapest total", Icons.Default.Savings,
        "Lowest possible spend — each card from whichever store is cheapest, even if that means more parcels."),
    FEWEST("Fewest packages", Icons.Default.Inventory2,
        "Fewest orders to place — the smallest set of stores that covers everything in stock, price aside."),
}

@Composable
private fun OrderListsPane(vm: SearchViewModel) {
    var strategy by remember { mutableStateOf(OrderStrategy.CHEAPEST) }

    // Recomputed automatically whenever results stream in (reads the observable list).
    val cheapest by remember { derivedStateOf { cheapestPlan(vm.searchedCards, vm.results.toList()) } }
    val fewest   by remember { derivedStateOf { fewestStoresPlan(vm.searchedCards, vm.results.toList()) } }
    val plan = if (strategy == OrderStrategy.CHEAPEST) cheapest else fewest

    val anyInStock = cheapest.storeOrders.isNotEmpty()

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
                        .clickable { strategy = s }
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

        // Plan totals
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 10.dp)) {
            PlanStat("${plan.storeCount}", if (plan.storeCount == 1) "store" else "stores")
            Spacer(Modifier.width(20.dp))
            PlanStat("${plan.itemCount}", if (plan.itemCount == 1) "card" else "cards")
            Spacer(Modifier.width(20.dp))
            PlanStat(formatZar(plan.grandTotal), "total", valueColor = Primary)
            if (plan.uncoveredCards.isNotEmpty()) {
                Spacer(Modifier.width(20.dp))
                PlanStat("${plan.uncoveredCards.size}", "unavailable", valueColor = ErrorColor)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(plan.storeOrders, key = { it.store }) { so ->
                StoreOrderCard(so, vm.images)
            }
            if (plan.uncoveredCards.isNotEmpty()) {
                item { UncoveredCard(plan.uncoveredCards) }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun PlanStat(value: String, label: String, valueColor: Color = OnSurface) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(value, fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = valueColor)
        Spacer(Modifier.width(5.dp))
        Text(label, fontSize = 11.sp, color = OnSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
    }
}

@Composable
private fun StoreOrderCard(order: StoreOrder, images: Map<String, String>) {
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
                CountBadge("${order.itemCount}")
                Spacer(Modifier.weight(1f))
                Text(formatZar(order.total), fontFamily = Mono, fontSize = 16.sp,
                    fontWeight = FontWeight.Bold, color = Primary)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.OpenInNew, "Open store", tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            HorizontalDivider(color = OutlineVariant)
            order.lines.forEachIndexed { idx, line ->
                if (idx > 0) HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
                OrderLineRow(line, line.listing.title?.let { images[it] } ?: images[line.card])
            }
        }
    }
}

@Composable
private fun OrderLineRow(line: data.OrderLine, imagePath: String?) {
    val density = LocalDensity.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

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
            Box(Modifier.width(COL_THUMB)) { CardThumbnail(imagePath, dimmed = false) }
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(line.card, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                line.listing.title?.takeIf { it != line.card }?.let {
                    Text(it, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Box(Modifier.width(COL_PRICE), contentAlignment = Alignment.CenterEnd) {
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

        if (hovered && imagePath != null) {
            val bmp = imageCache[imagePath]
            if (bmp != null) {
                Popup(
                    alignment = Alignment.TopStart,
                    offset = with(density) {
                        IntOffset(24.dp.roundToPx(), -(POPUP_CARD_H + 6.dp).roundToPx())
                    },
                ) { CardImagePopup(bmp) }
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
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {}
}
