# Phase 11 — The Warren: Android WebView searcher

Status: **Done.** Verified on the Pixel_9 emulator with real, live production searches. A "sol ring"
search returned a genuine (if unhelpful) result: The Warren's own search backend returned 5 fuzzy
substring matches (e.g. "Solemn Offering" — matches because "offe-RING" contains "ring"), all
correctly filtered out by the existing, already-tested `isRelevant()` function, yielding 0 relevant
hits — proving the pipeline is live and correctly discriminating, not just returning empty by
accident. A "swords to plowshares" search then returned a genuine relevant hit: "Swords to
Plowshares - Judge Gift Cards 2013" at R925,00, 2 in stock, which rendered correctly in the results
list sorted by price alongside every other store, and the store status grid showed "The Warren 1/1"
DONE. Full combined regression gate (`createDistributable` + `desktopTest` + `assembleDebug`) passes
together; desktop `.exe` launched standalone to confirm zero regression (the WebView dependency is
`androidMain`-only, no desktop impact).
Depends on: Phase 0B (feasibility spike), Phase 3 (SearchEngine dispatch), Phase 6/7 (results
plumbing), Phase 9 (ZArchiveApplication holder)
Blocks: none downstream (leaf feature phase)

## Goal

Implement the Android-only replacement for `network/BrowserSearcher.kt`'s Playwright-based Warren
search, using the WebView fetch-interception approach validated in Phase 0B, now wired into the real
`SearchEngine` orchestration alongside the other (already-ported) store searchers.

## Scope

- New `src/androidMain/kotlin/network/AndroidWarrenSearcher.kt`: an application-scoped, persistently-
  alive `WebView` (owned by `ZArchiveApplication` from Phase 9, or a dedicated singleton holder)
  running on the main/UI thread (per WebView's threading constraint — the coroutine call into it from
  `SearchEngine`'s concurrent per-store searches must hop to `Dispatchers.Main` for the WebView calls
  specifically, unlike every other store searcher which runs freely on `Dispatchers.IO`). Ports the
  logic of `BrowserSearcher.searchWarren`/`parseWarrenApiJson` (same URL construction —
  `https://thewarren.co.za/search?f=1&q=<encoded>&t=1&c=3&sc=7&is=1&o=0`, same pagination contract
  via `/openapi/1.0.0fe/client/product/search?limit=20&offset=...`, same JSON field extraction:
  `product_name`, `stock_available`, `discount_price`/`actual_price`, `product_id`,
  `mtg_product_variant_id`) — this JSON-parsing logic is already common-compatible
  (kotlinx.serialization.json) and can be lifted close to verbatim from `BrowserSearcher.kt` even
  though the transport (WebView vs Playwright) is completely different. Implements the
  `WebViewFeature.DOCUMENT_START_SCRIPT` plus `@JavascriptInterface` bridge plus
  `evaluateJavascript` pagination pattern proven in Phase 0B, with the UA override also validated
  there.
- Wire this into `SearchEngine.kt`'s store dispatch (currently `BrowserSearcher` is invoked for "the
  one Playwright-driven store" — the Android build needs its dispatch to route Warren to
  `AndroidWarrenSearcher` instead, likely via an expect/actual "special-case searcher" hook or a
  platform-provided `Map<String, suspend (String) -> List<SearchResult>>` override that
  `SearchEngine` consults before falling back to the generic per-platform searchers).
- The Warren still needs to appear correctly in the per-store status grid (Phase 6) and results
  (Phase 7) — no special-casing needed there since it produces ordinary `SearchResult`s.

## Key risks

Everything flagged in Phase 0B applies at full scale here: main-thread-only execution could
serialize what's concurrent on desktop (Playwright's multi-lane parallelism has no clean Android
WebView equivalent — a single WebView instance realistically handles one Warren search at a time; if
a search query list is large, this could visibly slow down just the Warren lane relative to other
stores, which is acceptable but should be an explicit, observed trade-off, not a surprise). The
WebView must stay attached to the view hierarchy — confirm it doesn't get torn down by configuration
changes (rotation) or process-death-while-backgrounded mid-search.

## Verification

On Pixel_9: run a search for a card known to be in stock at The Warren, confirm results appear with
correct price/availability/URL, matching what the desktop Playwright path returns for the same card.
Run a large multi-card search and observe (not necessarily fix) how the single-WebView-lane
constraint affects total search time relative to other stores. Background the app mid-Warren-search
and foreground it again; rotate the device mid-search; confirm no crash and a reasonable outcome
(either the search completes or fails gracefully, not a hang).

## Critical files

- `src/main/kotlin/network/BrowserSearcher.kt` (reference only — URL/pagination/JSON contract,
  ported close to verbatim for the JSON field-extraction logic)
- `src/androidMain/kotlin/network/AndroidWarrenSearcher.kt` (new)
- `src/androidMain/kotlin/androidapp/MainActivity.kt` (constructs `AndroidWarrenSearcher()` and
  passes it as `SearchViewModel`'s `warrenSearcher` argument)
- `build.gradle.kts` / `gradle/libs.versions.toml` (added `androidx.webkit:webkit` dependency to
  `androidMain`)

## Implementation notes (deviations/decisions made during execution)

- **No `SearchEngine.kt` dispatch changes were needed at all** — the plan anticipated needing a new
  "platform-special-cased searcher" hook, but Phase 3/4 had already built exactly this seam:
  `SearchEngine.runSearch`/`checkStore` accept a `sharedBrowserSearcher: BrowserBackedSearcher?`
  parameter and copy `store = storeName` onto whatever it returns. `AndroidWarrenSearcher` just
  implements that existing interface and gets passed into `SearchViewModel`'s constructor in
  `MainActivity.kt`, identical in shape to how desktop passes `BrowserSearcher(2)`. This is simpler
  than the plan assumed because the abstraction had already matured past what Phase 11's own doc
  (written before Phase 3/4 were implemented) anticipated.
- **A real, unvalidated risk flagged in the Phase 0B spike notes did manifest**: "This spike's
  WebView was never attached to the visible view hierarchy... a one-off spike succeeding doesn't
  rule out reliability issues under sustained real-world use." The first real test silently returned
  empty results with no crash and no error — the detached WebView's JS timers/fetch resolution were
  being throttled by Chromium since it considered the WebView not visible/foregrounded. Fixed with
  explicit `webView.onResume()` + `webView.resumeTimers()`, called once after creation and again at
  the start of every `search()` call.
- **`SearchEngine.checkStore` calls `browserSearcher.search()` once per card, sequentially** (a
  plain `for` loop, not concurrent `async`) for the `BROWSER` platform, so `AndroidWarrenSearcher`'s
  internal `Mutex` is a defensive guard, not a real contention point in practice — confirmed by
  reading `SearchEngine.kt` rather than assumed.
- **Diagnosing "why does this return 0 results" required temporarily logging the raw intercepted
  JSON body**, which revealed The Warren's own search backend does fuzzy substring matching
  server-side (a "sol ring" query matched "Solemn Offering" because "ring" is a substring of
  "Offering") — not a bug in the Android port, but the same `isRelevant()` whole-word-match filter
  already documented in CLAUDE.md correctly rejecting irrelevant results, exactly as it does for
  every other store. This diagnostic logging (a per-response body dump and a very chatty
  `onPageFinished` callback firing ~40 times per navigation, likely from the React app's client-side
  routing) was removed after confirming the fix worked; only clean production code remains.
