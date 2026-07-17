package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Storefront
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import data.KNOWN_PLATFORMS
import data.OrderLine
import data.Platform
import data.SearchResult
import data.StoreOrder
import data.cheapestPlan
import data.fewestStoresPlan
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import ui.theme.Tertiary

// Ported from ui/App.kt's OrderListsPane/PlanStat/StoreOrderCard/OrderLineRow/UncoveredCard
// (desktop), built on the already-common data/OrderOptimizer.kt (Phase 2). StoreOrderCard's
// showCart/cartOnClick automation (Shopify/WooCommerce/BigCommerce/PrestaShop) mirrors desktop's
// logic exactly (same data.Platform/data.KNOWN_PLATFORMS lookups), gated behind the same
// confirm-before-acting dialog added on both platforms -- see ConfirmDialog usage below.

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}

@Composable
fun OrderListsScreen(vm: SearchViewModel, onOpenUrl: (String) -> Unit, onImageTap: (String) -> Unit, onCardTap: (SearchResult) -> Unit) {
    val strategy = vm.orderStrategy
    val unchecked = vm.uncheckedOrderLines
    val excludedCards = vm.excludedCards
    val priceMax = vm.orderPriceFilter.toDoubleOrNull()
    val ownedCards by vm.ownedCardNames.collectAsState()

    // cardsForPlan() reads vm.excludeOwnedFromOrders/ownedCards/vm.searchedCards as Compose state
    // directly inside the derivedStateOf block below, same as vm.results, so toggling "exclude
    // owned" or a fresh collection import both trigger a recompute.
    fun cardsForPlan() = if (vm.excludeOwnedFromOrders) vm.searchedCards.filterNot { ownedCards.ownsCard(it) } else vm.searchedCards
    val cheapest by remember { derivedStateOf { cheapestPlan(cardsForPlan(), vm.results.toList(), vm.pinnedListings, vm.includePartialMatches) } }
    val fewest by remember { derivedStateOf { fewestStoresPlan(cardsForPlan(), vm.results.toList(), vm.pinnedListings, vm.includePartialMatches) } }
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

        // Exclude-owned filter -- deliberately rendered here, *before* the anyInStock early-return
        // below. Excluding owned cards can itself empty the plan (e.g. every searched card is
        // already owned), and if this toggle were only reachable further down (past that return),
        // there'd be no way to switch it back off once it hid everything.
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clickable { vm.excludeOwnedFromOrders = !vm.excludeOwnedFromOrders }
                .padding(vertical = 4.dp),
        ) {
            Text(
                "Exclude owned",
                fontSize = 11.sp,
                color = if (vm.excludeOwnedFromOrders) Primary else OnSurfaceVariant.copy(alpha = 0.6f),
            )
            Spacer(Modifier.width(6.dp))
            Icon(
                if (vm.excludeOwnedFromOrders) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                contentDescription = null,
                tint = if (vm.excludeOwnedFromOrders) Primary else OnSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
            )
        }
        Spacer(Modifier.height(10.dp))

        if (!anyInStock) {
            Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                Text(
                    when {
                        vm.isSearching -> "No in-stock cards yet — building the list as results arrive…"
                        vm.excludeOwnedFromOrders -> "No in-stock listings to build an order from (everything may be excluded as owned)."
                        else -> "No in-stock listings to build an order from."
                    },
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
            StoreOrderCard(so, vm.images, unchecked, excludedCards, priceMax, onOpenUrl, onImageTap, onCardTap, ownedCards)
            Spacer(Modifier.height(12.dp))
        }
        if (!vm.isSearching && plan.uncoveredCards.isNotEmpty()) {
            UncoveredCard(plan.uncoveredCards, ownedCards)
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
    onCardTap: (SearchResult) -> Unit,
    ownedCards: Set<String> = emptySet(),
) {
    val activeLines = order.lines
        .filter { !unchecked.containsKey(it.listing.url) && !excludedCards.containsKey(it.card) && (priceMax == null || (it.listing.priceZar ?: 0.0) <= priceMax) }
        .distinctBy { it.listing.variantId ?: it.listing.url }
    val displayCount = activeLines.size
    val displayTotal = activeLines.sumOf { it.listing.priceZar ?: 0.0 }
    val isWarren = order.store == "The Warren"

    val allHaveIds = activeLines.isNotEmpty() && activeLines.all { it.listing.variantId != null }
    val platform = KNOWN_PLATFORMS[order.storeUrl]
    // WC_STORE_API (The Hidden Realm) is also WooCommerce under the hood -- variation IDs are
    // resolved at search time from the product page, so the same ?add-to-cart= URL works.
    // BigCommerce uses GET /cart.php?action=add&product_id=X -- same browser-session pattern.
    // PrestaShop uses GET /cart?add=1&id_product=X&qty=1&token=STATIC_TOKEN&action=update.
    val isWooCart = platform == Platform.WOOCOMMERCE || platform == Platform.WC_STORE_API
    val isBigCommerceCart = platform == Platform.BIGCOMMERCE
    val isPrestaCart = platform == Platform.PRESTASHOP &&
        activeLines.isNotEmpty() && activeLines.first().listing.cartToken != null
    val showCart = allHaveIds && (platform == Platform.SHOPIFY || isWooCart || isBigCommerceCart || isPrestaCart) && !isWarren

    var wooCartLoading by remember { mutableStateOf(false) }
    var pendingCartConfirm by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Shown before cartOnClick actually runs (ConfirmDialog below) -- explains what's about to
    // happen since this drives real browser navigation the user didn't directly tap through to.
    // "opens your browser N times" rather than "N tabs": a mobile browser may reuse one tab across
    // sequential opens instead of stacking distinct tabs like desktop does -- functionally the same
    // (each URL still gets visited in order), just not literally "tabs" here.
    val cartConfirmMessage = when {
        isWooCart -> "WooCommerce doesn't support adding several cards through one link, so ZArchive will open " +
            "your browser ${activeLines.size} times in a row -- one per card -- then open your cart automatically. " +
            "This can take a minute or two; feel free to ignore your phone until the cart opens, just don't close " +
            "the browser while it's running.\n\nOnce your cart opens, please double-check all ${activeLines.size} card(s) made it in -- items can occasionally be missed."
        isBigCommerceCart -> "BigCommerce doesn't support adding several cards through one link, so ZArchive will open " +
            "your browser ${activeLines.size} times in a row -- one per card -- then open your cart automatically. " +
            "Feel free to ignore your phone until the cart opens, just don't close the browser while it's running.\n\n" +
            "Once your cart opens, please double-check all ${activeLines.size} card(s) made it in -- items can occasionally be missed."
        isPrestaCart -> "PrestaShop doesn't support adding several cards through one link, so ZArchive will open " +
            "your browser ${activeLines.size} times in a row -- one per card -- then open your cart automatically. " +
            "Feel free to ignore your phone until the cart opens, just don't close the browser while it's running.\n\n" +
            "Once your cart opens, please double-check all ${activeLines.size} card(s) made it in -- items can occasionally be missed."
        else -> "This opens your cart in the browser with all ${activeLines.size} card(s) already added.\n\n" +
            "Please double-check they all made it in once it opens -- items can occasionally be missed."
    }

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
                Text(
                    order.store, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSecondaryContainer,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                CountBadge("$displayCount")
                Spacer(Modifier.weight(1f))
                Text(formatZar(displayTotal), fontFamily = Mono, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Primary)
                Spacer(Modifier.width(10.dp))
                if (showCart) {
                    val base = order.storeUrl.trimEnd('/')
                    if (wooCartLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Primary, strokeWidth = 2.dp)
                    } else {
                        val wooCartUrl = if (platform == Platform.WC_STORE_API) "$base/basket/" else "$base/cart/"
                        val cartOnClick: () -> Unit = when {
                            isWooCart -> {
                                {
                                    wooCartLoading = true
                                    scope.launch {
                                        activeLines.forEachIndexed { idx, line ->
                                            if (idx > 0) delay(6000)
                                            onOpenUrl("$base/?add-to-cart=${line.listing.variantId}&quantity=1")
                                        }
                                        delay(6000)
                                        onOpenUrl(wooCartUrl)
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
                                            onOpenUrl("$base/cart.php?action=add&product_id=${line.listing.variantId}")
                                        }
                                        delay(3000)
                                        onOpenUrl("$base/cart.php")
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
                                            onOpenUrl("$base/cart?add=1&id_product=${line.listing.variantId}&qty=1&token=$token&action=update")
                                        }
                                        delay(3000)
                                        onOpenUrl("$base/cart")
                                        wooCartLoading = false
                                    }
                                }
                            }
                            else -> {
                                {
                                    val url = "$base/cart/" + activeLines.joinToString(",") { "${it.listing.variantId}:1" }
                                    onOpenUrl(url)
                                }
                            }
                        }
                        Icon(
                            Icons.Default.ShoppingCart,
                            contentDescription = "Add all to cart",
                            tint = Primary,
                            modifier = Modifier.size(16.dp).clickable { pendingCartConfirm = true },
                        )
                        if (pendingCartConfirm) {
                            ConfirmDialog(
                                title = "Add ${activeLines.size} card${if (activeLines.size == 1) "" else "s"} to cart?",
                                message = cartConfirmMessage,
                                confirmLabel = "Continue",
                                onConfirm = { pendingCartConfirm = false; cartOnClick() },
                                onDismiss = { pendingCartConfirm = false },
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                }
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
                    onCardTap = onCardTap,
                    owned = ownedCards.ownsCard(line.card),
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
    onCardTap: (SearchResult) -> Unit,
    owned: Boolean = false,
) {
    val contentAlpha = if (checked) 1f else 0.4f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCardTap(line.listing) }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(line.card, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (owned) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Default.CheckCircle, "In your collection", tint = Tertiary, modifier = Modifier.size(13.dp))
                }
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UncoveredCard(cards: List<String>, ownedCards: Set<String> = emptySet()) {
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
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                cards.forEachIndexed { i, card ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (i < cards.lastIndex) "$card," else card,
                            fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.8f),
                        )
                        if (ownedCards.ownsCard(card)) {
                            Spacer(Modifier.width(3.dp))
                            Icon(Icons.Default.CheckCircle, "In your collection", tint = Tertiary, modifier = Modifier.size(12.dp))
                        }
                    }
                }
            }
        }
    }
}
