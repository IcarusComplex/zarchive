package ui

import network.downloadRelease
import java.io.File

/**
 * Desktop's self-update mechanism: download the release zip, extract it in-process, hand off to a
 * generated swap script (rename old install -> move new install into place -> relaunch), matching
 * the app's behavior before the Android port. Moved out of `SearchViewModel` in Phase 4 so the
 * shared ViewModel has no compile-time dependency on `ProcessBuilder`/`java.util.zip.ZipFile`.
 */
object DesktopUpdateInstaller {
    /** Resolved once — null in dev mode (`mtg.debug=true`) or if the exe isn't found next to the jar. */
    fun resolveInstallDir(): File? {
        if (System.getProperty("mtg.debug") == "true") return null
        return runCatching {
            val loc = object {}.javaClass.protectionDomain?.codeSource?.location ?: return null
            val jar = File(loc.toURI())
            // Windows layout: ZArchive/app/*.jar  →  jar.parent.parent = ZArchive/
            val winCandidate = jar.parentFile?.parentFile
            if (winCandidate?.resolve("ZArchive.exe")?.exists() == true) return winCandidate
            // macOS layout:   ZArchive.app/Contents/app/*.jar  →  jar.parent.parent.parent = ZArchive.app/
            val macCandidate = jar.parentFile?.parentFile?.parentFile
            if (macCandidate?.name?.endsWith(".app") == true &&
                macCandidate.resolve("Contents/MacOS").exists()
            ) return macCandidate
            null
        }.getOrNull()
    }

    suspend fun install(downloadUrl: String, onProgress: (Float) -> Unit, onPhase: (String) -> Unit): Result<Unit> {
        val targetDir = resolveInstallDir() ?: return Result.failure(IllegalStateException("No install dir resolved"))
        val isMac = System.getProperty("os.name", "").orEmpty().lowercase().contains("mac")
        return runCatching {
            val tmp = File(System.getProperty("java.io.tmpdir"), "ZArchive-update").also { it.mkdirs() }
            val zip = tmp.resolve("ZArchive-update.zip")
            val extractDir = tmp.resolve("ZArchive-update-extract")

            // Phase 1: download
            downloadRelease(downloadUrl, zip) { p -> onProgress(p) }

            // Phase 2: extract in-process so we can report progress before closing
            onPhase("Extracting…")
            extractZipWithProgress(zip, extractDir) { p -> onProgress(p) }
            zip.delete()

            // Phase 3: hand off to a minimal swap script (rename + relaunch only)
            val pb: ProcessBuilder = if (isMac) {
                val script = tmp.resolve("zarchive-swapper.sh")
                script.writeText(buildMacSwapScript())
                script.setExecutable(true)
                ProcessBuilder("bash", script.absolutePath, extractDir.absolutePath, targetDir.absolutePath)
            } else {
                val script = tmp.resolve("zarchive-swapper.ps1")
                script.writeText(buildWindowsSwapScript())
                ProcessBuilder(
                    "powershell.exe", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                    "-ExtractDir", extractDir.absolutePath,
                    "-InstallDir", targetDir.absolutePath,
                )
            }
            pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
            pb.redirectError(ProcessBuilder.Redirect.DISCARD)
            pb.start()
        }
    }

    private fun extractZipWithProgress(zip: File, dest: File, onProgress: (Float) -> Unit) {
        dest.deleteRecursively()
        dest.mkdirs()
        java.util.zip.ZipFile(zip).use { zf ->
            val entries = zf.entries().toList()
            val total = entries.size.toFloat().coerceAtLeast(1f)
            var lastProgressMs = 0L
            entries.forEachIndexed { idx, entry ->
                val out = dest.resolve(entry.name)
                if (entry.isDirectory) { out.mkdirs() }
                else {
                    out.parentFile?.mkdirs()
                    zf.getInputStream(entry).use { it.copyTo(out.outputStream(), bufferSize = 65_536) }
                }
                // Throttle to ≤10 UI updates/sec — per-entry callbacks for a JRE (thousands
                // of files) saturate the EDT and slow extraction significantly.
                val now = System.currentTimeMillis()
                if (now - lastProgressMs >= 500) {
                    onProgress((idx + 1) / total)
                    lastProgressMs = now
                }
            }
        }
        onProgress(1f)
    }

    private fun buildWindowsSwapScript(): String {
        val D = "\$"
        return """
param([string]${D}ExtractDir, [string]${D}InstallDir)

${D}log = Join-Path ${D}env:TEMP 'zarchive-updater.log'
function Log(${D}msg) { "$(Get-Date -f 'HH:mm:ss') ${D}msg" | Out-File -FilePath ${D}log -Append -Encoding utf8 }

Log "Swap started. ExtractDir=${D}ExtractDir InstallDir=${D}InstallDir"

${D}deadline = [datetime]::Now.AddSeconds(30)
while ((Get-Process -Name 'ZArchive' -ErrorAction SilentlyContinue) -and ([datetime]::Now -lt ${D}deadline)) {
    Start-Sleep -Milliseconds 500
}
Start-Sleep -Seconds 2
Log "ZArchive process exited"

${D}parent  = Split-Path ${D}InstallDir -Parent
${D}name    = Split-Path ${D}InstallDir -Leaf
${D}backup  = Join-Path ${D}parent (${D}name + '-backup')
${D}swapped = ${D}false
${D}renamed = ${D}false

try {
    ${D}extracted = Get-ChildItem ${D}ExtractDir -Directory | Select-Object -First 1
    if (-not ${D}extracted) { throw "No directory found in extract dir" }
    if (Test-Path ${D}backup) { Remove-Item ${D}backup -Recurse -Force }

    # Try rename first (clean swap). Retry up to 5x in case JVM handles linger.
    for (${D}i = 0; ${D}i -lt 5; ${D}i++) {
        try {
            Rename-Item ${D}InstallDir (${D}name + '-backup') -ErrorAction Stop
            ${D}renamed = ${D}true
            break
        } catch {
            Log "Rename attempt $( ${D}i + 1 ) failed: ${D}_ - retrying in 2s"
            Start-Sleep -Seconds 2
        }
    }

    if (${D}renamed) {
        Move-Item ${D}extracted.FullName ${D}InstallDir -ErrorAction Stop
        Log "Moved new install into place"
    } else {
        # Rename still failing (e.g. Explorer has the folder open) - copy in-place.
        Log "Rename failed after 5 attempts - falling back to robocopy"
        robocopy ${D}extracted.FullName ${D}InstallDir /E /IS /IT /PURGE /NFL /NDL /NJH /NJS
        ${D}rc = ${D}LASTEXITCODE
        if (${D}rc -ge 8) { throw "robocopy failed with exit code ${D}rc" }
        Log "In-place update complete (robocopy exit: ${D}rc)"
    }
    ${D}swapped = ${D}true
} catch {
    Log "Swap failed: ${D}_"
    if (${D}renamed -and (Test-Path ${D}backup) -and -not (Test-Path ${D}InstallDir)) {
        Rename-Item ${D}backup ${D}name
        Log "Restored backup"
    }
}

Log "Launching ZArchive.exe (swapped=${D}swapped)"
Start-Process (Join-Path ${D}InstallDir 'ZArchive.exe')

if (${D}swapped -and (Test-Path ${D}backup)) { Remove-Item ${D}backup -Recurse -Force -ErrorAction SilentlyContinue }
Remove-Item ${D}ExtractDir -Recurse -Force -ErrorAction SilentlyContinue
Log "Done"
""".trimIndent()
    }

    private fun buildMacSwapScript(): String = """
#!/usr/bin/env bash
set -uo pipefail

LOG="${'$'}TMPDIR/zarchive-updater.log"
log() { echo "$(date '+%H:%M:%S') ${'$'}*" >> "${'$'}LOG"; }

EXTRACT_DIR="${'$'}1"
INSTALL_DIR="${'$'}2"
INSTALL_PARENT="$(dirname "${'$'}INSTALL_DIR")"
INSTALL_NAME="$(basename "${'$'}INSTALL_DIR")"
BACKUP_DIR="${'$'}{INSTALL_PARENT}/${'$'}{INSTALL_NAME}-backup"

log "Swap started. ExtractDir=${'$'}EXTRACT_DIR InstallDir=${'$'}INSTALL_DIR"

# Wait for ZArchive to exit (up to 30 seconds)
DEADLINE=$(( $(date +%s) + 30 ))
while pgrep -xq "ZArchive" 2>/dev/null; do
    [ "$(date +%s)" -ge "${'$'}DEADLINE" ] && break
    sleep 0.5
done
sleep 2
log "ZArchive process exited"

SWAPPED=false

EXTRACTED=$(find "${'$'}EXTRACT_DIR" -maxdepth 2 -name "*.app" | head -1)
if [ -z "${'$'}EXTRACTED" ]; then
    log "No .app bundle found in ${'$'}EXTRACT_DIR"
else
    rm -rf "${'$'}BACKUP_DIR"
    if mv "${'$'}INSTALL_DIR" "${'$'}BACKUP_DIR"; then
        if mv "${'$'}EXTRACTED" "${'$'}INSTALL_DIR"; then
            xattr -dr com.apple.quarantine "${'$'}INSTALL_DIR" 2>/dev/null || true
            # Java zip extraction strips Unix execute bits — restore them.
            chmod -R a+x "${'$'}INSTALL_DIR/Contents/MacOS" 2>/dev/null || true
            find "${'$'}INSTALL_DIR/Contents/runtime" -path "*/bin/*" -type f -exec chmod +x {} + 2>/dev/null || true
            rm -rf "${'$'}BACKUP_DIR"
            SWAPPED=true
            log "Swap complete"
        else
            log "Move new .app failed - restoring backup"
            mv "${'$'}BACKUP_DIR" "${'$'}INSTALL_DIR" || true
        fi
    else
        log "Rename old .app failed"
    fi
fi

log "Launching ${'$'}INSTALL_DIR (swapped=${'$'}SWAPPED)"
open "${'$'}INSTALL_DIR"
rm -rf "${'$'}EXTRACT_DIR"
log "Done"
""".trimIndent()
}
