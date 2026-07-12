package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import ui.theme.HeaderBg
import ui.theme.Mono
import ui.theme.OnPrimary
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerLow

// Phone-sized port of desktop's MonitorFoundDialog/MonitorFoundRow (App.kt) -- hosted inside the
// same ModalScrim convention every other Android modal uses. "Close" clears all of them at once
// (vm.dismissAllMonitorAlerts()), same as desktop; tapping a row opens the listing directly.
@Composable
fun MonitorAlertsModal(
    hits: List<SearchResult>,
    images: Map<String, String>,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth(0.92f)
            .fillMaxHeight(0.8f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceContainerLow)
            .border(1.dp, OutlineVariant, RoundedCornerShape(8.dp)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().background(HeaderBg).padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Icon(Icons.Default.Notifications, null, tint = Primary, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "${hits.size} card(s) found",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = OutlineVariant)
        LazyColumn(
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(hits, key = { "${it.store}|${it.title}|${it.priceZar}" }) { hit ->
                MonitorFoundRow(hit, images, onOpenUrl)
            }
        }
        HorizontalDivider(color = OutlineVariant)
        Button(
            onClick = onDismiss,
            colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        ) {
            Text("Close")
        }
    }
}

@Composable
private fun MonitorFoundRow(hit: SearchResult, images: Map<String, String>, onOpenUrl: (String) -> Unit) {
    val imagePath = (hit.title?.let { images[it] }) ?: images[hit.card]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .clickable { onOpenUrl(hit.url) }
            .padding(8.dp),
    ) {
        CardThumbnail(imagePath, dimmed = false)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hit.title ?: hit.card, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Text(hit.store, fontSize = 11.sp, color = OnSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            hit.priceZar?.let { formatZar(it) } ?: "N/A",
            fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Primary,
        )
    }
}

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}
