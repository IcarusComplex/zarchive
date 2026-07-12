# ZArchive Android Port — Execution Plan Index

This directory holds the phased execution plan for porting ZArchive to Android, split out of the
planning-session plan doc so each phase can be picked up and executed independently. Branch:
`experiment/android-port`.

Full planning context (research findings, why each decision was made) lives in the original plan
file at `C:\Users\nicob\.claude\plans\plan-out-the-development-dazzling-goblet.md` — this index and
the per-phase files below are the durable, in-repo copy meant to be executed against.

## Context

ZArchive is a working, shipping Kotlin Compose **Desktop** app (Windows/macOS via jpackage) that
searches South African MTG stores and shows Scryfall card art. This plan exists to de-risk a port to
Android before writing code: it inventories every screen and every desktop-only dependency, and
sequences the work so risky unknowns are resolved early and the shipping desktop app never breaks
along the way.

Research (three parallel codebase-exploration passes over the full UI, the full core/data layer, and
the build/CI config, followed by a dedicated design pass that re-verified file:line specifics) found
the app is bigger and more desktop-coupled than a first read suggests: 24 distinct screens/dialogs, a
background interval-polling "Search Monitor" feature, a browser-automation "add all to cart"
mechanism, an in-app self-updater built from generated PowerShell/bash swap scripts, an embedded
H2/Exposed database, Jsoup-based HTML scraping, a Playwright-driven store (The Warren) that requires
a real JS-executing browser session, and a Ktor client pinned to the JVM-only CIO engine. None of
these have a free ride to Android — each needed a deliberate decision.

## Decisions already made (do not re-litigate during execution)

1. **Scope**: core parity first. Search, results, order lists (single-listing links only), saved
   lists/results, settings, and The Warren (via an Android WebView rewrite) are in scope. The
   background Search Monitor and the batch "add all to cart" browser automation are explicitly
   **deferred** (Phase 14 — listed so it isn't forgotten, not sequenced).
2. **Distribution**: sideloaded APK via GitHub releases, mirroring the existing Windows/macOS zip
   pattern. No Play Store.
3. **Database**: leave desktop's Exposed+H2 database completely untouched. Android gets its own
   separate local database (SQLDelight, decided in Phase 0) behind repository interfaces shaped like
   today's `SearchListRepo`/`SearchResultRepo`.
4. **Touch UX for card art**: tap a thumbnail to toggle a larger inline/overlay preview (not
   long-press, not an always-larger thumbnail). Replaces every hover-popup on desktop.
5. **The Warren**: an Android `WebView`-based rewrite of `BrowserSearcher`'s session-bootstrap +
   fetch-interception trick (not a server-side proxy) — the WebView runs the site's own JS once to
   mint a session, intercepts the resulting API response, then reuses the session for paginated
   `evaluateJavascript` calls, mirroring Playwright's `waitForResponse`/`page.evaluate` today.

## Environment (confirmed on this machine)

Android Studio installed; real SDK at `%LOCALAPPDATA%\Android\Sdk` (platform `android-36.1`,
build-tools 36.1.0/37.0.0); one AVD, `Pixel_9` (API 36, `google_apis_playstore`, x86_64), ready for
emulator verification in every phase. **Note:** the `ANDROID_HOME` env var currently points at an
incomplete SDK (`C:\Android\android-sdk`, platform-tools only) — set `local.properties` → `sdk.dir`
explicitly in Phase 1 rather than relying on that env var.

## Regression gate (applies to every phase that touches shared/desktop code)

The desktop app is shipping and must not regress. After every phase that moves or refactors code
reachable from `desktopMain`:
- `.\gradlew.bat createDistributable` must still succeed and produce a working `ZArchive.exe`
  (spot-check: launch it, run one real search).
- The existing test suite (`IsRelevantTest`, `NormalizeCardNameTest`, `OrderOptimizerTest`,
  `ParsePriceTest`, `PreferExactMatchesTest`, `ParseCardListTest`) must keep passing unchanged.

## Phases

| Phase | File | Status |
|---|---|---|
| 0 | [phase-0-risk-spikes.md](phase-0-risk-spikes.md) | **Done** — all 5 spikes GO, see `spikes/kmp-poc/DECISIONS.md` |
| 1 | [phase-1-kmp-skeleton.md](phase-1-kmp-skeleton.md) | **Done** — desktop + Android both build/run, 107 tests green |
| 2 | [phase-2-portable-code.md](phase-2-portable-code.md) | **Done** — Models.kt/OrderOptimizer.kt + tests moved, 107 tests green |
| 3 | [phase-3-networking-parsing-cache.md](phase-3-networking-parsing-cache.md) | **Done** — verified with real network calls on desktop + Android emulator |
| 4 | [phase-4-viewmodel-split.md](phase-4-viewmodel-split.md) | **Done** — verified with a real `vm.search()` run on desktop + Android emulator |
| 5 | [phase-5-android-database.md](phase-5-android-database.md) | **Done** — real persistence verified across a full process kill on Pixel_9 |
| 6 | [phase-6-app-shell-search-input.md](phase-6-app-shell-search-input.md) | Not started |
| 7 | [phase-7-results-card-art.md](phase-7-results-card-art.md) | Not started |
| 8 | [phase-8-saved-lists-results.md](phase-8-saved-lists-results.md) | Not started |
| 9 | [phase-9-settings-dialogs.md](phase-9-settings-dialogs.md) | Not started |
| 10 | [phase-10-order-lists.md](phase-10-order-lists.md) | Not started |
| 11 | [phase-11-warren-webview.md](phase-11-warren-webview.md) | Not started |
| 12 | [phase-12-distribution-update.md](phase-12-distribution-update.md) | Not started |
| 13 | [phase-13-hardening-qa.md](phase-13-hardening-qa.md) | Not started |
| 14 | [phase-14-deferred.md](phase-14-deferred.md) | Deferred (not sequenced) |

Update the Status column as each phase is picked up / completed.

## Summary dependency chain

Phase 0 (spikes) → Phase 1 (KMP skeleton) → Phase 2 (portable data/logic) → Phase 3
(networking/parsing/cache abstraction, incl. CfThrottleRules) → Phase 4 (ViewModel split + repo
interfaces) → {Phase 5 (Android DB) in parallel with Phases 6/7 (core search+results UI, unblocked
by Phase 4's temporary DB stub)} → Phase 8 (saved lists/results UI, needs both Phase 5 DB and Phase 7
results-rendering) → Phase 9 (settings/dialogs) → Phase 10 (order lists) → Phase 11 (Warren WebView,
needs Phases 3/4/6/7's plumbing already working for every other store) → Phase 12
(distribution/update, needs Phase 3's shared GitHubService and Phase 9's dialog shells) → Phase 13
(hardening, needs everything). Phase 14 is future work, deliberately not sequenced.
