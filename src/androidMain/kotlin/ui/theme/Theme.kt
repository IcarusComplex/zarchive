package ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily

// ── Arcane Market Ledger palette (see CLAUDE.md) — same values as ui/App.kt's desktop palette.
// Duplicated here (not shared) since these are plain Color constants with no logic; if a later
// phase wants one shared source of truth, promoting this to commonMain is a trivial follow-up.
val Surface                 = Color(0xFF0B1326)
val SurfaceContainerLowest  = Color(0xFF060E20)
val SurfaceContainerLow     = Color(0xFF131B2E)
val SurfaceContainer        = Color(0xFF171F33)
val SurfaceContainerHigh    = Color(0xFF222A3D)
val SurfaceContainerHighest = Color(0xFF2D3449)
val OnSurface                = Color(0xFFDAE2FD)
val OnSurfaceVariant         = Color(0xFFD0C5AF)
val Outline                  = Color(0xFF99907C)
val OutlineVariant           = Color(0xFF4D4635)
val Primary                  = Color(0xFFF2CA50)
val OnPrimary                = Color(0xFF3C2F00)
val Secondary                = Color(0xFFC0C1FF)
val OnSecondaryContainer     = Color(0xFFB0B2FF)
val Tertiary                 = Color(0xFF58E7AA)
val ErrorColor                = Color(0xFFFFB4AB)
val HeaderBg                 = Color(0xFF00020C)

val Mono = FontFamily.Monospace

@Composable
fun ZArchiveTheme(content: @Composable () -> Unit) {
    // Always dark — the design is a dedicated "void"-dark palette, not a light/dark pair.
    val colorScheme = darkColorScheme(
        background   = Surface,
        surface      = SurfaceContainer,
        primary      = Primary,
        onPrimary    = OnPrimary,
        onBackground = OnSurface,
        onSurface    = OnSurface,
    )
    MaterialTheme(colorScheme = colorScheme, content = content)
}
