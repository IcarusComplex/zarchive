# Phase 4 — SearchViewModel split: common business logic plus platform-actions shim

Status: **Done.** Verified with real end-to-end runs, not just compilation: on desktop, ran a live
82-result search, opened Settings and Search Options (confirming `SettingsStore` persistence),
and triggered "check for updates" — all unchanged from before the refactor. On the Pixel_9
emulator, constructed the real (now-shared) `SearchViewModel` with Android's stub repos +
`PlatformActions` and called `vm.search()` directly — produced results identical to both the
desktop run and Phase 3's raw `engine.runSearch` call. `createDistributable` + `desktopTest` (107
tests) + `assembleDebug` all pass together in one invocation.
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

- `src/jvmCommonMain/kotlin/ui/SearchViewModel.kt` (moved, refactored — see note below on why
  `jvmCommonMain` not `commonMain`)
- `src/jvmCommonMain/kotlin/ui/OrderStrategy.kt` (moved, see note below)
- `src/commonMain/kotlin/data/SearchListRepo.kt` / `SearchResultRepo.kt` (new interfaces)
- `src/commonMain/kotlin/data/SettingsStore.kt` (new `expect`, not in original file list — see note)
- `src/commonMain/kotlin/ui/PlatformActions.kt` (new `expect`)
- `src/desktopMain/kotlin/data/DesktopSearchListRepo.kt` / `DesktopSearchResultRepo.kt` (renamed
  from `SearchListRepo`/`SearchResultRepo`, implement the new interfaces, logic unchanged)
- `src/androidMain/kotlin/data/AndroidSearchListRepo.kt` / `AndroidSearchResultRepo.kt` (new,
  in-memory stubs)
- `src/desktopMain/kotlin/ui/PlatformActions.desktop.kt` + `DesktopUpdateInstaller.kt` (new actual +
  helper, wraps existing swap-script logic unchanged)
- `src/androidMain/kotlin/ui/PlatformActions.android.kt` (new actual, stub until Phase 12)

## Implementation notes (deviations from the plan's assumptions)

- **`SearchViewModel.kt` landed in `jvmCommonMain`, not `commonMain` as the plan assumed.** It calls
  `engine.runSearch` and `network.CardImageService` directly, and both live in `jvmCommonMain` (a
  child of `commonMain` in the source-set hierarchy, per Phase 3's actual implementation — Phase 3's
  own doc had this backwards too, now corrected). A parent source set (`commonMain`) cannot see
  symbols defined in a child (`jvmCommonMain`), so the ViewModel had to move to the same level as
  the code it calls. `SearchListRepo`/`SearchResultRepo`/`SettingsStore`/`PlatformActions` — pure
  data-contract interfaces with no networking dependency — stay in true `commonMain`, since
  `jvmCommonMain` can see anything its parent declares.
- **`OrderStrategy` enum** (previously defined inline in `ui/App.kt`, desktopMain) needed to move
  too, since the ViewModel stores/exposes the selected strategy as state. It only uses
  `ImageVector`/`Icons.Default.*` — Compose Multiplatform *common* APIs, not desktop-specific — so
  this was a pure file move into `jvmCommonMain`, no further abstraction needed.
- **New `SettingsStore` abstraction** wasn't in the plan's file list. The ViewModel has ~20 call
  sites reading/writing simple settings (`autoOpenLuckshack`, `ignoreBasicLands`, six monitor
  settings, etc.) via `AppDatabase.getSetting`/`setSetting`/`getSettingBoolean`/`setSettingBoolean`
  — all desktop-only (Exposed/H2). Added an `expect object SettingsStore` mirroring those exact four
  method signatures; desktop's `actual` delegates straight to the unchanged `AppDatabase` (zero
  behavior change), Android's `actual` is an in-memory, non-persistent stub — same "temporary stub
  until Phase 5" pattern the plan already uses for the saved-list/result repos.
- **`PlatformActions.triggerUpdateInstall` uses `suspend fun ... : Result<Unit>`**, not the four
  separate callbacks (`onReady`/`onError` etc.) the plan sketched — cleaner given
  `startDownload`'s existing `runCatching`-based structure translates directly into `Result`.
- **The desktop swap-script builders + `extractZipWithProgress` + `resolveInstallDir` moved into a
  new `DesktopUpdateInstaller.kt`** (an object, not folded directly into
  `PlatformActions.desktop.kt`) — kept the ~250 lines of PowerShell/bash script-builder strings out
  of the actual-class file for readability. `PlatformActions.desktop.kt`'s `triggerUpdateInstall`
  is a one-line delegate to `DesktopUpdateInstaller.install(...)`.
- **`warrenSearcher: BrowserBackedSearcher?` became a constructor parameter**, not something the
  ViewModel constructs itself — the original code had `private val warrenSearcher =
  BrowserSearcher(2)` directly inside the ViewModel, which would have made the shared ViewModel
  depend on the concrete desktop-only Playwright class. Desktop's `App.kt` now passes
  `network.BrowserSearcher(2)` explicitly at construction time; this is the same "platform supplies
  a factory/instance" shape the plan describes for Phase 11's Warren dispatch, arrived at one phase
  early out of necessity (this is the same underlying issue Phase 3 already solved for
  `SearchEngine.runSearch`'s browser-searcher parameter — Phase 4 just had to thread it through the
  ViewModel's constructor too).
- **`Dispatchers.Swing` replaced with `Dispatchers.Main` everywhere** in the ViewModel (its
  `CoroutineScope`, and every `withContext(Dispatchers.Main)` hop). This is not a platform
  abstraction that needed `expect`/`actual` — `kotlinx-coroutines-core`'s `Dispatchers.Main` is
  itself the common/shared API, resolved at runtime via whichever platform-specific coroutines
  artifact is on the classpath (`kotlinx-coroutines-swing` on desktop — already a dependency,
  registers itself automatically — `kotlinx-coroutines-android` on Android, added as a new
  `androidMain` dependency). Desktop behavior is provably identical (same underlying Swing EDT
  dispatcher gets selected either way).
- **Desktop repo classes renamed** `SearchListRepo`/`SearchResultRepo` → `DesktopSearchListRepo`/
  `DesktopSearchResultRepo` (implementing the new common interfaces of the same un-prefixed names)
  — internal Exposed/H2 logic is completely unchanged, only the class name and an `: InterfaceName`
  + `override` keywords were added.
- `App.kt` needed exactly two small edits (not a restructure): the `SearchViewModel()`
  no-arg instantiation became a 4-argument constructor call, and `vm.installDir != null` became
  `vm.canInstallUpdate`. No other file in `ui/App.kt` needed changes — everything else already went
  through `vm.savedLists`/`vm.savedResults` (StateFlow getters), unaffected by the repo type change
  from concrete class to interface.
