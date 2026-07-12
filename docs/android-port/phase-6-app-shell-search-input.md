# Phase 6 — Android app shell plus core search input screen

Status: Not started
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
- `src/androidMain/kotlin/MainActivity.kt`
- `src/androidMain/kotlin/ui/AndroidApp.kt` (new)
- `src/androidMain/kotlin/ui/SearchInputScreen.kt` (new)
- `src/androidMain/kotlin/ui/StatusRow.kt` (new, ported)
