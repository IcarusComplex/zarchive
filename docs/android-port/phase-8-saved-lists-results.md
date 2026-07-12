# Phase 8 — Saved lists and saved results management

Status: Not started
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
- `src/androidMain/kotlin/ui/SavedListsScreen.kt` (new)
- `src/androidMain/kotlin/ui/SavedResultsScreen.kt` (new)
