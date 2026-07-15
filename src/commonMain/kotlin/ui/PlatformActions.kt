package ui

import java.io.File

/**
 * Platform-specific side effects the shared `SearchViewModel` needs but can't implement itself:
 * opening a URL, clipboard access, the crash-log file location, and the in-app update flow.
 *
 * Desktop's `triggerUpdateInstall` runs the full download/extract/swap-script/relaunch flow
 * unchanged from before the Android port. Android's is a no-op stub returning failure until
 * Phase 12 wires up the FileProvider/package-installer flow — `canInstallUpdate()` returns false
 * on Android in the meantime, so the UI falls back to "open the release page in a browser".
 */
expect class PlatformActions() {
    fun openUrl(url: String)
    fun copyToClipboard(text: String)
    val crashLogFile: File

    /**
     * Opens a native file picker filtered to `.csv`, for local collection-import (desktop only --
     * Android has no equivalent UI affordance for this and returns null unconditionally).
     */
    fun pickCsvFile(): File?

    fun canInstallUpdate(): Boolean

    /**
     * Downloads and installs [downloadUrl], reporting 0..1 progress and phase-text updates as it
     * goes. Returns success once the platform has done whatever it needs to relaunch the app with
     * the new version (desktop: swap script written and launched; Android, from Phase 12: the
     * system package-installer intent has been started).
     */
    suspend fun triggerUpdateInstall(
        downloadUrl: String,
        onProgress: (Float) -> Unit,
        onPhase: (String) -> Unit,
    ): Result<Unit>
}
