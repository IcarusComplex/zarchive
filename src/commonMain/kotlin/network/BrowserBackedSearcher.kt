package network

import data.SearchResult

/**
 * Abstraction over a browser-session-backed store searcher — a store (currently only The Warren)
 * whose search API requires a real JS-executing browser session before it becomes callable.
 * Desktop's `BrowserSearcher` (Playwright) implements this; Android's WebView-based equivalent
 * (Phase 11) will too. `engine.SearchEngine` depends only on this interface, never on a concrete
 * platform implementation, so it can live in shared code without pulling in Playwright.
 */
interface BrowserBackedSearcher : AutoCloseable {
    suspend fun search(base: String, card: String): List<SearchResult>
}
