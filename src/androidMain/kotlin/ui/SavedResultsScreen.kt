package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import data.SavedResultEntry
import ui.theme.HeaderBg
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerLowest

// Ported from ui/App.kt's SavedListsPanel (Results half) plus its "all results" AlertDialog
// (desktop) -- same full-screen-Dialog adaptation as SavedListsScreen.kt for the same reason (no
// room for a permanent pinned-rows sidebar panel on a phone).
@Composable
fun SavedResultsDialog(vm: SearchViewModel, onDismiss: () -> Unit) {
    val savedResults by vm.savedResults.collectAsState()
    var filter by remember { mutableStateOf("") }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }
    var saveDesc by remember { mutableStateOf("") }
    var overwriteTarget by remember { mutableStateOf<SavedResultEntry?>(null) }

    val filtered = remember(savedResults, filter) {
        val q = filter.trim()
        if (q.isBlank()) savedResults else savedResults.filter { entry ->
            entry.name.contains(q, ignoreCase = true) ||
                entry.description.contains(q, ignoreCase = true) ||
                entry.cards.any { it.contains(q, ignoreCase = true) }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(HeaderBg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Saved Results", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.weight(1f))
                    if (vm.results.isNotEmpty()) {
                        Icon(
                            Icons.Default.Archive, "Save current results",
                            tint = Primary,
                            modifier = Modifier.size(20.dp).clickable {
                                saveName = vm.lastLoadedResultName ?: ""
                                saveDesc = ""
                                showSaveDialog = true
                            },
                        )
                        Spacer(Modifier.size(16.dp))
                    }
                    Icon(Icons.Default.Close, "Close", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
                }
                HorizontalDivider(color = OutlineVariant)
                Box(Modifier.padding(12.dp)) {
                    FilterField(filter, placeholder = "Search saved results or cards…") { filter = it }
                }
                if (filtered.isEmpty()) {
                    Text(
                        if (savedResults.isEmpty()) "No saved results yet\nSearch for cards then use the archive icon to save" else "No matching results",
                        fontSize = 13.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { entry ->
                        SavedResultRow(
                            entry = entry,
                            onLoad = { vm.loadSavedResult(entry); onDismiss() },
                            onDelete = { vm.deleteSavedResult(entry.id) },
                        )
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        val listingCount = vm.results.count { it.title != null }
        SaveNameDialog(
            title = "Save results",
            nameLabel = "Name",
            name = saveName,
            onNameChange = { saveName = it },
            description = saveDesc,
            onDescriptionChange = { saveDesc = it },
            extraInfo = "${vm.searchedCards.size} cards · $listingCount listings",
            onSave = {
                val trimmed = saveName.trim()
                val existing = savedResults.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                if (existing != null) overwriteTarget = existing
                else { vm.saveCurrentResults(trimmed, saveDesc.trim()); showSaveDialog = false }
            },
            onCancel = { showSaveDialog = false },
        )
    }
    overwriteTarget?.let { existing ->
        ConfirmDialog(
            title = "Overwrite result?",
            message = "A saved result named \"${existing.name}\" already exists. Overwrite it with the current results?",
            confirmLabel = "Overwrite",
            onConfirm = {
                vm.overwriteSavedResult(existing.id, saveName.trim(), saveDesc.trim())
                overwriteTarget = null
                showSaveDialog = false
            },
            onDismiss = { overwriteTarget = null },
        )
    }
}
