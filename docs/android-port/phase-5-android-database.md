# Phase 5 — Android local database (SQLDelight, real implementation)

Status: Not started
Depends on: Phase 0 (0C decision), Phase 4
Blocks: Phase 6, Phase 8 (can run in parallel with Phases 6/7 since Phase 4's stub unblocks those)

## Goal

Replace Phase 4's temporary in-memory Android repo stub with a real, persistent, Android-only
database implementing the same `SearchListRepo`/`SearchResultRepo` interfaces, per the Phase 0C
decision (SQLDelight) and the explicit user decision to leave desktop's Exposed/H2 database
completely untouched.

## Scope

- Add the SQLDelight Gradle plugin plus android-driver dependency to `androidMain`.
- New `.sq` schema files mirroring **all five** tables in `data/AppDatabase.kt` — `SearchLists`,
  `SearchListCards`, `SavedResultSnapshots`, `Settings`, and `CfThrottleRules` (hand-duplicated
  schema, not shared with Exposed, per the user's explicit decision).
- `src/androidMain/kotlin/data/AndroidSearchListRepo.kt` / `AndroidSearchResultRepo.kt` implementing
  the `commonMain` interfaces from Phase 4, backed by SQLDelight generated queries, exposing the
  identical `StateFlow<List<SavedSearchList>>` / `StateFlow<List<SavedResultEntry>>` shapes (bridge
  SQLDelight's `Query.asFlow()` to a hot `StateFlow` the same way desktop's `refresh()`-into-
  `MutableStateFlow` pattern works today).
- Port the one-time seed-list logic (`SearchListRepo.seedIfEmpty` — inserts "Aragorn & Arwen EDH"
  example list gated by a `_seed_v1_lists` settings flag) and the settings-table read/write pattern,
  since `SearchViewModel`'s settings flags (auto-open-Luckshack, ignore-basic-lands, partial-match)
  persist through this same mechanism.
- JSON encode/decode of `SavedResultSnapshots` columns (`cardsJson`, `resultsJson`,
  `excludedCardsJson`, `uncheckedLinesJson`, `pinnedListingsJson`) reuses the same
  `kotlinx.serialization.json.Json` calls as desktop's `SearchResultRepo.kt` — this part is already
  common-compatible and doesn't need reimplementation, only wiring into the new Android storage
  layer.
- An `AndroidCfThrottleRepo` (or folded into one of the above) implementing the read/write path
  `SearchEngine.kt` needs for `loadActiveCfRules()` (added to the common interface in Phase 3).

## Key risks

Mostly mechanical given the Phase 0C spike already validated the driver setup; watch for
Flow-to-StateFlow bridging correctness (must behave like desktop's synchronous refresh()-after-
every-write pattern so the UI doesn't show stale lists after a save) and for the seed-list logic
accidentally re-seeding on every launch if the settings-flag check is wrong.

## Verification

- Instrumented test or manual pass on the Pixel_9 emulator: create a saved list, force-kill the app
  process (not just background it), relaunch, confirm the list persists; same for a saved result
  snapshot.
- Confirm the seed list appears exactly once ever, even across multiple fresh installs during
  testing (uninstall/reinstall to reset).
- Confirm desktop's `AppDatabase.kt` file (`~/.zarchive/zarchive.mv.db`) and its schema are provably
  untouched by this phase (no shared code path).

## Critical files

- `src/androidMain/sqldelight/data/*.sq` (new schema, all 5 tables)
- `src/androidMain/kotlin/data/AndroidSearchListRepo.kt` (new)
- `src/androidMain/kotlin/data/AndroidSearchResultRepo.kt` (new)
- `src/androidMain/kotlin/data/AndroidDatabase.kt` (new, driver/open-helper setup)
