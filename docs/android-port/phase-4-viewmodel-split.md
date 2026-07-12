# Phase 4 — SearchViewModel split: common business logic plus platform-actions shim

Status: Not started
Depends on: Phase 3
Blocks: Phase 5, Phase 6, Phase 7

## Goal

`ui/SearchViewModel.kt` (1022 lines) is almost entirely portable state/orchestration logic sitting on
top of the now-common networking and data layers — the exceptions are a handful of genuinely
desktop-only actions (clipboard copy, `Desktop.getDesktop().browse/open`, the Windows/macOS
swap-script builders and `ProcessBuilder` calls around lines 477-819, crash-log file path).
Splitting this now means every subsequent UI phase (6-10) builds new Compose screens against one
shared ViewModel instead of re-deriving business logic by re-reading `App.kt`, which is the single
biggest lever for Android/desktop behavioral parity in this whole port.

## Scope

- Extract repo interfaces in `commonMain` (`SearchListRepo`, `SearchResultRepo` as interfaces with
  the exact same method/`StateFlow` shapes as today's concrete classes: `lists:
  StateFlow<List<SavedSearchList>>`, suspend `fun create/touch/rename/update/updateCards/delete`, and
  `entries: StateFlow<List<SavedResultEntry>>`, suspend `fun save/overwrite/load/delete` returning
  `LoadedResultSnapshot`). Desktop's existing `data/SearchListRepo.kt` / `data/SearchResultRepo.kt`
  (Exposed-backed) become the `desktopMain` actual implementations, unchanged internally — only
  wrapped to satisfy the new common interface. Android gets a temporary in-memory stub actual in this
  phase (not the real DB) so that Phases 6-7 (mostly pure-UI work) can proceed without waiting on the
  full Phase 5 DB build-out; Phase 5 swaps the stub for the real SQLDelight-backed implementation
  with no changes required in the ViewModel or UI layers.
- New `commonMain` `expect class PlatformActions` (or interface plus `expect fun
  platformActions()`) covering: open a URL (`Desktop.getDesktop().browse` desktop /
  `Intent.ACTION_VIEW` android), copy text to clipboard (`java.awt.datatransfer` desktop /
  `ClipboardManager` android), crash-log file path (reuses Phase 3's `PlatformPaths`), and a
  `triggerUpdateInstall(...)`-shaped hook whose desktop actual calls today's swap-script logic
  unchanged and whose android actual is a no-op placeholder until Phase 12.
- `ui/SearchViewModel.kt`'s core state (search input, running/cancelled state, results,
  `storeStatuses`, images map, saved-list/result interactions, search-options/settings flags like
  `autoOpenLuckshack`/ignore-basic-lands/partial-match, `ParseCardList`-equivalent input parsing —
  reclaiming `ParseCardListTest.kt` into `commonTest` here) moves to
  `src/commonMain/kotlin/ui/SearchViewModel.kt`, calling through the new repo interfaces and
  `PlatformActions` instead of concrete desktop APIs. The desktop-only swap-script builders
  (`buildWindowsSwapScript`/`buildMacSwapScript`) move to
  `src/desktopMain/kotlin/ui/DesktopUpdateInstaller.kt` (or similar), called from the `desktopMain`
  actual `PlatformActions`.
- `Main.kt`'s crash-logger/`AppDatabase.init()` bootstrap stays desktop-only but should now construct
  the common `SearchViewModel` via its `desktopMain` actual repo implementations — confirms the seam
  actually works end-to-end on the platform that has real behavior today.

## Key risks

This is a large, careful refactor of the most business-critical file in the app with real behavioral
risk if done sloppily (e.g. accidentally changing search-cancellation semantics, or losing the
`preferExactMatches`/exact-match filtering applied at each of the three UI-facing points noted in
CLAUDE.md). Do this as a pure refactor — no new features, no behavior changes — and lean hard on the
regression gate.

## Verification

- Full desktop regression pass: `gradlew.bat run`, exercise every existing flow (multi-card search,
  save/load a list, save/load a result snapshot, toggle search options, trigger "check for update"
  against the real GitHub API, copy results to clipboard, click a Luckshack link, open an order-list
  listing).
- `desktopTest` / `commonTest` green, including `ParseCardListTest` now running from `commonTest`.
- Android side: confirm the common `SearchViewModel` compiles and constructs against the Phase 4
  in-memory stub repos plus no-op `PlatformActions` on the placeholder Android activity (still no
  real UI yet — this is a compile/construct smoke test, not a user-facing verification).

## Critical files

- `src/commonMain/kotlin/ui/SearchViewModel.kt` (moved, refactored)
- `src/commonMain/kotlin/data/SearchListRepo.kt` / `SearchResultRepo.kt` (new interfaces)
- `src/commonMain/kotlin/ui/PlatformActions.kt` (new `expect`)
- `src/desktopMain/kotlin/ui/PlatformActions.desktop.kt` (new actual, wraps existing swap-script
  logic)
- `src/androidMain/kotlin/ui/PlatformActions.android.kt` (new actual, stub until Phase 12)
