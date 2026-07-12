package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ui.theme.Mono
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerLow
import ui.theme.ErrorColor

private const val CARD_FORMAT_HELP_TEXT = "One card name per line\n" +
    "Adding cards to an existing search only looks up the new ones\n\n" +
    "Decklist format supported:\n" +
    "  4x Lightning Bolt\n" +
    "  1 Shadowspear\n" +
    "  [Creatures]   ← ignored\n" +
    "  # comment     ← ignored"

/**
 * Android's search input, adapted from desktop's LeftPanel/PanelSearch (a permanent left
 * sidebar) — mobile has no room for a persistent side panel, so this is a fixed-height card at
 * the top of the screen instead. Desktop's Alt+Enter shortcut has no Android equivalent: binding
 * a keyboard IME "search" action to this field would replace the soft keyboard's Enter key with a
 * dedicated search button, breaking the "one card per line" input format (Enter must still insert
 * a newline). The visible Search/Stop button — already the primary affordance on desktop too — is
 * the only way to trigger a search here.
 */
@Composable
fun SearchInputScreen(vm: SearchViewModel, onOpenSearchOptions: () -> Unit) {
    var showFormatHelp by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable { showFormatHelp = true },
            ) {
                Icon(Icons.Default.HelpOutline, "Supported input format", tint = OnSurfaceVariant, modifier = Modifier.size(14.dp))
                Text("Format help", fontSize = 12.sp, color = OnSurfaceVariant)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.clickable(onClick = onOpenSearchOptions),
            ) {
                Icon(Icons.Default.Settings, null, tint = OnSurfaceVariant, modifier = Modifier.size(14.dp))
                Text("Search Options", fontSize = 12.sp, color = OnSurfaceVariant)
            }
        }
        if (showFormatHelp) {
            FormatHelpDialog(onDismiss = { showFormatHelp = false })
        }
        var focused by remember { mutableStateOf(false) }
        BasicTextField(
            value = vm.query,
            onValueChange = { vm.query = it },
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .onFocusChanged { focused = it.isFocused },
            textStyle = TextStyle(fontFamily = Mono, fontSize = 13.sp, color = OnSurface),
            cursorBrush = SolidColor(Primary),
            decorationBox = { innerTextField ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceContainerLow, RoundedCornerShape(4.dp))
                        .border(1.dp, if (focused) Primary else OutlineVariant, RoundedCornerShape(4.dp))
                        .padding(12.dp),
                    contentAlignment = Alignment.TopStart,
                ) {
                    if (vm.query.isEmpty()) {
                        Text(
                            CARD_FORMAT_HELP_TEXT,
                            color = OnSurface.copy(alpha = 0.3f),
                            fontFamily = Mono,
                            fontSize = 11.sp,
                        )
                    }
                    innerTextField()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { if (vm.isSearching) vm.cancel() else vm.requestSearch() },
            modifier = Modifier.fillMaxWidth().height(44.dp),
            shape = RoundedCornerShape(4.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (vm.isSearching) ErrorColor.copy(alpha = 0.12f) else Primary.copy(alpha = 0.15f),
                contentColor = if (vm.isSearching) ErrorColor else Primary,
            ),
            contentPadding = PaddingValues(0.dp),
        ) {
            Text(
                if (vm.isSearching) "Stop" else "Search",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// Standalone Dialog (not ModalScrim) so it can be triggered directly from within the scrollable
// search-input content without needing to hoist state up to AndroidApp's top-level Box -- Dialog
// renders in its own window and already handles the scrim + outside-tap/back dismissal.
@Composable
private fun FormatHelpDialog(onDismiss: () -> Unit) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = SurfaceContainerLow,
            border = androidx.compose.foundation.BorderStroke(1.dp, OutlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text("Supported input format", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Primary)
                Spacer(Modifier.height(12.dp))
                Text(
                    CARD_FORMAT_HELP_TEXT,
                    color = OnSurface,
                    fontFamily = Mono,
                    fontSize = 13.sp,
                )
            }
        }
    }
}
