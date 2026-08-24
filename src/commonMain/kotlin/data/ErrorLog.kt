package data

/** One recorded API failure/backoff, surfaced to the user via the Diagnostics dialog. */
data class ApiErrorEntry(
    val timestamp: Long,
    val store: String,
    val url: String,
    val kind: String, // "BACKOFF" (Cloudflare/429) or "ERROR" (everything else)
    val message: String,
)

/** Records an API error/backoff so it survives past the current session. Desktop delegates to
 * `AppDatabase`; Android to its own SQLDelight-backed store. */
expect fun recordApiError(store: String, url: String, kind: String, message: String)

/** Loads the most recent recorded errors, newest first. */
expect fun loadRecentApiErrors(limit: Int = 200): List<ApiErrorEntry>

/** Clears every recorded error. */
expect fun clearApiErrors()
