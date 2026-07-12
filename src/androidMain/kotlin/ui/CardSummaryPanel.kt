package ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SearchResult
import data.preferExactMatches
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.SurfaceContainerHighest
import ui.theme.SurfaceContainerLow
import ui.theme.Tertiary

// Ported from ui/App.kt's CardSummaryPanel/CardSummaryEntry (desktop) — the hover-preview
// "showCardOnHover" toggle doesn't apply to touch, so it's dropped; instead each entry gets a small
// tappable thumbnail chip (which desktop's row-only-on-hover design didn't need) wired to
// [onImageTap] for the same tap-to-toggle full preview used in the results table.
@Composable
fun CardSummaryPanel(
    cards: List<String>,
    results: List<SearchResult>,
    images: Map<String, String>,
    isSearching: Boolean,
    onCardClick: (String) -> Unit,
    onImageTap: (String) -> Unit,
    includePartialMatches: Boolean = false,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    filter: String,
    onFilterChange: (String) -> Unit,
) {
    val filterQ = filter.trim().lowercase()
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
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onExpandedChange(!expanded) }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text("Card Summary", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurface)
                Spacer(Modifier.width(10.dp))
                Text("$foundCount / ${cards.size} found", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Tertiary)
                if (pendingCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Text("$pendingCount pending", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f))
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
                    androidx.compose.material3.HorizontalDivider(color = OutlineVariant.copy(alpha = 0.6f))
                    Box(Modifier.padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                        FilterField(filter, placeholder = "Find a card in the list…") { onFilterChange(it) }
                    }
                    if (shownCards.isEmpty()) {
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            Text("No cards match \"$filter\"", color = OnSurfaceVariant, fontSize = 12.sp)
                        }
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 280.dp)
                            .verticalScroll(rememberScrollState())
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        shownCards.forEach { card ->
                            val cardResults = results.filter { it.card == card }
                            val imagePath = preferExactMatches(card, cardResults.filter { it.title != null }, exactOnly = !includePartialMatches)
                                .mapNotNull { r -> r.title?.let { images[it] } }
                                .firstOrNull() ?: images[card]
                            CardSummaryEntry(
                                card = card,
                                results = cardResults,
                                imagePath = imagePath,
                                isSearching = isSearching,
                                onClick = { onCardClick(card) },
                                onImageTap = onImageTap,
                                includePartialMatches = includePartialMatches,
                            )
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
    imagePath: String?,
    isSearching: Boolean,
    onClick: () -> Unit,
    onImageTap: (String) -> Unit,
    includePartialMatches: Boolean,
) {
    val listings = preferExactMatches(card, results.filter { it.title != null }, exactOnly = !includePartialMatches)
    val hasInStock = listings.any { it.available != false }
    val hasOutOfStock = !hasInStock && listings.any { it.available == false }
    val (statusText, statusColor) = when {
        hasInStock  -> "Found" to Tertiary
        isSearching -> "…" to OnSurfaceVariant.copy(alpha = 0.35f)
        hasOutOfStock -> "Out of Stock" to ui.theme.ErrorColor
        else -> "Not Found" to OnSurfaceVariant
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(SurfaceContainerHighest)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 5.dp),
    ) {
        CardThumbnail(
            path = imagePath,
            dimmed = false,
            onTap = imagePath?.let { { onImageTap(it) } },
            width = 24.dp,
            height = 34.dp,
        )
        Spacer(Modifier.width(8.dp))
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
}
