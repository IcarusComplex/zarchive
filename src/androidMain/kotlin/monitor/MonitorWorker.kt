package monitor

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import data.STORES
import data.SearchResult
import data.SettingsStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import network.AndroidWarrenSearcher

private val workerJson = Json { ignoreUnknownKeys = true }

/**
 * Android's sole monitor execution path (see monitor/MonitorPlatform.android.kt --
 * runsMonitorLoopInProcess = false). Scheduled/cancelled by MonitorScheduler, reading the exact
 * same SettingsStore-backed config (monitorCardQuery/monitorDisabledStores/monitorSeenListingsJson)
 * the desktop in-process loop and the (unused-on-Android) SearchViewModel fields read/write, so
 * state stays consistent regardless of which UI screen last touched it.
 */
class MonitorWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val query = SettingsStore.getSetting("monitorCardQuery", "")
        val ignoreBasicLands = SettingsStore.getSettingBoolean("ignoreBasicLands", true)
        val disabledStores = SettingsStore.getSetting("monitorDisabledStores", "")
            .split(",").filter { it.isNotBlank() }.toSet()
        val enabledStores = STORES.keys.filter { it !in disabledStores }.toSet()

        val seenKeys: MutableSet<String> = runCatching {
            workerJson.decodeFromString<List<String>>(SettingsStore.getSetting("monitorSeenListingsJson", "[]"))
        }.getOrDefault(emptyList()).toMutableSet()

        val browserSearcher = AndroidWarrenSearcher()
        val newHits = mutableListOf<SearchResult>()
        // A check that throws (network blip, WebView hiccup, etc.) must still land a status/history
        // entry -- previously an uncaught exception skipped straight past the monitorLastCheckedAt/
        // monitorLastCheckStatus writes below, so a failed run looked identical to one that never
        // ran at all, and there was no record of it anywhere.
        var failure: Throwable? = null
        try {
            val foundCount = runMonitorCheck(
                query = query,
                enabledStores = enabledStores,
                ignoreBasicLands = ignoreBasicLands,
                browserSearcher = browserSearcher,
                seenKeys = seenKeys,
                onHit = { newHits.add(it) },
            )
            if (foundCount > 0) {
                SettingsStore.setSetting("monitorSeenListingsJson", workerJson.encodeToString(seenKeys.toList()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failure = e
        } finally {
            // WebView.destroy() (inside close()) must run on the same thread the WebView was
            // created on (Main) -- doWork() itself runs on a background dispatcher.
            withContext(Dispatchers.Main) { browserSearcher.close() }
        }

        val statusText = when {
            failure != null -> "Check failed: ${failure.message ?: failure::class.simpleName}"
            newHits.isEmpty() -> "No new listings found"
            else -> "${newHits.size} new listing${if (newHits.size == 1) "" else "s"} found"
        }
        val now = System.currentTimeMillis()
        SettingsStore.setSetting("monitorLastCheckedAt", now.toString())
        SettingsStore.setSetting("monitorLastCheckStatus", statusText)
        recordMonitorCheck(now, statusText, newHits.size, failure == null)
        // Every run, not just ones with hits -- see MonitorCheckBus's doc comment. Without this, a
        // foregrounded app's "last checked" status line only ever caught up on the next launch or
        // resume, even though the history trail (recordMonitorCheck above) had already moved on.
        MonitorCheckBus.notifyChecked()

        if (newHits.isNotEmpty()) {
            postMonitorHitNotification(applicationContext, newHits)
            MonitorHitBus.emit(newHits)
        }
        return if (failure != null) Result.retry() else Result.success()
    }
}
