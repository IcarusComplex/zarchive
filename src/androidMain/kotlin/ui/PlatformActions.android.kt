package ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidapp.ZArchiveApplication
import androidx.core.content.FileProvider
import data.PlatformPaths
import network.downloadRelease
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

    // No local-file-picker UI on Android for collection import -- Drive is the only source there.
    actual fun pickCsvFile(): File? = null

    // Android's package installer handles the replace-over-existing-install flow itself once
    // handed a same-signature APK -- a real simplification versus desktop's swap scripts. The
    // system also handles the "allow installs from this source" permission prompt when needed.
    actual fun canInstallUpdate(): Boolean = true

    actual suspend fun triggerUpdateInstall(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit,
    ): Result<Unit> = runCatching {
        onPhase("Downloading update…")
        val context = ZArchiveApplication.appContext
        val dest = File(context.cacheDir, "update.apk")
        downloadRelease(downloadUrl, dest, onProgress)

        onPhase("Opening installer…")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", dest)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    }
}
