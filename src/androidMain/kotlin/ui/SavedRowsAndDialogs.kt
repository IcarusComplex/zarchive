package ui

import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SavedResultEntry
import data.SavedSearchList
import ui.theme.ErrorColor
import ui.theme.Mono
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerLowest

// Ported from ui/App.kt's SavedListRow/SavedResultRow/EditListDialog (desktop) plus two small
// reusable dialog shapes (SaveNameDialog/ConfirmDialog) factored out to avoid duplicating the same
// AlertDialog boilerplate between the Lists and Results screens -- desktop has this boilerplate
// duplicated once for lists and once for results; not worth doing that twice here too.
// Desktop's hover-reveals-delete-icon pattern doesn't apply to touch, so the edit/delete icons are
// always visible on Android instead of only on hover.

@Composable
fun SavedListRow(
    list: SavedSearchList,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Icon(Icons.Default.FormatListBulleted, null, tint = OnSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(8.dp))
        Text(list.name, fontSize = 13.sp, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Spacer(Modifier.width(6.dp))
        Text("${list.cards.size}", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f), fontFamily = Mono)
        Spacer(Modifier.width(10.dp))
        if (onEdit != null) {
            Icon(Icons.Default.Edit, "Edit", tint = OnSurfaceVariant, modifier = Modifier.size(15.dp).clickable(onClick = onEdit))
            Spacer(Modifier.width(10.dp))
        }
        Icon(Icons.Default.Close, "Delete", tint = ErrorColor.copy(alpha = 0.8f), modifier = Modifier.size(15.dp).clickable(onClick = onDelete))
    }
}

@Composable
fun SavedResultRow(
    entry: SavedResultEntry,
    onLoad: () -> Unit,
    onDelete: () -> Unit,
) {
    val dateStr = remember(entry.savedAt) {
        java.text.SimpleDateFormat("d MMM ''yy, HH:mm").format(java.util.Date(entry.savedAt))
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onLoad)
            .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Archive, null, tint = OnSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(8.dp))
            Text(entry.name, fontSize = 13.sp, color = OnSurface, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(6.dp))
            Text("${entry.cardCount}", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.5f), fontFamily = Mono)
            Spacer(Modifier.width(10.dp))
            Icon(Icons.Default.Close, "Delete", tint = ErrorColor.copy(alpha = 0.8f), modifier = Modifier.size(15.dp).clickable(onClick = onDelete))
        }
        Row(Modifier.padding(start = 22.dp, top = 2.dp)) {
            Text(dateStr, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.4f), fontFamily = Mono)
            if (entry.description.isNotBlank()) {
                Text(" · ", fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.3f))
                Text(entry.description, fontSize = 10.sp, color = OnSurfaceVariant.copy(alpha = 0.5f),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SaveNameDialog(
    title: String,
    nameLabel: String,
    name: String,
    onNameChange: (String) -> Unit,
    description: String? = null,
    onDescriptionChange: ((String) -> Unit)? = null,
    extraInfo: String? = null,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = onNameChange,
                    label = { Text(nameLabel, fontSize = 12.sp) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary, focusedLabelColor = Primary,
                        cursorColor = Primary, unfocusedBorderColor = OutlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (description != null && onDescriptionChange != null) {
                    OutlinedTextField(
                        value = description, onValueChange = onDescriptionChange,
                        label = { Text("Description (optional)", fontSize = 12.sp) },
                        minLines = 2, maxLines = 4,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Primary, focusedLabelColor = Primary,
                            cursorColor = Primary, unfocusedBorderColor = OutlineVariant,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (extraInfo != null) {
                    Text(extraInfo, fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave, enabled = name.isNotBlank()) { Text("Save", color = Primary) } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel", color = OnSurfaceVariant) } },
        containerColor = SurfaceContainerLowest,
        titleContentColor = OnSurface,
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
        text = { Text(message, fontSize = 13.sp, color = OnSurfaceVariant) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel, color = Primary) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurfaceVariant) } },
        containerColor = SurfaceContainerLowest,
        titleContentColor = OnSurface,
    )
}

@Composable
fun EditListDialog(
    list: SavedSearchList,
    onSave: (name: String, cards: List<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    var nameDraft by remember(list.id) { mutableStateOf(list.name) }
    var cardsDraft by remember(list.id) { mutableStateOf(list.cards.joinToString("\n")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit list", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = OnSurface) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = nameDraft, onValueChange = { nameDraft = it },
                    label = { Text("List name", fontSize = 12.sp) }, singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Primary, focusedLabelColor = Primary,
                        cursorColor = Primary, unfocusedBorderColor = OutlineVariant,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Cards (one per line)", fontSize = 11.sp, color = OnSurfaceVariant.copy(alpha = 0.7f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(220.dp)
                        .border(1.dp, OutlineVariant, RoundedCornerShape(4.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
                ) {
                    BasicTextField(
                        value = cardsDraft,
                        onValueChange = { cardsDraft = it },
                        textStyle = TextStyle(color = OnSurface, fontSize = 13.sp),
                        cursorBrush = SolidColor(Primary),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val cards = cardsDraft.lines().map { it.trim() }.filter { it.isNotBlank() }
                    onSave(nameDraft.trim(), cards)
                },
                enabled = nameDraft.isNotBlank(),
            ) { Text("Save", color = Primary) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = OnSurfaceVariant) } },
        containerColor = SurfaceContainerLowest,
        titleContentColor = OnSurface,
    )
}
