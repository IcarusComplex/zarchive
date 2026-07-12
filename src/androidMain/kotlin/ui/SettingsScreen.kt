package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import data.BuildInfo
import network.BUG_REPORT_URL
import ui.theme.ErrorColor
import ui.theme.HeaderBg
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerHigh
import ui.theme.SurfaceContainerLowest

private const val SUPPORT_URL = "https://ko-fi.com/icaruscomplexza"

// Ported from ui/App.kt's SettingsMenu/SettingsCheckItem/SettingsLinkItem/SettingsActionItem
// (desktop) -- desktop's custom Popup becomes a standard Material3 DropdownMenu, the native
// Android equivalent. Links open via PlatformActions (Intent.ACTION_VIEW) instead of
// Desktop.getDesktop().browse.
@Composable
fun SettingsMenu(vm: SearchViewModel, onOpenUrl: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Default.Settings, "Settings", tint = OnSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = {
                    SettingsItemContent(
                        label = "Early Access",
                        sublabel = "Opt in to beta / pre-release builds",
                        trailing = {
                            Icon(
                                if (vm.earlyAccess) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                null, tint = if (vm.earlyAccess) Primary else OnSurfaceVariant.copy(alpha = 0.5f),
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                },
                onClick = { vm.earlyAccess = !vm.earlyAccess },
            )
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))
            DropdownMenuItem(
                text = {
                    SettingsItemContent(
                        label = if (vm.updateCheckState == UpdateCheckState.CHECKING) "Checking…" else "Check for updates",
                        sublabel = "Current version: v${BuildInfo.VERSION}",
                        icon = Icons.Default.Refresh,
                    )
                },
                onClick = {
                    if (vm.updateCheckState != UpdateCheckState.CHECKING) { vm.checkForUpdates(); expanded = false }
                },
            )
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))
            if (vm.syncStatus == SyncStatus.DISCONNECTED) {
                DropdownMenuItem(
                    text = {
                        SettingsItemContent(
                            label = "Connect Google Drive",
                            sublabel = "Sync saved lists & results across devices",
                            icon = Icons.Default.CloudSync,
                        )
                    },
                    onClick = { vm.connectGoogleDrive {}; expanded = false },
                )
            } else {
                DropdownMenuItem(
                    text = {
                        SettingsItemContent(
                            label = "Sync now",
                            sublabel = vm.syncAccountEmail?.let { "Connected as $it" } ?: "Connected to Google Drive",
                            icon = Icons.Default.CloudSync,
                        )
                    },
                    onClick = { vm.syncNow(); expanded = false },
                )
                HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))
                DropdownMenuItem(
                    text = { SettingsItemContent(label = "Disconnect Google Drive", icon = Icons.Default.CloudOff) },
                    onClick = { vm.disconnectGoogleDrive(); expanded = false },
                )
            }
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))
            DropdownMenuItem(
                text = { SettingsItemContent(label = "Report a bug", sublabel = "Open a GitHub issue", icon = Icons.Default.BugReport) },
                onClick = { onOpenUrl(BUG_REPORT_URL); expanded = false },
            )
            HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))
            DropdownMenuItem(
                text = { SettingsItemContent(label = "Support ZArchive", sublabel = "Support on Ko-fi", icon = Icons.Default.Favorite) },
                onClick = { onOpenUrl(SUPPORT_URL); expanded = false },
            )
        }
    }
}

@Composable
private fun SettingsItemContent(
    label: String,
    sublabel: String? = null,
    icon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        if (icon != null) {
            Icon(icon, null, tint = OnSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(label, fontSize = 13.sp, color = OnSurface)
            if (sublabel != null) Text(sublabel, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        }
    }
}

// Ported from ui/App.kt's SearchOptionsDialog/OptionsSectionHeader/OptionToggle (desktop) -- a
// full-screen Dialog here rather than a fixed 560dp-wide AlertDialog, matching the same
// full-screen-Dialog pattern SavedListsScreen.kt/SavedResultsScreen.kt already established for
// content-heavy screens on a phone.
@Composable
fun SearchOptionsDialog(vm: SearchViewModel, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(HeaderBg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Settings, null, tint = Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Search Options", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, "Close", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
                }
                HorizontalDivider(color = OutlineVariant)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OptionsSectionHeader("Stores")
                    val allStores = data.STORES.keys.sorted()
                    val allSelected = allStores.all { it in vm.enabledStores }
                    Surface(shape = RoundedCornerShape(6.dp), color = SurfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable { vm.enabledStores = if (allSelected) emptySet() else allStores.toSet() }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Text(
                                if (allSelected) "Deselect all stores" else "Select all stores",
                                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSurface, modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(6.dp))
                            Icon(
                                if (allSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                null, tint = if (allSelected) Primary else OutlineVariant, modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    allStores.forEach { store ->
                        OptionToggle(checked = store in vm.enabledStores, label = store, onChange = { vm.setStoreEnabled(store, it) })
                    }

                    HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

                    OptionsSectionHeader("Search Behaviour")
                    OptionToggle(
                        checked = vm.ignoreBasicLands,
                        label = "Ignore Basic Lands",
                        sublabel = "Plains, Island, Swamp, Mountain, Forest, Wastes",
                        onChange = { vm.ignoreBasicLands = it },
                    )
                    OptionToggle(
                        checked = vm.includePartialMatches,
                        label = "Include partial name matches",
                        sublabel = "Show listings whose name contains your search term, not just exact card name matches. " +
                            "Useful when searching a word that appears in many card names (e.g. \"Kraken\").",
                        onChange = { vm.includePartialMatches = it },
                    )

                    OptionsSectionHeader("Luckshack")
                    OptionToggle(
                        checked = vm.autoOpenLuckshack,
                        label = "Auto-open in browser on search",
                        sublabel = "Luckshack is Cloudflare-protected and cannot be scraped. " +
                            "When enabled, opens each card's Luckshack search page automatically.",
                        onChange = { vm.autoOpenLuckshack = it },
                    )
                }
            }
        }
    }
}

// Not private -- also reused by SearchMonitorsScreen.kt's store-selection grid.
@Composable
fun OptionsSectionHeader(title: String) {
    Text(title.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = OnSurfaceVariant.copy(alpha = 0.5f), letterSpacing = 1.sp)
}

@Composable
fun OptionToggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit, sublabel: String? = null) {
    Row(
        verticalAlignment = if (sublabel != null) Alignment.Top else Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 6.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = OnSurface)
            if (sublabel != null) Text(sublabel, fontSize = 11.sp, color = OnSurfaceVariant, lineHeight = 15.sp)
        }
        Spacer(Modifier.width(8.dp))
        Icon(
            if (checked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
            null, tint = if (checked) Primary else OutlineVariant, modifier = Modifier.size(18.dp),
        )
    }
}
