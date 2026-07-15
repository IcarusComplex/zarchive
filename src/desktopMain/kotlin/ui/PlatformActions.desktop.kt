package ui

import data.PlatformPaths
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

actual class PlatformActions actual constructor() {
    private val installDir: File? by lazy { DesktopUpdateInstaller.resolveInstallDir() }

    actual fun openUrl(url: String) {
        runCatching {
            val desktop = Desktop.getDesktop()
            if (Desktop.isDesktopSupported() && desktop.isSupported(Desktop.Action.BROWSE)) {
                desktop.browse(URI(url))
            }
        }
    }

    actual fun copyToClipboard(text: String) {
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        }
    }

    actual fun pickCsvFile(): File? = runCatching {
        val dialog = java.awt.FileDialog(null as java.awt.Frame?, "Choose a collection CSV", java.awt.FileDialog.LOAD)
        dialog.setFilenameFilter { _, name -> name.endsWith(".csv", ignoreCase = true) }
        dialog.isVisible = true
        val dir = dialog.directory
        val name = dialog.file
        if (dir != null && name != null) File(dir, name) else null
    }.getOrNull()

    actual val crashLogFile: File
        get() = PlatformPaths.debugDumpDir.resolve("crash.log")

    actual fun canInstallUpdate(): Boolean = installDir != null

    actual suspend fun triggerUpdateInstall(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit,
    ): Result<Unit> = DesktopUpdateInstaller.install(downloadUrl, onProgress, onPhase)
}
