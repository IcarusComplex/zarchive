package data

actual fun recordApiError(store: String, url: String, kind: String, message: String, detail: String?) {
    AppDatabase.recordApiError(store, url, kind, message, detail)
}

actual fun loadRecentApiErrors(limit: Int): List<ApiErrorEntry> =
    AppDatabase.loadRecentApiErrors(limit)

actual fun clearApiErrors() {
    AppDatabase.clearApiErrors()
}
