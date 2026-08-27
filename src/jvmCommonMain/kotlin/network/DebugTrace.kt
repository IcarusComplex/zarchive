package network

import data.PlatformPaths
import java.io.FileWriter
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val TRACE_DEBUG = System.getProperty("mtg.debug") == "true"
private val traceMutex = Mutex()
private var traceWriter: FileWriter? = null
private val TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
private val FILENAME_TS_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS")
private const val TRACE_RETENTION_MS = 48L * 60 * 60 * 1000

private fun newTraceFile() =
    PlatformPaths.debugDumpDir.resolve("trace_${LocalDateTime.now().format(FILENAME_TS_FORMAT)}.log")

// Deletes trace_*.log files older than 48h -- otherwise switching from one shared, truncated
// trace.log to one file per search (see traceReset) would let them accumulate forever, since
// Search Monitors call runSearch (and so traceReset) on their own schedule same as a manual search.
private fun pruneOldTraceFiles() {
    val cutoff = System.currentTimeMillis() - TRACE_RETENTION_MS
    PlatformPaths.debugDumpDir.listFiles { f -> f.name.startsWith("trace_") && f.name.endsWith(".log") }
        ?.filter { it.lastModified() < cutoff }
        ?.forEach { it.delete() }
}

/**
 * Opens a fresh, uniquely-named trace file for a new search -- called once at the top of
 * [engine.runSearch]. No-op outside debug builds (`mtg.debug=true`, i.e. `gradlew run`).
 *
 * Each search gets its OWN file (`trace_<timestamp>.log`) rather than sharing/truncating one
 * `trace.log` -- confirmed live (Aug 2026): a background Search Monitor fired mid-session and its
 * own runSearch call truncated the trace of the manual search actually being debugged, silently
 * discarding it. Old trace files (>48h) are pruned on each reset so they don't accumulate forever.
 */
suspend fun traceReset(header: String) {
    if (!TRACE_DEBUG) return
    traceMutex.withLock {
        runCatching {
            traceWriter?.close()
            pruneOldTraceFiles()
            traceWriter = FileWriter(newTraceFile(), false).apply {
                write("=== $header ===\n")
                flush()
            }
        }.onFailure { e -> println("[TRACE] Failed to start new trace file: ${e.message}") }
    }
}

/**
 * Appends one timestamped line to the current search's trace file (see [traceReset]). No-op
 * outside debug builds.
 *
 * This exists so runtime behavior -- actual concurrency (are the card/candidate semaphores really
 * only letting N requests through at once?), actual pacing (is the per-host rate limiter's delay
 * really being honored between requests?), and actual response content -- can be observed directly
 * instead of inferred from reading the throttling/concurrency code, which has previously hidden
 * real bugs (see the duplicate Cloudflare-block log-entry bug: two racing coroutines both reached
 * the same catch block before either updated the shared "this store is blocked" state).
 */
suspend fun traceLog(tag: String, message: String) {
    if (!TRACE_DEBUG) return
    traceMutex.withLock {
        runCatching {
            val ts = LocalDateTime.now().format(TS_FORMAT)
            val writer = traceWriter ?: FileWriter(newTraceFile(), true).also { traceWriter = it }
            writer.write("[$ts] [$tag] $message\n")
            writer.flush()
        }.onFailure { e -> println("[TRACE] Failed to write trace line: ${e.message}") }
    }
}
