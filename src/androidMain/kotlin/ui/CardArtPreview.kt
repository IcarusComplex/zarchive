package ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.SurfaceContainerHighest

// Ported from ui/App.kt's CardThumbnail/ShimmerOverlay/CardImagePopup (desktop) — the hover-driven
// popup is replaced here with an explicit tap: tapping a thumbnail invokes [onTap] (wired by the
// caller to show a full-screen ModalScrim with EnlargedCardPreview), rather than any hover state.
private val imageCache = java.util.concurrent.ConcurrentHashMap<String, ImageBitmap>()
private val grayscaleFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun rememberCardBitmap(path: String?): ImageBitmap? {
    var bitmap by remember(path) { mutableStateOf(path?.let { imageCache[it] }) }
    LaunchedEffect(path) {
        if (path != null && bitmap == null) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching {
                    val f = java.io.File(path)
                    if (f.exists()) BitmapFactory.decodeFile(path)?.asImageBitmap() else null
                }.getOrNull()
            }
            if (loaded != null) {
                imageCache[path] = loaded
                bitmap = loaded
            }
        }
    }
    return bitmap
}

@Composable
fun CardThumbnail(
    path: String?,
    dimmed: Boolean,
    onTap: (() -> Unit)? = null,
    width: Dp = 46.dp,
    height: Dp = 64.dp,
) {
    val bmp = rememberCardBitmap(path)
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier.size(width, height).clip(shape)
            .background(SurfaceContainerHighest)
            .border(1.dp, OutlineVariant, shape)
            .then(if (onTap != null && bmp != null) Modifier.clickable { onTap() } else Modifier),
    ) {
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (dimmed) grayscaleFilter else null,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (path != null) {
            ShimmerOverlay()
        }
    }
}

@Composable
fun ShimmerOverlay() {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 800, easing = LinearEasing)),
        label = "shimmerProgress",
    )
    val sweep = progress * 900f
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0f),
                        Color.White.copy(alpha = 0.06f),
                        Color.White.copy(alpha = 0f),
                    ),
                    start = Offset(sweep - 250f, 0f),
                    end = Offset(sweep, 400f),
                ),
            ),
    )
}

private val ENLARGED_CARD_W = 260.dp
private val ENLARGED_CARD_H = 364.dp

// Full-screen tap-to-dismiss preview, hosted inside a ModalScrim by the caller (AndroidApp) —
// replaces desktop's anchored hover Popup with a mobile-idiomatic centered "lightbox" that
// dismisses on tapping anywhere on the scrim, including a small explicit close affordance.
@Composable
fun EnlargedCardPreview(path: String, onDismiss: () -> Unit) {
    val bmp = rememberCardBitmap(path)
    Box(
        Modifier
            .size(ENLARGED_CARD_W, ENLARGED_CARD_H)
            .shadow(24.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)),
    ) {
        if (bmp != null) {
            Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(Modifier.fillMaxSize().background(SurfaceContainerHighest)) { ShimmerOverlay() }
        }
        Icon(
            Icons.Default.Close,
            "Close preview",
            tint = OnSurfaceVariant,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(6.dp)
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.4f))
                .clickable { onDismiss() }
                .padding(4.dp),
        )
    }
}
