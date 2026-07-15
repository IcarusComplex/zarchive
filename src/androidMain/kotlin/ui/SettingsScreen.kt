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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Inventory2
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
import androidx.compose.runtime.collectAsState
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
import ui.theme.Tertiary

private const val SUPPORT_URL = "https://ko-fi.com/icaruscomplexza"

// Ported from ui/App.kt's SettingsMenu/SettingsCheckItem/SettingsLinkItem/SettingsActionItem
// (desktop) -- desktop's custom Popup becomes a standard Material3 DropdownMenu, the native
// Android equivalent. Links open via PlatformActions (Intent.ACTION_VIEW) instead of
// Desktop.getDesktop().browse.
@Composable
fun SettingsMenu(vm: SearchViewModel, onOpenUrl: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var showCollectionDialog by remember { mutableStateOf(false) }
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
            val ownedCount by vm.ownedCardNames.collectAsState()
            DropdownMenuItem(
                text = {
                    SettingsItemContent(
                        label = "Collection Import",
                        sublabel = if (ownedCount.isEmpty()) "Not set up" else "${ownedCount.size} cards imported",
                        icon = Icons.Default.Inventory2,
                    )
                },
                onClick = { showCollectionDialog = true; expanded = false },
            )
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
        if (showCollectionDialog) {
            CollectionImportDialog(vm, onDismiss = { showCollectionDialog = false })
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

// Ported from ui/App.kt's CollectionImportDialog (desktop) -- Drive-only here (no local-file
// picker on Android, per PlatformActions.pickCsvFile()'s Android actual returning null).
@Composable
fun CollectionImportDialog(vm: SearchViewModel, onDismiss: () -> Unit) {
    val groups by vm.collectionGroups.collectAsState()
    val ownedCount by vm.ownedCardNames.collectAsState()
    val connected = vm.syncStatus != SyncStatus.DISCONNECTED

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(HeaderBg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Icon(Icons.Default.Inventory2, null, tint = Primary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Collection Import", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.weight(1f))
                    Icon(Icons.Default.Close, "Close", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
                }
                HorizontalDivider(color = OutlineVariant)
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    OptionsSectionHeader("Source")
                    Text(
                        "${vm.collectionFormatLabel} — on a desktop, export your collection as a CSV and " +
                            "use \"Import from file\" (Settings there). That uploads it to the \"ZArchive\" " +
                            "Google Drive folder as \"${vm.collectionDriveFileName}\", which \"Import from " +
                            "Google Drive\" below can then find here. A file dragged into that folder via " +
                            "Drive's website directly won't be visible to ZArchive (Google's restricted " +
                            "Drive access) — it has to go through the app's own import at least once.",
                        fontSize = 12.sp, color = OnSurfaceVariant, lineHeight = 16.sp,
                    )

                    OptionsSectionHeader("Import")
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (connected) SurfaceContainerHigh else SurfaceContainerHigh.copy(alpha = 0.5f),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clickable(enabled = connected && vm.collectionImportStatus != CollectionImportStatus.IMPORTING) {
                                    vm.importCollectionFromDrive()
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        ) {
                            Icon(Icons.Default.CloudDownload, null, tint = if (connected) OnSurfaceVariant else OnSurfaceVariant.copy(alpha = 0.4f), modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (connected) "Import from Google Drive" else "Import from Google Drive (connect Drive first)",
                                fontSize = 13.sp, color = if (connected) OnSurface else OnSurfaceVariant.copy(alpha = 0.5f),
                            )
                        }
                    }

                    when (vm.collectionImportStatus) {
                        CollectionImportStatus.IMPORTING -> Text("Importing…", fontSize = 12.sp, color = OnSurfaceVariant)
                        CollectionImportStatus.ERROR -> Text(
                            vm.collectionImportError ?: "Import failed", fontSize = 12.sp, color = ErrorColor,
                        )
                        CollectionImportStatus.IDLE -> {
                            val lastImportedAt = vm.collectionLastImportedAt
                            Text(
                                if (lastImportedAt == null) "Never imported" else
                                    "Last imported ${java.text.SimpleDateFormat("d MMM yyyy, HH:mm").format(java.util.Date(lastImportedAt))} — ${ownedCount.size} cards owned",
                                fontSize = 12.sp, color = OnSurfaceVariant,
                            )
                        }
                    }

                    HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))

                    OptionsSectionHeader("Which groups count as owned")
                    if (groups.isEmpty()) {
                        Text(
                            "Import a collection above to choose which binders/lists count as owned. " +
                                "Everything is included by default -- nothing is auto-excluded.",
                            fontSize = 12.sp, color = OnSurfaceVariant,
                        )
                    } else {
                        val listGroups = groups.filter { it.type.equals("list", ignoreCase = true) }
                        val binderGroups = groups.filter { it.type.equals("binder", ignoreCase = true) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            if (listGroups.isNotEmpty()) {
                                val allListsIncluded = listGroups.all { it.included }
                                GroupTypeBulkToggle(
                                    label = if (allListsIncluded) "Exclude lists" else "Include lists",
                                    onClick = { vm.setCollectionGroupsOfTypeIncluded("list", !allListsIncluded) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (binderGroups.isNotEmpty()) {
                                val allBindersIncluded = binderGroups.all { it.included }
                                GroupTypeBulkToggle(
                                    label = if (allBindersIncluded) "Exclude binders" else "Include binders",
                                    onClick = { vm.setCollectionGroupsOfTypeIncluded("binder", !allBindersIncluded) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                        groups.forEach { group ->
                            OptionToggle(
                                checked = group.included,
                                label = "${group.name}  ·  ${group.type}",
                                sublabel = "${group.cardCount} distinct card${if (group.cardCount == 1) "" else "s"}",
                                onChange = { vm.setCollectionGroupIncluded(group.name, it) },
                            )
                        }
                    }
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

// Bulk "Exclude lists"/"Exclude binders" (and their invert) action, styled like the "Select/
// Deselect all stores" row in SearchOptionsDialog above.
@Composable
private fun GroupTypeBulkToggle(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(shape = RoundedCornerShape(6.dp), color = SurfaceContainerHigh, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp).fillMaxWidth(),
        ) {
            Text(label, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = OnSurface)
        }
    }
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
