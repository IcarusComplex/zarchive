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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import data.SearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ui.theme.Mono
import ui.theme.OnSecondaryContainer
import ui.theme.OnSurface
import ui.theme.OnSurfaceVariant
import ui.theme.OutlineVariant
import ui.theme.Primary
import ui.theme.SurfaceContainerHighest
import ui.theme.SurfaceContainerLow

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

// MTG card aspect ratio (2.5in : 3.5in = 5:7), matching the original fixed 260x364 size.
private const val CARD_ASPECT_H_OVER_W = 364f / 260f
private const val CARD_PREVIEW_WIDTH_FRACTION = 0.85f

// Full-screen tap-to-dismiss preview, hosted inside a ModalScrim by the caller (AndroidApp) —
// replaces desktop's anchored hover Popup with a mobile-idiomatic centered "lightbox" that
// dismisses on tapping anywhere on the scrim, including a small explicit close affordance. Sized
// to 85% of the screen width (not a fixed dp size) so it reads as a real "zoomed in" card on any
// phone, with height derived from the fixed MTG card aspect ratio.
@Composable
fun EnlargedCardPreview(path: String, onDismiss: () -> Unit) {
    val bmp = rememberCardBitmap(path)
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val width = screenWidth * CARD_PREVIEW_WIDTH_FRACTION
    val height = width * CARD_ASPECT_H_OVER_W
    Box(
        Modifier
            .size(width, height)
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

private fun formatZar(v: Double): String {
    val totalCents = Math.round(v * 100)
    val whole = totalCents / 100
    val cents = (totalCents % 100).toString().padStart(2, '0')
    val grouped = whole.toString().reversed().chunked(3).joinToString(" ").reversed()
    return "R$grouped,$cents"
}

// Tapping anywhere on a result row (ResultsScreen.kt's ListingCard), not just its thumbnail, opens
// this instead of the bare EnlargedCardPreview -- same enlarged art, plus the full title/set/store/
// price and the same "Use this version"/"Open in store" actions the row's own footer has, for when
// the row's own truncated text isn't enough. Hosted inside a ModalScrim by the caller (AndroidApp),
// same as EnlargedCardPreview.
@Composable
fun CardDetailModal(
    result: SearchResult,
    imagePath: String?,
    isPinned: Boolean,
    onTogglePin: (() -> Unit)?,
    onOpenUrl: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val bmp = rememberCardBitmap(imagePath)
    val screenWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    val cardWidth = screenWidth * CARD_PREVIEW_WIDTH_FRACTION * 0.7f
    val cardHeight = cardWidth * CARD_ASPECT_H_OVER_W

    Column(
        modifier = Modifier
            .fillMaxWidth(0.9f)
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceContainerLow)
            .border(1.dp, OutlineVariant, RoundedCornerShape(8.dp))
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(cardWidth, cardHeight).shadow(16.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp))) {
            if (bmp != null) {
                Image(bmp, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize().background(SurfaceContainerHighest)) { ShimmerOverlay() }
            }
        }
        Text(
            result.title ?: result.card,
            fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = OnSurface,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
        val subtitle = result.note.takeIf { it.isNotBlank() && it !in setOf("In stock", "Out of stock", "not stocked") }
        if (subtitle != null) {
            Text(subtitle, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
        if (!result.setHint.isNullOrBlank()) {
            Text(result.setHint, fontSize = 12.sp, color = OnSurfaceVariant.copy(alpha = 0.7f), textAlign = TextAlign.Center)
        }
        Text(result.store, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = OnSecondaryContainer)
        Text(
            text = result.priceZar?.let { formatZar(it) } ?: "N/A",
            fontFamily = Mono,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = if (result.priceZar != null) Primary else OnSurfaceVariant.copy(alpha = 0.6f),
        )
        HorizontalDivider(color = OutlineVariant.copy(alpha = 0.4f))
        Row(Modifier.fillMaxWidth().height(48.dp)) {
            if (onTogglePin != null) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { onTogglePin() }
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (isPinned) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                        null,
                        tint = if (isPinned) Primary else OnSurfaceVariant.copy(alpha = 0.7f),
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Use this version",
                        fontSize = 12.sp,
                        fontWeight = if (isPinned) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isPinned) Primary else OnSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                VerticalDivider(color = OutlineVariant.copy(alpha = 0.3f))
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable { onOpenUrl(result.url); onDismiss() }
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.OpenInNew, null, tint = OnSurfaceVariant, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Open in store", fontSize = 12.sp, color = OnSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
