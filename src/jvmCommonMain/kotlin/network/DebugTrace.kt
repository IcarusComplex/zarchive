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

private fun traceFile() = PlatformPaths.debugDumpDir.resolve("trace.log")

/**
 * Truncates and re-opens the debug trace log for a fresh search -- called once at the top of
 * [engine.runSearch]. No-op outside debug builds (`mtg.debug=true`, i.e. `gradlew run`).
 */
suspend fun traceReset(header: String) {
    if (!TRACE_DEBUG) return
    traceMutex.withLock {
        runCatching {
            traceWriter?.close()
            traceWriter = FileWriter(traceFile(), false).apply {
                write("=== $header ===\n")
                flush()
            }
        }.onFailure { e -> println("[TRACE] Failed to reset trace.log: ${e.message}") }
    }
}

/**
 * Appends one timestamped line to `~/zarchive-debug/trace.log`. No-op outside debug builds.
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
            val writer = traceWriter ?: FileWriter(traceFile(), true).also { traceWriter = it }
            writer.write("[$ts] [$tag] $message\n")
            writer.flush()
        }.onFailure { e -> println("[TRACE] Failed to write trace line: ${e.message}") }
    }
}
