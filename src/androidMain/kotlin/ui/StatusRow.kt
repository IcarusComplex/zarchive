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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.Mono
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerHighest
import ui.theme.SurfaceContainerLow
import ui.theme.Tertiary
import ui.theme.ErrorColor

// Ported close to verbatim from ui/App.kt's StatusRow/StoreStatusChip (desktop) — pure Compose
// state UI with no AWT dependency, backed by the already-common SearchViewModel.storeStatuses.

@Composable
fun StatusRow(vm: SearchViewModel) {
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
