import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import data.AppDatabase
import data.BuildInfo
import ui.App

private val crashLog = java.io.File(System.getProperty("user.home"), "zarchive-debug/crash.log")

// "Mac OS X"/"Windows 11" from os.name -> a short platform tag matching the naming used
// elsewhere (release asset filenames, README variants): "macOS"/"Windows".
private fun platformLabel(): String {
    val name = System.getProperty("os.name") ?: "Unknown OS"
    return when {
        name.startsWith("Mac") -> "macOS"
        name.startsWith("Windows") -> "Windows"
        else -> name
    }
}

private fun deviceInfoLine(): String {
    val osName = System.getProperty("os.name") ?: "Unknown OS"
    val osVersion = System.getProperty("os.version") ?: "?"
    val arch = System.getProperty("os.arch") ?: "?"
    val javaVersion = System.getProperty("java.version") ?: "?"
    return "$osName $osVersion ($arch), JRE $javaVersion"
}

private fun installCrashLogger() {
    Thread.setDefaultUncaughtExceptionHandler { thread, e ->
        runCatching {
            crashLog.parentFile.mkdirs()
            val ts = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
            crashLog.appendText(
                "[$ts] Thread: ${thread.name}\n" +
                    "App: ZArchive ${BuildInfo.VERSION} (${platformLabel()})\n" +
                    "Device: ${deviceInfoLine()}\n" +
                    "${e.stackTraceToString()}\n\n",
            )
        }
    }
}

// java.awt.Desktop.getDesktop() does lazy native COM/OLE init on Windows the first time
// it's touched in the process, which makes the user's first "external link" click stall
// for a few seconds. Pay that cost here in the background during startup instead.
private fun warmUpDesktop() {
    Thread {
        runCatching { if (java.awt.Desktop.isDesktopSupported()) java.awt.Desktop.getDesktop() }
    }.also { it.isDaemon = true }.start()
}

// Reads and deletes the crash log from the previous session, if any.
private fun readAndClearCrashLog(): String? {
    if (!crashLog.exists() || crashLog.length() == 0L) return null
    return runCatching {
        val content = crashLog.readText().trim()
        // Delete before returning so the dialog never repeats next launch.
        // If delete fails (file locked etc.) clear the content instead so it
        // stays empty and is skipped on the next check.
        if (!crashLog.delete()) crashLog.writeText("")
        content.ifEmpty { null }
    }.getOrNull()
}

fun main() {
    installCrashLogger()
    warmUpDesktop()
    AppDatabase.init()
    val pendingCrash = readAndClearCrashLog()
    application {
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
        // Fill the AWT window background with the app surface colour so the brief
        // gap between a window move and the next Compose repaint is invisible.
        SideEffect { window.background = java.awt.Color(0x0B, 0x13, 0x26) }
        App(windowState, ::exitApplication, pendingCrash)
    }
    }
}
