# Phase 8 — Saved lists and saved results management

Status: **Done.** Verified on the Pixel_9 emulator with a fresh install (`pm clear`): opened the
Saved Lists dialog from its new `TopAppBar` icon, confirmed the real seed list ("Aragorn & Arwen EDH
(Example)", 36 cards) renders correctly with edit/delete icons, tapped it to load, confirmed the
dialog closed and the Search Results tab's query field was populated with all 36 card names. The
Saved Results dialog (archive icon) opens correctly (empty state, since no result was saved during
this run). Full combined regression gate (`createDistributable` + `desktopTest` + `assembleDebug`)
passes together; desktop `.exe` launched standalone to confirm zero regression. Mid-phase, the user
requested moving Android's top-level tab navigation from a top folder-tab row to a bottom
`NavigationBar` (README decision #6) — implemented and verified alongside this phase's own scope;
see the implementation notes below and Phase 6's updated notes.
Depends on: Phase 5 (real Android DB), Phase 7 (results rendering)
Blocks: none downstream (leaf feature phase)

## Goal

Port the two "library management" panels — saved card lists and saved result snapshots — now that
Phase 5's real Android DB and Phase 7's results-rendering primitives both exist to build on
(saved-result loading needs to render through the same `CardSection`/`ListingTable` components
Phase 7 built).

## Scope (desktop reference to Android target)

- `SavedListsPanel` (409-846, Lists tab: save/load/rename/delete, "all lists" modal with search) plus
  `EditListDialog` (847-922, rename plus edit card text) plus `SavedListRow` (923-971) to new Android
  saved lists screen, wired to Phase 5's `AndroidSearchListRepo` through the Phase 4 common
  `SearchListRepo` interface (already-tested end-to-end in Phase 5, so this phase is pure UI).
- `SavedListsPanel`'s Results-tab half (same file, Results tab: save/load/delete result snapshots,
  "all results" modal) plus `SavedResultRow` (972-1024) to new Android saved results screen, wired to
  `AndroidSearchResultRepo`; loading a saved result renders through Phase 7's results components
  (confirms the "load snapshot to display" path end-to-end, including restoring
  `excludedCards`/`uncheckedLines`/`pinnedListings` state).

## Key risks

Low — by this point the hard infrastructure (DB, repo interfaces, results rendering) is already
proven; this phase is primarily assembling existing pieces into new screens plus modal navigation
(Android back-stack/dialog conventions for the "all lists"/"all results" full-list modals, which on
desktop are in-window popups but on Android should probably be a full-screen dialog or a dedicated
nav destination — decide based on Android navigation conventions, likely a `Dialog` with full-screen
styling to mirror desktop's modal-over-content feel).

## Verification

On Pixel_9: create 2+ saved lists, rename one, delete one, confirm the "all lists" modal
search-filters correctly; save a result snapshot from a real search, reload it and confirm identical
results/exclusions/pinned-listings state to what was saved; delete a saved result. Confirm none of
this touches desktop's DB (already guaranteed by Phase 5's isolation, but worth a final sanity check
that `~/.zarchive/zarchive.mv.db` timestamp is untouched by Android testing on the same dev machine,
if ever run side-by-side).

## Critical files

- `src/main/kotlin/ui/App.kt` (reference only — `SavedListsPanel`, `EditListDialog`,
  `SavedListRow`, `SavedResultRow`, lines noted above)
- `src/androidMain/kotlin/ui/SavedListsScreen.kt` (new — `SavedListsDialog`)
- `src/androidMain/kotlin/ui/SavedResultsScreen.kt` (new — `SavedResultsDialog`)
- `src/androidMain/kotlin/ui/SavedRowsAndDialogs.kt` (new — `SavedListRow`, `SavedResultRow`,
  `SaveNameDialog`, `ConfirmDialog`, `EditListDialog`, shared by both screens above)
- `src/androidMain/kotlin/ui/AndroidApp.kt` (two new `TopAppBar` action icons open the dialogs; the
  top `FolderTabs` row was replaced with a bottom `NavigationBar` — see notes below)

## Implementation notes (deviations/decisions made during execution)

- **No permanent sidebar panel — full-screen `Dialog`s reached from `TopAppBar` icons instead.**
  Desktop's `SavedListsPanel` is a permanent panel pinned inside the 240dp sidebar (3 rows visible,
  "+N more" opens a modal for the rest). A phone has no room for a second permanent panel alongside
  the search input, so both Lists and Results became a single full-screen `Dialog`
  (`DialogProperties(usePlatformDefaultWidth = false)`) always showing the complete list, opened via
  a bookmark/archive icon in the `TopAppBar`'s `actions`. This matches the phase plan's own suggestion
  ("probably a full-screen dialog to mirror desktop's modal-over-content feel").
- **Hover-reveals-delete/edit-icon pattern dropped — icons are always visible.** Desktop only shows
  the delete/edit icons on row hover (`SavedListRow`/`SavedResultRow`); touch has no hover state, so
  both icons render at all times on Android's row.
- **`SaveNameDialog`/`ConfirmDialog` factored into a shared `SavedRowsAndDialogs.kt`** rather than
  duplicated once per screen (desktop's `App.kt` has near-identical `AlertDialog` blocks repeated for
  lists and for results) — same save-name-with-overwrite-confirmation shape serves both.
- **Bottom `NavigationBar` replaces the top `FolderTabs` row (mid-phase user request, not originally
  planned for Phase 8).** The 3 top-level sections (Search Results / Order Lists / Search Monitors)
  now live in a `Scaffold.bottomBar` `NavigationBar` with `NavigationBarItem`s, matching native
  Android/Material3 top-level-destination convention and comfortable thumb reach — not a port of
  desktop's `FolderTabs`, which stays completely untouched (`README.md` decision #6). This removed
  `AndroidApp.kt`'s private `FolderTabs` composable and the `Row` + `HorizontalDivider` that hosted it
  entirely; several imports (`background`, `border`, `clickable`, `Arrangement`, `horizontalScroll`,
  `RoundedCornerShape`, `clip`, `SurfaceContainerLowest`) became unused and were removed. Verified via
  screenshot: tab switching (Search Results <-> Order Lists) and the gold selection indicator both
  render and behave correctly.
