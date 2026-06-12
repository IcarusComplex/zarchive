import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import ui.App

fun main() = application {
    // Use the working area (screen minus taskbar) so the window never launches behind it.
    val genv = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
    val workArea = genv.maximumWindowBounds          // excludes taskbar on all platforms
    val density  = genv.defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    val winW = (workArea.width  * 0.95 / density).toInt()
    val winH = (workArea.height * 0.95 / density).toInt()
    // Centre within the working area (not the full screen).
    val winX = (workArea.x + (workArea.width  - winW * density).toInt() / 2).coerceAtLeast(workArea.x)
    val winY = (workArea.y + (workArea.height - winH * density).toInt() / 2).coerceAtLeast(workArea.y)
    val windowState = rememberWindowState(
        size     = DpSize(winW.dp, winH.dp),
        position = androidx.compose.ui.window.WindowPosition(winX.dp, winY.dp),
    )
    val icon = remember {
        runCatching {
            // Load from the classpath so it resolves both in `gradlew run` and the packaged app
            Thread.currentThread().contextClassLoader.getResourceAsStream("app_icon.png")
                ?.use { javax.imageio.ImageIO.read(it) }
                ?.toComposeImageBitmap()
                ?.let { BitmapPainter(it) }
        }.getOrNull()
    }
    Window(
        onCloseRequest = ::exitApplication,
        title = "ZArchive",
        state = windowState,
        undecorated = true,
        icon = icon,
    ) {
        App(::exitApplication, windowState, window)
    }
}
