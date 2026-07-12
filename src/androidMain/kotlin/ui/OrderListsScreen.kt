package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.OrderLine
import data.StoreOrder
import data.cheapestPlan
import data.fewestStoresPlan
import ui.theme.ErrorColor
import ui.theme.HeaderBg
import ui.theme.Mono
import ui.theme.OnSecondaryContainer
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.Secondary
import ui.theme.SurfaceContainerLow
import ui.theme.SurfaceContainerLowest

// Ported from ui/App.kt's OrderListsPane/PlanStat/StoreOrderCard/OrderLineRow/UncoveredCard
// (desktop), built on the already-common data/OrderOptimizer.kt (Phase 2). Desktop's sequential
// "add all to cart" browser automation (Shopify/WooCommerce/BigCommerce/PrestaShop-specific,
// showCart/cartOnClick in StoreOrderCard) is explicitly NOT ported per the user's decision --
// Android core parity only offers opening a single listing or a single store at a time.

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}

@Composable
fun OrderListsScreen(vm: SearchViewModel, onOpenUrl: (String) -> Unit, onImageTap: (String) -> Unit) {
    val strategy = vm.orderStrategy
    val unchecked = vm.uncheckedOrderLines
    val excludedCards = vm.excludedCards
    val priceMax = vm.orderPriceFilter.toDoubleOrNull()

    val cheapest by remember { derivedStateOf { cheapestPlan(vm.searchedCards, vm.results.toList(), vm.pinnedListings, vm.includePartialMatches) } }
    val fewest by remember { derivedStateOf { fewestStoresPlan(vm.searchedCards, vm.results.toList(), vm.pinnedListings, vm.includePartialMatches) } }
    val plan = if (strategy == OrderStrategy.CHEAPEST) cheapest else fewest
    val anyInStock = cheapest.storeOrders.isNotEmpty()

    val isLineActive = { line: OrderLine ->
        !unchecked.containsKey(line.listing.url) &&
            !excludedCards.containsKey(line.card) &&
            (priceMax == null || (line.listing.priceZar ?: 0.0) <= priceMax)
    }
    val activeStores = plan.storeOrders.count { so -> so.lines.any { isLineActive(it) } }
    val activeItems = plan.storeOrders.sumOf { so -> so.lines.count { isLineActive(it) } }
    val activeTotal = plan.storeOrders.sumOf { so -> so.lines.filter { isLineActive(it) }.sumOf { it.listing.priceZar ?: 0.0 } }

    Column(Modifier.fillMaxSize()) {
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
                    Text(s.label, fontSize = 12.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium, color = if (active) Primary else OnSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(strategy.blurb, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
        Spacer(Modifier.height(10.dp))

        if (!anyInStock) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (vm.isSearching) "No in-stock cards yet — building the list as results arrive…"
                    else "No in-stock listings to build an order from.",
                    color = OnSurfaceVariant, fontSize = 13.sp,
                )
            }
            return
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            PlanStat("$activeStores", if (activeStores == 1) "store" else "stores")
            Spacer(Modifier.width(16.dp))
            PlanStat("$activeItems", if (activeItems == 1) "card" else "cards")
            Spacer(Modifier.width(16.dp))
            PlanStat(formatZar(activeTotal), "total", valueColor = Primary)
            if (vm.isSearching) {
                Spacer(Modifier.weight(1f))
                CircularProgressIndicator(Modifier.size(12.dp), color = Primary, strokeWidth = 1.5.dp)
            }
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(4.dp))
                .background(SurfaceContainerLow)
                .border(1.dp, if (priceMax != null) Primary.copy(alpha = 0.5f) else OutlineVariant, RoundedCornerShape(4.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Default.FilterList, null, tint = if (priceMax != null) Primary else OnSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(8.dp))
            Text("Max R", fontSize = 12.sp, color = OnSurfaceVariant, fontFamily = Mono)
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (vm.orderPriceFilter.isEmpty()) {
                    Text("any", color = OnSurfaceVariant.copy(alpha = 0.4f), fontFamily = Mono, fontSize = 12.sp)
                }
                BasicTextField(
                    value = vm.orderPriceFilter,
                    onValueChange = { vm.orderPriceFilter = it.filter { c -> c.isDigit() || c == '.' } },
                    singleLine = true,
                    textStyle = TextStyle(color = if (priceMax != null) Primary else OnSurface, fontFamily = Mono, fontSize = 12.sp),
                    cursorBrush = SolidColor(Primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (vm.orderPriceFilter.isNotEmpty()) {
                Icon(Icons.Default.Close, "Clear price filter", tint = OnSurfaceVariant,
                    modifier = Modifier.size(14.dp).clickable { vm.orderPriceFilter = "" })
            }
        }
        Spacer(Modifier.height(12.dp))

        plan.storeOrders.forEach { so ->
            StoreOrderCard(so, vm.images, unchecked, excludedCards, priceMax, onOpenUrl, onImageTap)
            Spacer(Modifier.height(12.dp))
        }
        if (!vm.isSearching && plan.uncoveredCards.isNotEmpty()) {
            UncoveredCard(plan.uncoveredCards)
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
private fun StoreOrderCard(
    order: StoreOrder,
    images: Map<String, String>,
    unchecked: MutableMap<String, Unit>,
    excludedCards: MutableMap<String, Unit>,
    priceMax: Double?,
    onOpenUrl: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    val activeLines = order.lines
        .filter { !unchecked.containsKey(it.listing.url) && !excludedCards.containsKey(it.card) && (priceMax == null || (it.listing.priceZar ?: 0.0) <= priceMax) }
        .distinctBy { it.listing.variantId ?: it.listing.url }
    val displayCount = activeLines.size
    val displayTotal = activeLines.sumOf { it.listing.priceZar ?: 0.0 }
    val isWarren = order.store == "The Warren"

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
                    .clickable { onOpenUrl(order.storeUrl) }
                    .padding(horizontal = 12.dp, vertical = 9.dp),
            ) {
                Icon(Icons.Default.Storefront, null, tint = Secondary, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text(order.store, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSecondaryContainer, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                CountBadge("$displayCount")
                Spacer(Modifier.weight(1f))
                Text(formatZar(displayTotal), fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primary)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Default.OpenInNew, "Open store", tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
            }
            HorizontalDivider(color = OutlineVariant)
            if (isWarren) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        "Cart must be filled manually on The Warren — Cloudflare protection prevents automation.",
                        fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f),
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
                    onOpenUrl = onOpenUrl,
                    onImageTap = onImageTap,
                )
            }
        }
    }
}

@Composable
private fun OrderLineRow(
    line: OrderLine,
    imagePath: String?,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
    onOpenUrl: (String) -> Unit,
    onImageTap: (String) -> Unit,
) {
    val contentAlpha = if (checked) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenUrl(line.listing.url) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onToggle,
            modifier = Modifier.size(28.dp),
            colors = CheckboxDefaults.colors(checkedColor = Primary, uncheckedColor = OutlineVariant, checkmarkColor = Color(0xFF3C2F00)),
        )
        Spacer(Modifier.width(6.dp))
        Box(Modifier.alpha(contentAlpha)) {
            CardThumbnail(imagePath, dimmed = false, onTap = imagePath?.let { { onImageTap(it) } }, width = 40.dp, height = 56.dp)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f).alpha(contentAlpha)) {
            Text(line.card, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            line.listing.title?.takeIf { it != line.card }?.let {
                Text(it, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            line.listing.priceZar?.let { formatZar(it) } ?: "N/A",
            fontFamily = Mono,
            fontSize = if (line.listing.priceZar != null) 15.sp else 12.sp,
            fontWeight = FontWeight.Bold,
            color = if (line.listing.priceZar == null) OnSurfaceVariant.copy(alpha = 0.6f) else Primary,
            modifier = Modifier.alpha(contentAlpha),
        )
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
                Text("Not available anywhere (${cards.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor)
            }
            Spacer(Modifier.height(6.dp))
            Text(cards.joinToString(", "), fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.8f))
        }
    }
}
