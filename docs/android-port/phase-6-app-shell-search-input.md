# Phase 6 — Android app shell plus core search input screen

Status: **Done.** Verified on the Pixel_9 emulator: a real multi-store search ("sol ring") completed
end-to-end through the full ported networking stack — "Done — 49 listings found", all 18 stores
(including The Warren, gracefully no-op without a WebView searcher) showing DONE with correct
per-store counts in the expandable store grid. A second, heavier 4-card query ("goblin"/"brainstorm"/
"wrath"/"tutor") was used to catch the search mid-flight: the button correctly switched to "Stop", a
progress bar and "Checked 4 / 80 cards" text rendered live, and tapping Stop correctly cancelled the
job ("Cancelled" status, button reverted to "Search", live grid cleared). Tab switching to Order
Lists and Search Monitors renders their Phase-10/Phase-14 placeholders correctly, with the tapped tab
correctly highlighted. `createDistributable` + `desktopTest` + `assembleDebug` all pass together in
one combined invocation; the desktop `.exe` was launched standalone afterward to confirm zero
regression.
Depends on: Phase 4 (stub DB is enough to start; Phase 5 real DB can land in parallel)
Blocks: Phase 7, Phase 8, Phase 9, Phase 10, Phase 11

## Goal

Stand up the real Android app shell (navigation/tab state, top-level scaffold) and the first real
user-facing screen: entering a card list and kicking off a search, with live per-store progress.
This is the first phase where a user can actually do something on Android, and it establishes the
screen-level Compose conventions (top app bar instead of window chrome, IME actions instead of
keyboard shortcuts) that the rest of the UI phases follow.

## Scope (desktop reference to Android target)

- `ui/App.kt`'s `App()` root composable (window/tab-state shell, lines ~127-252) and `FolderTabs`
  (lines 1993-2029, 3-tab nav) to new `src/androidMain/kotlin/ui/AndroidApp.kt` root composable
  hosted by `MainActivity.kt`, using a standard Compose `Scaffold` plus `TopAppBar` instead of
  `TitleBar` (lines 253-291, custom drag bar/minimize/maximize — not ported, replaced with a normal
  Material3 top bar showing the app name plus a settings icon). `PreReleaseBadge` (1089-1148) ports
  as a small badge in the new top bar.
- `LeftPanel`/`PanelSearch` (lines 292-391, 1025-1071: multi-line query input, Search/Stop button) to
  new Android search input screen/pane. The desktop Alt+Enter-to-search shortcut has no Android
  equivalent — replace with a Compose `KeyboardOptions(imeAction = ImeAction.Search)` plus
  `keyboardActions` on the multi-line text field, plus the existing visible Search button (already
  present today, so this isn't purely a shortcut-loss — the button remains the primary affordance).
  Shift+Enter-for-newline stays natural on a multi-line field regardless of platform.
- `StatusRow`/`StoreStatusChip` (lines 1808-1941: live search progress, per-store status grid) ports
  close to verbatim — this is Compose state UI with no AWT dependency, backed by
  `SearchViewModel.storeStatuses` (already common as of Phase 4).
- `ModalScrim` (1345-1356, shared dialog backdrop) ports as a small shared Compose util, reusable by
  every later dialog phase.
- Wire the Android `MainActivity` to construct the common `SearchViewModel` using Phase 5's real
  Android repos (no longer the Phase 4 stub) and Phase 4's `PlatformActions.android` actual.

## Key risks

Low-to-medium — this is "normal Compose Android work," the main risk is scope discipline (don't
accidentally pull in results/order-list rendering here; keep this phase to input+shell+progress
only, per the phase boundary).

## Verification

On the Pixel_9 emulator: launch the app, type/paste a multi-line card list, tap Search, confirm the
per-store status grid updates live (PENDING to CHECKING to DONE) matching desktop behavior for the
same query, confirm Stop/cancel works mid-search. No results table needed yet to consider this phase
done (Phase 7 renders results) — verification is scoped to "search starts, progresses, and can be
cancelled."

## Critical files

- `src/main/kotlin/ui/App.kt` (reference only — `App()`, `FolderTabs`, `LeftPanel`/`PanelSearch`,
  `StatusRow`/`StoreStatusChip`, `ModalScrim`, lines noted above)
- `src/androidMain/kotlin/androidapp/MainActivity.kt` (rewritten — constructs the real
  `SearchViewModel` with Android's Phase 5 repos/`PlatformActions`, replacing prior throwaway
  verification hooks)
- `src/androidMain/kotlin/ui/AndroidApp.kt` (new)
- `src/androidMain/kotlin/ui/SearchInputScreen.kt` (new)
- `src/androidMain/kotlin/ui/StatusRow.kt` (new, ported)
- `src/androidMain/kotlin/ui/ModalScrim.kt` (new, ported verbatim — not yet used by any dialog, but
  in place for later phases)
- `src/androidMain/kotlin/ui/theme/Theme.kt` (new — duplicated "Arcane Market Ledger" color palette
  constants plus a `ZArchiveTheme` wrapper)

## Implementation notes (deviations/decisions made during execution)

- **Search input is a fixed-height (160dp) card at the top of the Search Results tab, not a
  permanent side panel.** Desktop's `LeftPanel` is a permanent 240dp-wide sidebar — phones don't have
  that screen real estate. This is a deliberate structural adaptation for every future phase to build
  around, not a straight port.
- **`ImeAction.Search` was deliberately *not* bound** to the multi-line query field, contrary to the
  plan's original suggestion. Binding a keyboard "search" IME action to this field would replace the
  soft keyboard's Enter key with a dedicated search action, breaking the "one card per line" input
  format (Enter must still insert a newline, matching desktop's Shift+Enter-for-newline /
  Alt+Enter-for-search split — Android's single-Enter-key soft keyboard has no clean equivalent split,
  so the visible Search button remains the only way to trigger a search).
- **New `ui/theme/Theme.kt`** duplicates desktop's private color constants from `App.kt` rather than
  sharing them — desktop's palette lives as `private val`s inside a desktop-only file, so duplication
  was simpler than extracting a shared module for ~15 color constants at this stage.
- **Tab-wrap bug found and fixed during verification:** the "Search Monitors" folder-tab label
  initially wrapped to two lines on the Pixel_9's screen width. Fixed by wrapping the tab `Row` in
  `Modifier.horizontalScroll(rememberScrollState())` and adding `maxLines = 1, softWrap = false` to
  each tab's label `Text`.
- **`TopAppBar` required `@OptIn(ExperimentalMaterial3Api::class)`** on `AndroidApp` — Material3's
  top app bar is still an experimental API as of the Compose Multiplatform version pinned in Phase 0E.
- Functional verification deliberately used real production store endpoints (not mocked) to prove the
  entire `jvmCommonMain` networking/parsing stack — not just the new UI — genuinely executes
  correctly on Android, since this is the first phase exercising that code path from real Android UI
  interaction rather than a throwaway Logcat hook.
