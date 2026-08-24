package data

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

/**
 * Formats a plain-text report suitable for pasting into a GitHub issue or support message —
 * same shape/spirit as the existing crash-log report (see `PlatformActions.crashLogFile`).
 */
fun formatErrorLogForReport(entries: List<ApiErrorEntry>, appVersion: String, platform: String): String = buildString {
    appendLine("ZArchive v$appVersion ($platform)")
    appendLine("API error log -- ${entries.size} entries")
    appendLine()
    if (entries.isEmpty()) {
        appendLine("(no errors recorded)")
    } else {
        entries.forEach { e ->
            val ts = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(e.timestamp), ZoneId.systemDefault())
                .format(TS_FORMAT)
            appendLine("[$ts] ${e.store} -- ${e.kind} -- ${e.message} (${e.url})")
        }
    }
}
