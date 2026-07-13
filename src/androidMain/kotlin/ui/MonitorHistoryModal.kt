package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.History
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import monitor.MonitorCheckEntry
import ui.theme.ErrorColor
import ui.theme.HeaderBg
import ui.theme.Mono
import ui.theme.OnPrimary
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerLow
import ui.theme.Tertiary

// Trail of the last ~10 monitor checks (monitor/MonitorHistory.kt) -- lets a user who can't tell
// whether background checks are actually happening see exactly when each one ran, whether it
// succeeded, and what it found, rather than only ever seeing a single "last checked" line.
@Composable
fun MonitorHistoryModal(history: List<MonitorCheckEntry>, onDismiss: () -> Unit) {
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
            Icon(Icons.Default.History, null, tint = Primary, modifier = Modifier.width(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "Recent checks",
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider(color = OutlineVariant)
        if (history.isEmpty()) {
            Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center) {
                Text(
                    "No checks recorded yet -- turn the monitor on to start one.",
                    fontSize = 13.sp, color = OnSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(history, key = { it.timestamp }) { entry -> MonitorHistoryRow(entry) }
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
private fun MonitorHistoryRow(entry: MonitorCheckEntry) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                java.text.SimpleDateFormat("EEE HH:mm:ss").format(java.util.Date(entry.timestamp)),
                fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface,
            )
            Text(entry.status, fontSize = 12.sp, color = OnSurfaceVariant)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (!entry.success) "FAILED" else if (entry.hitCount > 0) "+${entry.hitCount}" else "-",
            fontFamily = Mono, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (!entry.success) ErrorColor else if (entry.hitCount > 0) Tertiary else OnSurfaceVariant,
        )
    }
}
