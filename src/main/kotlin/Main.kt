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
    val screenSize = java.awt.Toolkit.getDefaultToolkit().screenSize
    val density = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration.defaultTransform.scaleX
    val screenW = (screenSize.width  * 0.95 / density).toInt()
    val screenH = (screenSize.height * 0.95 / density).toInt()
    val windowState = rememberWindowState(size = DpSize(screenW.dp, screenH.dp))
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
