package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RemoveShoppingCart
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Token
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SearchResult
import data.preferExactMatches
import kotlinx.coroutines.launch
import ui.theme.ErrorColor
import ui.theme.HeaderBg
import ui.theme.Mono
import ui.theme.OnSecondaryContainer
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainer
import ui.theme.SurfaceContainerHighest
import ui.theme.SurfaceContainerLow
import ui.theme.SurfaceContainerLowest

// Ported from ui/App.kt's SearchResultsTab -> CardSection -> ListingTable/ListingRow chain
// (desktop). Desktop's wide 7-column table (thumb/name/store/status/price/pin/link, sized for a
// ~1100dp window) doesn't fit a phone -- ListingCard below stacks the same information into two
// rows instead of columns. Tap-to-toggle card art replaces the hover popup throughout.

private fun List<SearchResult>.sortedByPriceAsc(): List<SearchResult> =
    sortedWith(compareBy({ it.priceZar == null }, { it.priceZar ?: Double.MAX_VALUE }))

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}

@Composable
fun LuckshackLinks(cards: List<String>, onOpenUrl: (String) -> Unit) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = SurfaceContainerLow,
        border = BorderStroke(1.dp, OutlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.OpenInNew, null, tint = OnSecondaryContainer, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
                Text("Luckshack (not searched — open directly):", fontSize = 11.sp, color = OnSurfaceVariant, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
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
                            .clickable { onOpenUrl(data.luckshackSearchUrl(card)) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                    ) {
                        Text(card, fontSize = 11.sp, color = OnSecondaryContainer, softWrap = false)
                        Spacer(Modifier.width(4.dp))
                        Icon(Icons.Default.OpenInNew, "Open $card on Luckshack", tint = OnSurfaceVariant, modifier = Modifier.size(11.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun FilterField(value: String, placeholder: String = "Filter results…", onChange: (String) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .height(38.dp)
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
                textStyle = TextStyle(color = OnSurface, fontFamily = Mono, fontSize = 13.sp),
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
fun CountBadge(text: String, muted: Boolean = false) {
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
private fun ListingCard(
    result: SearchResult,
    imagePath: String?,
    dimmed: Boolean,
    isPinned: Boolean,
    onTogglePin: (() -> Unit)?,
    onOpenUrl: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    // In-stock/out-of-stock is already conveyed by which ListingGroup ("In Stock" vs "Out of
    // Stock" header) a row sits in, so no per-row status chip is repeated here. The footer is
    // split into two full-half tap targets (mobile: bigger targets beat small icons) rather than
    // the whole row opening the listing and a pair of small trailing icons.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isPinned && !dimmed) Primary.copy(alpha = 0.07f) else androidx.compose.ui.graphics.Color.Transparent),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardThumbnail(imagePath, dimmed, onTap = imagePath?.let { { onImageTap(it) } })
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    result.title ?: "", fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                    color = if (dimmed) OnSurfaceVariant else OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val subtitle = result.note.takeIf { it.isNotBlank() && it !in setOf("In stock", "Out of stock", "not stocked") }
                if (subtitle != null) {
                    Text(subtitle, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    result.store, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                    color = if (dimmed) OnSurfaceVariant else OnSecondaryContainer, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = result.priceZar?.let { formatZar(it) } ?: "N/A",
                fontFamily = Mono,
                fontSize = if (result.priceZar != null) 15.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    result.priceZar == null -> OnSurfaceVariant.copy(alpha = 0.6f)
                    dimmed -> OnSurfaceVariant
                    else -> Primary
                },
            )
        }
        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            if (onTogglePin != null) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTogglePin() }
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isPinned) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        null,
                        tint = if (isPinned) Primary else OnSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Use this version",
                        fontSize = 12.sp,
                        fontWeight = if (isPinned) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isPinned) Primary else OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                androidx.compose.material3.VerticalDivider(color = OutlineVariant.copy(alpha = 0.3f))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onOpenUrl(result.url) }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.OpenInNew, null, tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in store", fontSize = 12.sp, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun ListingGroup(
    rows: List<SearchResult>,
    images: Map<String, String>,
    dimmed: Boolean,
    emptyMessage: String?,
    card: String,
    pinnedUrl: String?,
    onTogglePin: ((String) -> Unit)?,
    onOpenUrl: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (dimmed) SurfaceContainerLowest else SurfaceContainerLow,
        border = BorderStroke(1.dp, if (dimmed) OutlineVariant.copy(alpha = 0.5f) else OutlineVariant),
        modifier = Modifier.fillMaxWidth().then(if (dimmed) Modifier.alpha(0.7f) else Modifier),
    ) {
        Column {
            if (emptyMessage != null) {
                Text(emptyMessage, color = OnSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
            }
            rows.forEachIndexed { idx, r ->
                if (idx > 0) HorizontalDivider(color = OutlineVariant.copy(alpha = 0.3f))
                ListingCard(
                    result = r,
                    imagePath = (r.title?.let { images[it] }) ?: images[card],
                    dimmed = dimmed,
                    isPinned = pinnedUrl == r.url,
                    onTogglePin = onTogglePin?.let { fn -> { fn(r.url) } },
                    onOpenUrl = onOpenUrl,
                    onImageTap = onImageTap,
                )
            }
        }
    }
}

@Composable
fun CardSection(
    card: String,
    results: List<SearchResult>,
    images: Map<String, String>,
    isSearching: Boolean,
    isRefreshing: Boolean,
    pinnedUrl: String?,
    onTogglePin: (String) -> Unit,
    includePartialMatches: Boolean,
    excludedFromOrder: Boolean,
    onToggleExcludeFromOrder: () -> Unit,
    onRefresh: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    var cardFilter by remember { mutableStateOf("") }
    val cardFilterQ = cardFilter.trim().lowercase()

    val allListings = preferExactMatches(card, results.filter { it.title != null }, exactOnly = !includePartialMatches)
    val listings = if (cardFilterQ.isEmpty()) allListings else allListings.filter { r ->
        r.title!!.lowercase().contains(cardFilterQ) || r.store.lowercase().contains(cardFilterQ)
    }
    val inStock = listings.filter { it.available != false }.sortedByPriceAsc()
    val outOfStock = listings.filter { it.available == false }.sortedByPriceAsc()
    val notStocked = results.filter { it.title == null && it.note == "not stocked" }.map { it.store }.sorted()
    val errors = results.filter { it.title == null && it.note != "not stocked" }

    var inStockExpanded by remember { mutableStateOf(true) }
    var outStockExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerLowest,
        border = BorderStroke(1.dp, OutlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
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
                    "\"$card\"",
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                CountBadge("${allListings.size}")
                if (isSearching || isRefreshing) {
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), color = Primary, strokeWidth = 1.5.dp)
                }
                Spacer(Modifier.weight(1f))
                Icon(
                    Icons.Default.Refresh, "Refresh search",
                    tint = if (isRefreshing) Primary.copy(alpha = 0.4f) else OnSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp).clickable(enabled = !isRefreshing, onClick = onRefresh),
                )
                Spacer(Modifier.width(10.dp))
                Icon(
                    if (excludedFromOrder) Icons.Default.RemoveShoppingCart else Icons.Default.ShoppingCart,
                    if (excludedFromOrder) "Excluded from order" else "Exclude from order",
                    tint = if (excludedFromOrder) ErrorColor else OnSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp).clickable(onClick = onToggleExcludeFromOrder),
                )
                Spacer(Modifier.width(6.dp))
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
                        ListingGroup(
                            emptyList(), images, dimmed = false,
                            emptyMessage = if (isSearching) "Searching…" else "No listings found at any store",
                            card = card, pinnedUrl = pinnedUrl, onTogglePin = onTogglePin,
                            onOpenUrl = onOpenUrl, onImageTap = onImageTap,
                        )
                    } else if (listings.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text("No listings match \"$cardFilter\"", color = OnSurfaceVariant, fontSize = 13.sp)
                        }
                    } else {
                        if (inStock.isNotEmpty()) {
                            ListingGroup(
                                inStock, images, dimmed = false, emptyMessage = null,
                                card = card, pinnedUrl = pinnedUrl, onTogglePin = onTogglePin,
                                onOpenUrl = onOpenUrl, onImageTap = onImageTap,
                            )
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
                                ListingGroup(
                                    outOfStock, images, dimmed = true, emptyMessage = null,
                                    card = card, pinnedUrl = pinnedUrl, onTogglePin = onTogglePin,
                                    onOpenUrl = onOpenUrl, onImageTap = onImageTap,
                                )
                            }
                        }
                    }

                    if (errors.isNotEmpty() || notStocked.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        errors.forEach { err ->
                            Text("⚠ ${err.store}: ${err.note}", fontSize = 11.sp, color = ErrorColor, modifier = Modifier.padding(vertical = 1.dp))
                        }
                        if (notStocked.isNotEmpty()) {
                            Text("Not stocked: ${notStocked.joinToString(", ")}", fontSize = 11.sp,
                                color = OnSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.padding(top = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ResultsScreen(
    vm: SearchViewModel,
    scrollState: androidx.compose.foundation.ScrollState,
    scrollRootY: Int,
    summaryExpanded: Boolean,
    onSummaryExpandedChange: (Boolean) -> Unit,
    summaryFilter: String,
    onSummaryFilterChange: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    val summaryCards = vm.searchedCards.ifEmpty { vm.results.map { it.card }.distinct() }
    val hasResults = vm.results.map { it.card }.toSet()
    val resultCards = summaryCards.filter { it in hasResults || vm.refreshingCards.containsKey(it) }
    val scope = rememberCoroutineScope()
    val sectionPositions = remember { mutableMapOf<String, Int>() }

    Column {
        if (summaryCards.size > 1) {
            CardSummaryPanel(
                cards = summaryCards,
                results = vm.results.toList(),
                images = vm.images,
                isSearching = vm.isSearching,
                onCardClick = { card ->
                    sectionPositions[card]?.let { y ->
                        val target = (scrollState.value + (y - scrollRootY)).coerceAtLeast(0)
                        scope.launch { scrollState.animateScrollTo(target) }
                    }
                },
                onImageTap = onImageTap,
                includePartialMatches = vm.includePartialMatches,
                expanded = summaryExpanded,
                onExpandedChange = onSummaryExpandedChange,
                filter = summaryFilter,
                onFilterChange = onSummaryFilterChange,
            )
            Spacer(Modifier.height(10.dp))
        }
        resultCards.forEach { card ->
            Box(
                Modifier.onGloballyPositioned { coords ->
                    sectionPositions[card] = coords.positionInRoot().y.toInt()
                },
            ) {
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
                    onOpenUrl = onOpenUrl,
                    onImageTap = onImageTap,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
