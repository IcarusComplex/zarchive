package ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

// Ported verbatim from ui/App.kt's ModalScrim (desktop) — a plain Compose overlay, no
// desktop-specific API involved. Shared by every dialog phase from here on. [onDismiss] fires when
// the scrim itself (not the content) is tapped -- Phase 7's tap-to-dismiss card art preview is the
// first caller to need this; it defaults to a no-op so earlier non-dismissible usage is unaffected.
@Composable
fun ModalScrim(onDismiss: () -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.65f))
            .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) { onDismiss() },
        contentAlignment = Alignment.Center,
        content = content,
    )
}
