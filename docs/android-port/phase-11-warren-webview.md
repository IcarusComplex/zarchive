# Phase 11 — The Warren: Android WebView searcher

Status: Not started
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

- `src/main/kotlin/network/BrowserSearcher.kt` (reference only — URL/pagination/JSON contract, not
  ported directly)
- `src/androidMain/kotlin/network/AndroidWarrenSearcher.kt` (new)
- `src/commonMain/kotlin/engine/SearchEngine.kt` (dispatch hook for the platform-special-cased
  searcher)
