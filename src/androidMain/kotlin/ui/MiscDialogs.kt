package ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.BuildInfo
import network.CRASH_REPORT_URL
import network.UpdateInfo
import ui.theme.ErrorColor
import ui.theme.Mono
import ui.theme.OnPrimary
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerHighest
import ui.theme.SurfaceContainerLow
import ui.theme.SurfaceContainerLowest
import ui.theme.Tertiary

// Ported from ui/App.kt's UpdateStatusFooter/UpdateDialog/DownloadProgressDialog/
// CrashReportDialog/AddToSearchDialog/AllAlreadySearchedDialog/SearchSummaryDialog (desktop).
// Every modal dialog here uses ModalScrim (Phase 6) + a Surface sized to 92% of screen width
// instead of desktop's fixed 380-480dp widths, which don't translate to phone screens.

@Composable
fun UpdateStatusFooter(state: UpdateCheckState, error: String? = null) {
    val borderColor = when (state) {
        UpdateCheckState.UPDATE_FOUND -> Primary
        UpdateCheckState.UP_TO_DATE -> Tertiary
        UpdateCheckState.CHECK_FAILED -> ErrorColor
        else -> OutlineVariant
    }
    Column(Modifier.fillMaxWidth().background(SurfaceContainerLowest)) {
        HorizontalDivider(color = borderColor.copy(alpha = 0.5f))
        if (state == UpdateCheckState.CHECKING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Primary, trackColor = SurfaceContainerHighest)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                UpdateCheckState.CHECKING -> {
                    Icon(Icons.Default.Refresh, null, tint = Primary, modifier = Modifier.size(13.dp))
                    Text("Checking for updates…", fontSize = 11.sp, color = OnSurfaceVariant)
                }
                UpdateCheckState.UPDATE_FOUND -> {
                    Icon(Icons.Default.Info, null, tint = Primary, modifier = Modifier.size(13.dp))
                    Text("Update available — open Settings to download.", fontSize = 11.sp, color = Primary)
                }
                UpdateCheckState.UP_TO_DATE -> {
                    Icon(Icons.Default.Check, null, tint = Tertiary, modifier = Modifier.size(13.dp))
                    Text("Already up to date", fontSize = 11.sp, color = Tertiary)
                }
                UpdateCheckState.CHECK_FAILED -> {
                    Icon(Icons.Default.ErrorOutline, null, tint = ErrorColor, modifier = Modifier.size(13.dp))
                    Text(
                        "Couldn't check for updates" + (error?.let { " — $it" } ?: ""),
                        fontSize = 11.sp, color = ErrorColor,
                    )
                }
                else -> {}
            }
        }
    }
}

@Composable
fun SyncStatusFooter(state: SyncStatus, error: String?) {
    val borderColor = when (state) {
        SyncStatus.SYNCED -> Tertiary
        SyncStatus.ERROR -> ErrorColor
        else -> OutlineVariant
    }
    Column(Modifier.fillMaxWidth().background(SurfaceContainerLowest)) {
        HorizontalDivider(color = borderColor.copy(alpha = 0.5f))
        if (state == SyncStatus.SYNCING) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(2.dp), color = Primary, trackColor = SurfaceContainerHighest)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                SyncStatus.SYNCING -> {
                    Icon(Icons.Default.CloudSync, null, tint = Primary, modifier = Modifier.size(13.dp))
                    Text("Syncing with Google Drive…", fontSize = 11.sp, color = OnSurfaceVariant)
                }
                SyncStatus.SYNCED -> {
                    Icon(Icons.Default.CloudDone, null, tint = Tertiary, modifier = Modifier.size(13.dp))
                    Text("Synced with Google Drive", fontSize = 11.sp, color = Tertiary)
                }
                SyncStatus.ERROR -> {
                    Icon(Icons.Default.CloudOff, null, tint = ErrorColor, modifier = Modifier.size(13.dp))
                    Text("Sync failed" + (error?.let { " — $it" } ?: ""), fontSize = 11.sp, color = ErrorColor)
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun DialogSurface(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = SurfaceContainerLow,
        border = BorderStroke(1.dp, OutlineVariant),
        modifier = Modifier.fillMaxWidth(0.92f),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
fun UpdateDialog(info: UpdateInfo, canAutoInstall: Boolean, onOpenUrl: (String) -> Unit, onInstall: () -> Unit, onDismiss: () -> Unit) {
    ModalScrim(onDismiss = onDismiss) {
        DialogSurface {
            Text("Update available", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            Text("ZArchive ${info.tag} is available. You're on v${BuildInfo.VERSION}.", fontSize = 13.sp, color = OnSurface)
            if (canAutoInstall) {
                Text(
                    "ZArchive will download the update, then open Android's installer. Approve the install to finish.",
                    fontSize = 12.sp, color = OnSurfaceVariant,
                )
            } else {
                Text(
                    "Download the new version from GitHub Releases, extract, and replace your current ZArchive install.",
                    fontSize = 12.sp, color = OnSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(1f))
                OutlinedButton(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, OutlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                ) { Text("Later", fontSize = 12.sp) }
                if (canAutoInstall) {
                    Button(
                        onClick = onInstall,
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    ) { Text("Download & Install", fontSize = 12.sp) }
                } else {
                    Button(
                        onClick = { onOpenUrl(info.releaseUrl); onDismiss() },
                        shape = RoundedCornerShape(4.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                    ) { Text("Open release page", fontSize = 12.sp) }
                }
            }
        }
    }
}

@Composable
fun DownloadProgressDialog(phase: String, progress: Float, error: String?, onCancel: () -> Unit) {
    ModalScrim {
        DialogSurface {
            if (error != null) {
                Text("Update failed", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor)
                Text(error, fontSize = 12.sp, color = OnSurfaceVariant)
                Button(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text("Close", fontSize = 12.sp) }
            } else {
                Text(phase, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                if (progress.isNaN()) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Primary, trackColor = SurfaceContainerHighest,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                        color = Primary, trackColor = SurfaceContainerHighest,
                    )
                }
                Text(
                    if (progress.isNaN()) "…" else "${(progress * 100).toInt()}%",
                    fontSize = 12.sp, fontFamily = Mono, color = OnSurfaceVariant,
                )
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, OutlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                ) { Text("Cancel", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun CrashReportDialog(crashLog: String, onCopyToClipboard: (String) -> Unit, onOpenUrl: (String) -> Unit, onDismiss: () -> Unit) {
    var copied by remember { mutableStateOf(false) }
    ModalScrim(onDismiss = onDismiss) {
        DialogSurface {
            Text("ZArchive crashed last session", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor)
            Text(
                "Would you like to report this? Copy the crash log, then open the GitHub issue page and paste it in.",
                fontSize = 13.sp, color = OnSurface,
            )
            Box(
                Modifier.fillMaxWidth().heightIn(max = 140.dp)
                    .background(SurfaceContainerLowest, RoundedCornerShape(4.dp))
                    .padding(8.dp),
            ) {
                Text(
                    crashLog, fontSize = 10.sp, fontFamily = Mono, color = OnSurfaceVariant.copy(alpha = 0.7f),
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(4.dp),
                    border = BorderStroke(1.dp, OutlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
                ) { Text("Dismiss", fontSize = 12.sp) }
                Button(
                    onClick = {
                        onCopyToClipboard(crashLog)
                        copied = true
                        onOpenUrl(CRASH_REPORT_URL)
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text(if (copied) "Copied!" else "Copy & report", fontSize = 12.sp) }
            }
        }
    }
}

@Composable
fun AddToSearchDialog(
    newCount: Int,
    totalCount: Int,
    unavailableCount: Int,
    onAddNew: () -> Unit,
    onAddNewAndRefresh: () -> Unit,
    onSearchAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val existingCount = totalCount - newCount
    ModalScrim(onDismiss = onDismiss) {
        DialogSurface {
            Text("Add to existing results?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            Text(
                "$existingCount card${if (existingCount == 1) "" else "s"} already in results. " +
                    "Your query has $newCount new card${if (newCount == 1) "" else "s"}" +
                    if (unavailableCount > 0) ", and $unavailableCount of the existing ${if (unavailableCount == 1) "is" else "are"} still unavailable." else ".",
                fontSize = 13.sp, color = OnSurface,
            )
            if (unavailableCount > 0) {
                Button(
                    onClick = onAddNewAndRefresh,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text("Add $newCount new + refresh $unavailableCount unavailable", fontSize = 12.sp) }
            }
            Button(
                onClick = onAddNew,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = if (unavailableCount > 0) ButtonDefaults.buttonColors(containerColor = SurfaceContainerHighest, contentColor = OnSurface)
                else ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            ) { Text("Add $newCount new only", fontSize = 12.sp) }
            OutlinedButton(
                onClick = onSearchAll,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, OutlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
            ) { Text("Search all $totalCount", fontSize = 12.sp) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Cancel", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun FirstListSyncPromptDialog(onConnect: () -> Unit, onDismiss: () -> Unit) {
    ModalScrim(onDismiss = onDismiss) {
        DialogSurface {
            Text("Sync across devices?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            Text(
                "Connect Google Drive to keep your saved lists and results in sync between your " +
                    "phone and other devices. You can also do this later from Settings.",
                fontSize = 13.sp, color = OnSurface,
            )
            Button(
                onClick = onConnect,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
            ) { Text("Connect Google Drive", fontSize = 12.sp) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Not now", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun AllAlreadySearchedDialog(
    count: Int,
    unavailableCount: Int,
    onRefreshUnavailable: () -> Unit,
    onResearch: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalScrim(onDismiss = onDismiss) {
        DialogSurface {
            Text("Already in results", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            Text(
                "All $count card${if (count == 1) "" else "s"} in your query are already in the current results" +
                    if (unavailableCount > 0) ", and $unavailableCount of ${if (count == 1) "it" else "them"} ${if (unavailableCount == 1) "is" else "are"} still unavailable." else ".",
                fontSize = 13.sp, color = OnSurface,
            )
            if (unavailableCount > 0) {
                Button(
                    onClick = onRefreshUnavailable,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text("Refresh $unavailableCount unavailable", fontSize = 12.sp) }
            }
            OutlinedButton(
                onClick = onResearch,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                border = BorderStroke(1.dp, OutlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = OnSurfaceVariant),
            ) { Text("Re-search all $count", fontSize = 12.sp) }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Keep results", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
fun SearchSummaryDialog(vm: SearchViewModel) {
    data class CardBuckets(val inStock: Set<String>, val outOfStock: Set<String>, val notFound: List<String>)
    val buckets = remember(vm.results.size) {
        val byCard = vm.results.groupBy { it.card }
        val inStock = mutableSetOf<String>()
        val outOfStock = mutableSetOf<String>()
        for (card in vm.searchedCards) {
            val listings = byCard[card] ?: emptyList()
            val hasInStock = listings.any { it.title != null && it.available != false }
            val hasTitle = listings.any { it.title != null }
            when {
                hasInStock -> inStock.add(card)
                hasTitle -> outOfStock.add(card)
            }
        }
        CardBuckets(inStock, outOfStock, vm.searchedCards.filter { it !in inStock && it !in outOfStock })
    }
    val cfStores = vm.cfBlockedStores.toList()
    val totalCards = vm.searchedCards.size

    ModalScrim(onDismiss = { vm.dismissSearchSummary() }) {
        DialogSurface {
            Text("Search complete", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${buckets.inStock.size}/$totalCards", fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold,
                        color = if (buckets.inStock.size == totalCards) Tertiary else Primary,
                    )
                    Text("in stock", fontSize = 11.sp, color = OnSurfaceVariant)
                }
                if (buckets.outOfStock.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${buckets.outOfStock.size}", fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ErrorColor)
                        Text("out of stock", fontSize = 11.sp, color = OnSurfaceVariant)
                    }
                }
                if (cfStores.isNotEmpty()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${cfStores.size}", fontFamily = Mono, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = ErrorColor)
                        Text("rate limited", fontSize = 11.sp, color = OnSurfaceVariant)
                    }
                }
            }
            Column(
                Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (buckets.outOfStock.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Out of stock everywhere:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor.copy(alpha = 0.9f))
                        buckets.outOfStock.forEach { card -> Text("· $card", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)) }
                    }
                }
                if (buckets.notFound.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("No listings found:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant)
                        buckets.notFound.forEach { card -> Text("· $card", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)) }
                    }
                }
                if (cfStores.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Rate limited — results may be incomplete:", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ErrorColor.copy(alpha = 0.9f))
                        cfStores.forEach { store -> Text("· $store", fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f)) }
                        Text(
                            "Cloudflare blocked these stores mid-search. Try again with fewer cards, or search them individually.",
                            fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f),
                        )
                    }
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Button(
                    onClick = { vm.dismissSearchSummary() },
                    shape = RoundedCornerShape(4.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = OnPrimary),
                ) { Text("OK", fontSize = 12.sp) }
            }
        }
    }
}
