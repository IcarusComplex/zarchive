package ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidapp.ZArchiveApplication
import data.PlatformPaths
import java.io.File

actual class PlatformActions actual constructor() {
    actual fun openUrl(url: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ZArchiveApplication.appContext.startActivity(intent)
        }
    }

    actual fun copyToClipboard(text: String) {
        runCatching {
            val clipboard = ZArchiveApplication.appContext
                .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("ZArchive", text))
        }
    }

    actual val crashLogFile: File
        get() = PlatformPaths.debugDumpDir.resolve("crash.log")

    // No in-app update-install mechanism on Android until Phase 12 (FileProvider + package
    // installer intent) — the UI falls back to "open the release page" while this is false.
    actual fun canInstallUpdate(): Boolean = false

    actual suspend fun triggerUpdateInstall(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Android in-app update lands in Phase 12"))
}
