package ui

import androidx.compose.runtime.*
import data.SearchResult
import data.STORES
import data.luckshackSearchUrl
import engine.runSearch
import kotlinx.coroutines.*
import kotlinx.coroutines.swing.Swing
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

    // Persisted user setting: whether a Luckshack search auto-opens in the browser.
    private val prefs = java.util.prefs.Preferences.userRoot().node("zarchive")
    private var autoOpenLuckshackState by mutableStateOf(prefs.getBoolean("autoOpenLuckshack", false))
    var autoOpenLuckshack: Boolean
        get() = autoOpenLuckshackState
        set(value) {
            autoOpenLuckshackState = value
            prefs.putBoolean("autoOpenLuckshack", value)
        }

    val results = mutableStateListOf<SearchResult>()

    // Listing title → local image file path. Keyed by the listing's own title so each
    // distinct card (e.g. "Shadowspear" vs "Mardu Shadowspear") gets its own art.
    val images = mutableStateMapOf<String, String>()

    private var searchJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Swing + SupervisorJob())

    private var ignoreBasicLandsState by mutableStateOf(prefs.getBoolean("ignoreBasicLands", true))
    var ignoreBasicLands: Boolean
        get() = ignoreBasicLandsState
        set(value) {
            ignoreBasicLandsState = value
            prefs.putBoolean("ignoreBasicLands", value)
        }

    private var includeWarrenState by mutableStateOf(prefs.getBoolean("includeWarren", false))
    var includeWarren: Boolean
        get() = includeWarrenState
        set(value) {
            includeWarrenState = value
            prefs.putBoolean("includeWarren", value)
        }

    private var earlyAccessState by mutableStateOf(prefs.getBoolean("earlyAccess", false))
    var earlyAccess: Boolean
        get() = earlyAccessState
        set(value) {
            earlyAccessState = value
            prefs.putBoolean("earlyAccess", value)
        }

    var updateInfo by mutableStateOf<UpdateInfo?>(null)
    var updateCheckState by mutableStateOf(UpdateCheckState.IDLE)

    // null = idle, 0–1 = progress, -1 = error
    var downloadProgress by mutableStateOf<Float?>(null)
    var downloadError    by mutableStateOf<String?>(null)
    private var downloadJob: Job? = null

    // Resolved once at startup — null in dev mode or if the exe is not found next to the jar.
    val installDir: File? = resolveInstallDir()

    fun startDownload(info: UpdateInfo, onReadyToInstall: () -> Unit) {
        val url        = info.downloadUrl ?: return
        val targetDir  = installDir       ?: return
        downloadProgress = 0f
        downloadError    = null
        downloadJob = scope.launch(Dispatchers.IO) {
            runCatching {
                val tmp = File(System.getProperty("java.io.tmpdir"), "ZArchive-update").also { it.mkdirs() }
                val zip = tmp.resolve("ZArchive-update.zip")
                downloadRelease(url, zip) { p ->
                    scope.launch(Dispatchers.Swing) { downloadProgress = p }
                }
                // Write updater script next to the zip so it can clean itself up.
                val script = tmp.resolve("zarchive-updater.ps1")
                script.writeText(buildUpdaterScript())
                ProcessBuilder(
                    "powershell.exe", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass",
                    "-File", script.absolutePath,
                    "-ZipPath", zip.absolutePath,
                    "-InstallDir", targetDir.absolutePath,
                ).start()
                withContext(Dispatchers.Swing) { onReadyToInstall() }
            }.onFailure { e ->
                withContext(Dispatchers.Swing) {
                    downloadProgress = -1f
                    downloadError = e.message ?: "Download failed"
                }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        downloadProgress = null
        downloadError = null
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

        val storesToSearch = if (includeWarren) STORES else STORES.filterKeys { it != "The Warren" }

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
                        val newTitles = rows.mapNotNull { it.title }.filter { requestedTitles.add(it) }
                        if (newTitles.isNotEmpty()) {
                            imageJobs += scope.launch(Dispatchers.IO) {
                                val resolved = imageService.resolveImages(newTitles)
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
                // Packaged layout: ZArchive/app/zarchive-X.Y.Z.jar  → parent.parent = ZArchive/
                val loc    = object {}.javaClass.protectionDomain?.codeSource?.location ?: return null
                val appDir = File(loc.toURI()).parentFile                               ?: return null
                appDir.parentFile?.takeIf { it.resolve("ZArchive.exe").exists() }
            }.getOrNull()
        }

        fun buildUpdaterScript(): String {
            // \$ in a Kotlin string literal produces a literal dollar sign.
            val D = "\$"
            return """
param([string]${D}ZipPath, [string]${D}InstallDir)

${D}deadline = [datetime]::Now.AddSeconds(30)
while ((Get-Process -Name 'ZArchive' -ErrorAction SilentlyContinue) -and ([datetime]::Now -lt ${D}deadline)) {
    Start-Sleep -Milliseconds 500
}

${D}parent  = Split-Path ${D}InstallDir -Parent
${D}name    = Split-Path ${D}InstallDir -Leaf
${D}backup  = Join-Path ${D}parent (${D}name + '-backup')
${D}extract = Join-Path ${D}env:TEMP 'ZArchive-update-extract'

try {
    if (Test-Path ${D}extract) { Remove-Item ${D}extract -Recurse -Force }
    Expand-Archive -Path ${D}ZipPath -DestinationPath ${D}extract -Force
    ${D}extracted = Get-ChildItem ${D}extract -Directory | Select-Object -First 1
    if (Test-Path ${D}backup) { Remove-Item ${D}backup -Recurse -Force }
    Rename-Item ${D}InstallDir (${D}name + '-backup')
    Move-Item ${D}extracted.FullName ${D}InstallDir
    Start-Process (Join-Path ${D}InstallDir 'ZArchive.exe')
    Remove-Item ${D}backup -Recurse -Force -ErrorAction SilentlyContinue
} catch {
    if ((Test-Path ${D}backup) -and -not (Test-Path ${D}InstallDir)) {
        Rename-Item ${D}backup ${D}name
    }
}

Remove-Item ${D}ZipPath -Force         -ErrorAction SilentlyContinue
Remove-Item ${D}extract -Recurse -Force -ErrorAction SilentlyContinue
""".trimIndent()
        }

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
