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
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.delay
import data.SearchResult
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
fun App(
    onCloseRequest: () -> Unit = {},
    windowState: WindowState = rememberWindowState(),
    window: java.awt.Window? = null,
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

    MaterialTheme(colorScheme = colorScheme) {
        Column(
            Modifier.fillMaxSize().background(Surface)
                .border(1.dp, OutlineVariant)
        ) {
            AppHeader(vm, onCloseRequest, windowState, window)
            HorizontalDivider(color = OutlineVariant)
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                if (vm.isSearching || vm.statusText.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    StatusRow(vm)
                }
                Spacer(Modifier.height(8.dp))
                ResultsPane(vm)
            }
        }
    }
}

@Composable
private fun AppHeader(
    vm: SearchViewModel,
    onCloseRequest: () -> Unit,
    windowState: WindowState,
    window: java.awt.Window?,
) {
    Surface(color = HeaderBg, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        val w = window ?: return@detectDragGestures
                        val f = w as? java.awt.Frame
                        if (f == null || f.extendedState and java.awt.Frame.MAXIMIZED_BOTH == 0) {
                            w.location = java.awt.Point(
                                (w.x + dragAmount.x).toInt(),
                                (w.y + dragAmount.y).toInt(),
                            )
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val bannerBitmap = remember {
                runCatching {
                    // Load from the classpath so it resolves both in `gradlew run` and the packaged app
                    Thread.currentThread().contextClassLoader.getResourceAsStream("banner_logo.png")
                        ?.use { javax.imageio.ImageIO.read(it) }
                        ?.toComposeImageBitmap()
                }.getOrNull()
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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

            Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeaderSearch(vm, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    IgnoreBasicLandsToggle(vm.ignoreBasicLands) { vm.ignoreBasicLands = it }
                    LuckshackToggle(vm.autoOpenLuckshack) { vm.autoOpenLuckshack = it }
                }
            }

            if (vm.isSearching) {
                GhostIconButton(Icons.Default.Close, "Cancel", tint = ErrorColor, iconSize = 18.dp) { vm.cancel() }
            } else {
                GhostIconButton(Icons.Default.Search, "Search", tint = Primary, iconSize = 18.dp) { vm.search() }
            }

            if (System.getProperty("mtg.debug") == "true") {
                val debugDir = java.io.File(System.getProperty("user.home"), "zarchive-debug")
                GhostIconButton(Icons.Default.BugReport, "Debug", tint = OnSurfaceVariant, iconSize = 16.dp) {
                    debugDir.mkdirs(); Desktop.getDesktop().open(debugDir)
                }
            }

            GhostIconButton(Icons.Default.Remove, "Minimize", tint = OnSurfaceVariant, iconSize = 16.dp) {
                (window as? java.awt.Frame)?.extendedState = java.awt.Frame.ICONIFIED
            }
            GhostIconButton(Icons.Default.Fullscreen, "Maximize / Restore", tint = OnSurfaceVariant, iconSize = 16.dp) {
                val f = window as? java.awt.Frame ?: return@GhostIconButton
                f.extendedState = if (f.extendedState and java.awt.Frame.MAXIMIZED_BOTH != 0)
                    java.awt.Frame.NORMAL else java.awt.Frame.MAXIMIZED_BOTH
            }
            GhostIconButton(Icons.Default.Close, "Close", tint = ErrorColor, iconSize = 16.dp) {
                onCloseRequest()
            }
        }
    }
}

@Composable
private fun HeaderSearch(vm: SearchViewModel, modifier: Modifier) {
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    OutlinedTextField(
        value = vm.query,
        onValueChange = { vm.query = it },
        modifier = modifier.focusRequester(focusRequester).onKeyEvent { e ->
            if (e.key == Key.Enter && e.type == KeyEventType.KeyDown &&
                !e.isShiftPressed && !vm.isSearching
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
                    entries.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            pair.forEach { (store, status) ->
                                StoreStatusChip(store, status, Modifier.weight(1f))
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
private fun StoreStatusChip(store: String, status: StoreStatus, modifier: Modifier = Modifier) {
    val (icon, tint, bg) = when (status) {
        StoreStatus.PENDING  -> Triple(Icons.Default.HourglassEmpty, OnSurfaceVariant.copy(alpha = 0.4f), SurfaceContainerHighest)
        StoreStatus.CHECKING -> Triple(Icons.Default.Sync,           Primary,                             Primary.copy(alpha = 0.08f))
        StoreStatus.DONE     -> Triple(Icons.Default.CheckCircle,     Tertiary,                            Tertiary.copy(alpha = 0.08f))
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
        Text(
            store,
            fontSize = 11.sp,
            color = when (status) {
                StoreStatus.PENDING  -> OnSurfaceVariant.copy(alpha = 0.5f)
                StoreStatus.CHECKING -> OnSurface
                StoreStatus.DONE     -> OnSurfaceVariant
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ResultsPane(vm: SearchViewModel) {
    val searchedCards = vm.searchedCards

    if (searchedCards.isEmpty() && vm.results.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Enter a card name and press Search", color = OnSurfaceVariant, fontSize = 14.sp)
        }
        return
    }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    // Summary tracks the full searched list from the moment search() fires.
    // Results list only shows cards that have received at least one response.
    val summaryCards = searchedCards.ifEmpty { vm.results.map { it.card }.distinct() }
    val resultCards  = vm.results.map { it.card }.distinct()

    Column {
        if (summaryCards.size > 1) {
            Spacer(Modifier.height(8.dp))
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
private fun FilterField(value: String, onChange: (String) -> Unit) {
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
                Text("Filter results…", color = OnSurfaceVariant.copy(alpha = 0.5f), fontFamily = Mono, fontSize = 13.sp)
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

    val allListings = results.filter { it.title != null }
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
                        emptyMessage = if (isSearching) "Searching…" else "No listings found at any store")
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
                        ListingTable(inStock, images, dimmed = false, emptyMessage = null)
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
                            ListingTable(outOfStock, images, dimmed = true, emptyMessage = null)
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
private fun ListingTable(rows: List<SearchResult>, images: Map<String, String>, dimmed: Boolean, emptyMessage: String?) {
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
                ListingRow(r, r.title?.let { images[it] }, dimmed = dimmed)
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
            withContext(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(path)
                    if (f.exists()) {
                        javax.imageio.ImageIO.read(f)?.toComposeImageBitmap()?.let {
                            imageCache[path] = it
                            bitmap = it
                        }
                    }
                }
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
    val foundCount = cards.count { card ->
        results.any { it.card == card && it.title != null && it.available != false }
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
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
                    // Scrollable grid capped at ~240dp so 100-card lists don't push results off screen
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 240.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        cards.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                pair.forEach { card ->
                                    val cardResults = results.filter { it.card == card }
                                    val imagePath = cardResults
                                        .mapNotNull { r -> r.title?.let { images[it] } }
                                        .firstOrNull()
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

    val listings = results.filter { it.title != null }
    val hasInStock = listings.any { it.available != false }
    val hasOutOfStock = !hasInStock && listings.any { it.available == false }
    val (statusText, statusColor) = when {
        results.isEmpty() && isSearching -> "…"           to OnSurfaceVariant.copy(alpha = 0.35f)
        hasInStock                       -> "Found"        to Tertiary
        hasOutOfStock                    -> "Out of Stock" to ErrorColor
        else                             -> "Not Found"    to OnSurfaceVariant
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

private fun openUrl(url: String) {
    try {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(URI(url))
        }
    } catch (_: Exception) {}
}
