package ui

import androidx.compose.runtime.*
import data.AppDatabase
import data.SearchListRepo
import data.SearchResult
import data.STORES
import data.luckshackSearchUrl
import engine.runSearch
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.swing.Swing
import network.BrowserSearcher
import network.CardImageService
import network.UpdateInfo
import network.checkForUpdate
import network.downloadRelease
import java.io.File

enum class StoreStatus { PENDING, CHECKING, DONE }
enum class UpdateCheckState { IDLE, CHECKING, UP_TO_DATE, UPDATE_FOUND }

class SearchViewModel {
    var query by mutableStateOf("")
    var isSearching by mutableStateOf(false)
    var statusText by mutableStateOf("")
    var completedStores by mutableStateOf(0)
    var searchedCards by mutableStateOf<List<String>>(emptyList())
    var totalStores by mutableStateOf(STORES.size)
    val storeStatuses = mutableStateMapOf<String, StoreStatus>()
    // How many card-queries have returned for each store (i.e. how many out of searchedCards.size).
    val storeCardCounts = mutableStateMapOf<String, Int>()

    private var autoOpenLuckshackState by mutableStateOf(AppDatabase.getSettingBoolean("autoOpenLuckshack", false))
    var autoOpenLuckshack: Boolean
        get() = autoOpenLuckshackState
        set(value) { autoOpenLuckshackState = value; AppDatabase.setSettingBoolean("autoOpenLuckshack", value) }

    val results = mutableStateListOf<SearchResult>()

    // Listing title → local image file path. Keyed by the listing's own title so each
    // distinct card (e.g. "Shadowspear" vs "Mardu Shadowspear") gets its own art.
    val images = mutableStateMapOf<String, String>()

    private var searchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    // Persists across search calls so the Playwright browser and its CF-clearance session
    // stay alive — avoids a cold Cloudflare solve on every search click. 2 lanes handles
    // most multi-card Warren searches without spawning a third idle browser instance.
    private val warrenSearcher = BrowserSearcher(2)

    private var ignoreBasicLandsState by mutableStateOf(AppDatabase.getSettingBoolean("ignoreBasicLands", true))
    var ignoreBasicLands: Boolean
        get() = ignoreBasicLandsState
        set(value) { ignoreBasicLandsState = value; AppDatabase.setSettingBoolean("ignoreBasicLands", value) }

    private var enabledStoresState: Set<String> by mutableStateOf(loadEnabledStores())
    var enabledStores: Set<String>
        get() = enabledStoresState
        set(value) {
            enabledStoresState = value
            val disabled = STORES.keys.filter { it !in value }
            AppDatabase.setSetting("disabledStores", disabled.joinToString(","))
        }

    fun setStoreEnabled(store: String, enabled: Boolean) {
        enabledStores = if (enabled) enabledStores + store else enabledStores - store
    }

    private fun loadEnabledStores(): Set<String> {
        val raw = AppDatabase.getSetting("disabledStores", "").ifBlank { null }
        return if (raw != null) {
            val disabled = raw.split(",").filter { it.isNotBlank() }.toSet()
            STORES.keys.filter { it !in disabled }.toSet()
        } else {
            val warrenOn = AppDatabase.getSettingBoolean("includeWarren", true)
            STORES.keys.filter { it != "The Warren" || warrenOn }.toSet()
        }
    }

    private var earlyAccessState by mutableStateOf(AppDatabase.getSettingBoolean("earlyAccess", false))
    var earlyAccess: Boolean
        get() = earlyAccessState
        set(value) { earlyAccessState = value; AppDatabase.setSettingBoolean("earlyAccess", value) }

    // ── Saved search lists ─────────────────────────────────────────────────────
    val searchListRepo = SearchListRepo()
    val savedLists: StateFlow<List<data.SavedSearchList>> get() = searchListRepo.lists

    fun saveSearchList(name: String) {
        val cards = query.lines().map { it.trim() }.filter { it.isNotBlank() }
        if (cards.isEmpty()) return
        scope.launch(Dispatchers.IO) { searchListRepo.create(name, cards) }
    }

    fun loadSearchList(list: data.SavedSearchList) {
        query = list.cards.joinToString("\n")
        scope.launch(Dispatchers.IO) { searchListRepo.touch(list.id) }
    }

    fun deleteSearchList(id: Int) {
        scope.launch(Dispatchers.IO) { searchListRepo.delete(id) }
    }

    fun saveEditedList(id: Int, name: String, cards: List<String>) {
        scope.launch(Dispatchers.IO) { searchListRepo.update(id, name, cards) }
    }

    var updateInfo by mutableStateOf<UpdateInfo?>(null)
    var updateCheckState by mutableStateOf(UpdateCheckState.IDLE)

    // null = idle, 0–1 = progress, -1 = error
    var downloadProgress by mutableStateOf<Float?>(null)
    var downloadPhase    by mutableStateOf("Downloading update…")
    var downloadError    by mutableStateOf<String?>(null)
    private var downloadJob: Job? = null

    // Resolved once at startup — null in dev mode or if the exe is not found next to the jar.
    val installDir: File? = resolveInstallDir()

    fun startDownload(info: UpdateInfo, onReadyToInstall: () -> Unit) {
        val url       = info.downloadUrl ?: return
        val targetDir = installDir       ?: return
        val isMac     = System.getProperty("os.name", "").lowercase().contains("mac")
        downloadProgress = 0f
        downloadPhase    = "Downloading update…"
        downloadError    = null
        downloadJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(System.getProperty("java.io.tmpdir"), "ZArchive-update").also { it.mkdirs() }
                val zip        = tmp.resolve("ZArchive-update.zip")
                val extractDir = tmp.resolve("ZArchive-update-extract")

                // Phase 1: download
                // Guard: only update if not cancelled (cancel sets downloadProgress = null)
                downloadRelease(url, zip) { p ->
                    scope.launch(Dispatchers.Swing) { if (downloadProgress != null) downloadProgress = p }
                }

                // Phase 2: extract in-process so we can report progress before closing
                withContext(Dispatchers.Swing) { downloadProgress = 0f; downloadPhase = "Extracting…" }
                extractZipWithProgress(zip, extractDir) { p ->
                    scope.launch(Dispatchers.Swing) { if (downloadProgress != null) downloadProgress = p }
                }
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
                withContext(Dispatchers.Swing) { onReadyToInstall() }
            }.onFailure { e ->
                withContext(Dispatchers.Swing) {
                    downloadProgress = -1f
                    downloadError = e.message ?: "Update failed"
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadProgress = null
        downloadPhase    = "Downloading update…"
        downloadError    = null
    }

    fun checkForUpdates() {
        if (updateCheckState == UpdateCheckState.CHECKING) return
        updateCheckState = UpdateCheckState.CHECKING
        scope.launch(Dispatchers.IO) {
            val info = runCatching { checkForUpdate(earlyAccess) }.getOrNull()
            withContext(Dispatchers.Swing) {
                updateInfo = info
                updateCheckState = if (info != null) UpdateCheckState.UPDATE_FOUND else UpdateCheckState.UP_TO_DATE
            }
        }
    }

    fun dismissUpdateStatus() { updateCheckState = UpdateCheckState.IDLE }

    fun search() {
        val cards = parseCardList(query, ignoreBasicLands)
        if (cards.isEmpty()) return

        val storesToSearch = STORES.filterKeys { it in enabledStores }

        searchedCards = cards
        searchJob?.cancel()
        results.clear()
        images.clear()
        completedStores = 0
        totalStores = storesToSearch.size
        storeStatuses.clear()
        storeStatuses.putAll(storesToSearch.keys.associateWith { StoreStatus.PENDING })
        storeCardCounts.clear()
        isSearching = true
        statusText = "Starting search…"

        // Luckshack isn't scraped (Cloudflare). If the user opted in, open each card's Luckshack
        // search in their browser now; otherwise it's available as a click-through link in the UI.
        if (autoOpenLuckshack) openLuckshackSearches(cards)

        searchJob = scope.launch {
            val imageService = CardImageService()
            val requestedTitles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
            val imageJobs = java.util.concurrent.CopyOnWriteArrayList<Job>()

            try {
                // Resolve art for the (clean) searched card names up front. These are keyed by the
                // card name and act as a reliable fallback in the summary / order list / thumbnails
                // whenever a messy store listing title fails to resolve its own specific printing.
                imageJobs += scope.launch(Dispatchers.IO) {
                    val resolved = imageService.resolveImages(cards.filter { requestedTitles.add(it) })
                    if (resolved.isNotEmpty()) withContext(Dispatchers.Swing) { images.putAll(resolved) }
                }

                runSearch(
                    cards = cards,
                    stores = storesToSearch,
                    sharedBrowserSearcher = warrenSearcher,
                    onProgress = { storeName ->
                        withContext(Dispatchers.Swing) {
                            storeStatuses[storeName] = StoreStatus.CHECKING
                            statusText = "Checking $storeName…"
                        }
                    },
                    onResults = { rows ->
                        withContext(Dispatchers.Swing) {
                            results.addAll(rows)
                            // Each call to onResults is one card resolved at one store.
                            rows.firstOrNull()?.store?.takeIf { it.isNotBlank() }?.let { store ->
                                storeCardCounts[store] = (storeCardCounts[store] ?: 0) + 1
                            }
                        }
                        val newTitleHints = rows.mapNotNull { r -> r.title?.let { it to r.setHint } }
                            .filter { (title, _) -> requestedTitles.add(title) }
                            .toMap()
                        if (newTitleHints.isNotEmpty()) {
                            imageJobs += scope.launch(Dispatchers.IO) {
                                val resolved = imageService.resolveImages(newTitleHints)
                                if (resolved.isNotEmpty()) {
                                    withContext(Dispatchers.Swing) { images.putAll(resolved) }
                                }
                            }
                        }
                    },
                    onStoreComplete = { storeName ->
                        withContext(Dispatchers.Swing) {
                            storeStatuses[storeName] = StoreStatus.DONE
                            completedStores++
                            statusText = "Checked $completedStores / $totalStores stores"
                        }
                    },
                )
                imageJobs.joinAll()
            } finally {
                imageService.close()
            }
            isSearching = false
            statusText = "Done — ${results.count { it.title != null }} listings found"
        }
    }

    companion object {
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

        private fun extractZipWithProgress(zip: File, dest: File, onProgress: (Float) -> Unit) {
            dest.deleteRecursively()
            dest.mkdirs()
            java.util.zip.ZipFile(zip).use { zf ->
                val entries = zf.entries().toList()
                val total   = entries.size.toFloat().coerceAtLeast(1f)
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

        fun buildWindowsSwapScript(): String {
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

        fun buildMacSwapScript(): String = """
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

        private val BASIC_LANDS = setOf("plains", "island", "swamp", "mountain", "forest",
            "wastes", "snow-covered plains", "snow-covered island", "snow-covered swamp",
            "snow-covered mountain", "snow-covered forest")

        // Parses plain "one card per line" format AND section-headed deck lists:
        //   [CREATURES]
        //   1 Shadowspear
        //   2x Lightning Bolt
        fun parseCardList(input: String, ignoreBasicLands: Boolean): List<String> =
            input.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("[") }
                .mapNotNull { line ->
                    // Strip leading quantity (e.g. "1 ", "2x ", "1x ")
                    val name = line.removePrefix(Regex("""^\d+[xX]?\s+""")) ?: line
                    name.trim().ifEmpty { null }
                }
                .filter { !ignoreBasicLands || it.lowercase() !in BASIC_LANDS }
                .distinct()

        private fun String.removePrefix(regex: Regex): String =
            regex.find(this)?.let { substring(it.value.length) } ?: this
    }

    private fun openLuckshackSearches(cards: List<String>) {
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (java.awt.Desktop.isDesktopSupported() && desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
                cards.forEach { card -> runCatching { desktop.browse(java.net.URI(luckshackSearchUrl(card))) } }
            }
        }
    }

    fun cancel() {
        searchJob?.cancel()
        isSearching = false
        statusText = "Cancelled"
    }
}
