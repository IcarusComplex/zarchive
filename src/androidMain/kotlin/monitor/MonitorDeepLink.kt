package monitor

import android.content.Intent
import data.SearchResult
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

private val deepLinkJson = Json { ignoreUnknownKeys = true }

// What a tapped monitor notification should open: a single hit's own detail modal, or (for a
// check that found several at once) the shared monitor-alerts modal listing all of them.
sealed class PendingMonitorNav {
    data class SingleHit(val result: SearchResult) : PendingMonitorNav()
    data object OpenAlerts : PendingMonitorNav()
}

fun parsePendingMonitorNav(intent: Intent?): PendingMonitorNav? {
    intent ?: return null
    intent.getStringExtra(EXTRA_MONITOR_HIT_JSON)?.let { json ->
        return runCatching { PendingMonitorNav.SingleHit(deepLinkJson.decodeFromString<SearchResult>(json)) }.getOrNull()
    }
    if (intent.getBooleanExtra(EXTRA_MONITOR_OPEN_ALERTS, false)) return PendingMonitorNav.OpenAlerts
    return null
}
