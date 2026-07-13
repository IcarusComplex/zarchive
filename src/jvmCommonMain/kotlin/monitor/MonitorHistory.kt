package monitor

import data.SettingsStore
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One completed monitor check, newest-first. Recorded by both execution paths (desktop's
 * SearchViewModel.runMonitorCheck and Android's MonitorWorker) so a user who can't tell whether
 * background checks are actually happening can open a trail of the last [HISTORY_LIMIT] runs --
 * including failed ones, which previously vanished silently (an exception mid-check skipped the
 * monitorLastCheckedAt/monitorLastCheckStatus writes entirely, so a run that errored out looked
 * identical to a run that never happened).
 */
@Serializable
data class MonitorCheckEntry(
    val timestamp: Long,
    val status: String,
    val hitCount: Int,
    val success: Boolean,
)

private val historyJson = Json { ignoreUnknownKeys = true }
private const val HISTORY_KEY = "monitorCheckHistoryJson"
private const val HISTORY_LIMIT = 10

fun loadMonitorHistory(): List<MonitorCheckEntry> = runCatching {
    historyJson.decodeFromString<List<MonitorCheckEntry>>(SettingsStore.getSetting(HISTORY_KEY, "[]"))
}.getOrDefault(emptyList())

fun recordMonitorCheck(timestamp: Long, status: String, hitCount: Int, success: Boolean) {
    val updated = (listOf(MonitorCheckEntry(timestamp, status, hitCount, success)) + loadMonitorHistory())
        .take(HISTORY_LIMIT)
    SettingsStore.setSetting(HISTORY_KEY, historyJson.encodeToString(updated))
}
