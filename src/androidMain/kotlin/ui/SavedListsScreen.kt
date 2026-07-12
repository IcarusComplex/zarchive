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
import androidx.compose.material.icons.filled.BookmarkAdd
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
import data.SavedSearchList
import ui.theme.HeaderBg
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerLowest

// Ported from ui/App.kt's SavedListsPanel (Lists half) plus its "all lists" AlertDialog (desktop).
// Desktop keeps a permanent 3-row-pinned sidebar panel plus a "view all" modal for the overflow;
// there's no room for a permanent panel on a phone, so this is a single full-screen Dialog reached
// from a toolbar icon in AndroidApp, always showing the complete list rather than a pinned subset.
@Composable
fun SavedListsDialog(vm: SearchViewModel, onDismiss: () -> Unit) {
    val lists by vm.savedLists.collectAsState()
    var filter by remember { mutableStateOf("") }
    var editingList by remember { mutableStateOf<SavedSearchList?>(null) }
    var showSaveDialog by remember { mutableStateOf(false) }
    var saveNameDraft by remember { mutableStateOf("") }
    var overwriteTarget by remember { mutableStateOf<SavedSearchList?>(null) }

    val filtered = remember(lists, filter) {
        val q = filter.trim()
        if (q.isBlank()) lists else lists.filter { l -> l.name.contains(q, ignoreCase = true) || l.cards.any { it.contains(q, ignoreCase = true) } }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(color = SurfaceContainerLowest, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().background(HeaderBg).statusBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text("Saved Lists", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface, modifier = Modifier.weight(1f))
                    if (vm.query.isNotBlank()) {
                        Icon(
                            Icons.Default.BookmarkAdd, "Save current list",
                            tint = Primary,
                            modifier = Modifier.size(20.dp).clickable {
                                saveNameDraft = vm.lastLoadedListName ?: ""
                                showSaveDialog = true
                            },
                        )
                        Spacer(Modifier.size(16.dp))
                    }
                    Icon(Icons.Default.Close, "Close", tint = OnSurfaceVariant, modifier = Modifier.size(20.dp).clickable(onClick = onDismiss))
                }
                HorizontalDivider(color = OutlineVariant)
                Box(Modifier.padding(12.dp)) {
                    FilterField(filter, placeholder = "Search saved lists or cards…") { filter = it }
                }
                if (filtered.isEmpty()) {
                    Text(
                        if (lists.isEmpty()) "No saved lists yet" else "No matching lists",
                        fontSize = 13.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                LazyColumn(Modifier.weight(1f)) {
                    items(filtered, key = { it.id }) { list ->
                        SavedListRow(
                            list = list,
                            onLoad = { vm.loadSearchList(list); onDismiss() },
                            onDelete = { vm.deleteSearchList(list.id) },
                            onEdit = { editingList = list },
                        )
                        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        SaveNameDialog(
            title = "Save list",
            nameLabel = "List name",
            name = saveNameDraft,
            onNameChange = { saveNameDraft = it },
            onSave = {
                val trimmed = saveNameDraft.trim()
                val existing = lists.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
                if (existing != null) overwriteTarget = existing
                else { vm.saveSearchList(trimmed); showSaveDialog = false }
            },
            onCancel = { showSaveDialog = false },
        )
    }
    overwriteTarget?.let { existing ->
        ConfirmDialog(
            title = "Overwrite list?",
            message = "A list named \"${existing.name}\" already exists. Overwrite it with the current cards?",
            confirmLabel = "Overwrite",
            onConfirm = { vm.overwriteSearchList(existing.id, saveNameDraft.trim()); overwriteTarget = null; showSaveDialog = false },
            onDismiss = { overwriteTarget = null },
        )
    }
    editingList?.let { target ->
        EditListDialog(
            list = target,
            onSave = { name, cards -> vm.saveEditedList(target.id, name, cards); editingList = null },
            onDismiss = { editingList = null },
        )
    }
}
