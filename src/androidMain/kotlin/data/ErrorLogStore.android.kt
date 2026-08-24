package data

// Mirrors AppDatabase.kt's recordApiError()/loadRecentApiErrors()/clearApiErrors() (500-row cap,
// oldest-first eviction) using the api_error_logs table added in ZArchiveDatabase.sq / 4.sqm.
private val queries get() = AndroidDatabase.instance.zArchiveDatabaseQueries

private const val API_ERROR_LOG_CAP = 500L

actual fun recordApiError(store: String, url: String, kind: String, message: String) {
    queries.insertError(
        timestamp = System.currentTimeMillis(),
        store = store,
        url = url,
        kind = kind,
        message = message,
    )
    val count = queries.countErrors().executeAsOne()
    if (count > API_ERROR_LOG_CAP) {
        queries.selectStaleErrorIds(count - API_ERROR_LOG_CAP).executeAsList().forEach { id ->
            queries.deleteErrorById(id)
        }
    }
}

actual fun loadRecentApiErrors(limit: Int): List<ApiErrorEntry> =
    queries.selectRecentErrors(limit.toLong()).executeAsList().map { row ->
        ApiErrorEntry(
            timestamp = row.timestamp,
            store = row.store,
            url = row.url,
            kind = row.kind,
            message = row.message,
        )
    }

actual fun clearApiErrors() {
    queries.clearErrors()
}
