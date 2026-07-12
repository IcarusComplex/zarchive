# Phase 5 — Android local database (SQLDelight, real implementation)

Status: **Done.** Verified on the Pixel_9 emulator with a real fresh-install + `pm clear` + full
`am force-stop` process kill: the seed list ("Aragorn & Arwen EDH (Example)", 36 cards, matching
desktop's list exactly) persisted correctly across the kill with no re-seeding. `vm.search()` and
the raw `engine.runSearch` verification hooks still produce identical results with the real DB
wired in. `createDistributable` + `desktopTest` (107 tests) + `assembleDebug` all pass together —
desktop's Exposed/H2 database is provably untouched (Android's SQLite file lives entirely inside
the emulator's own separate filesystem; no shared code path exists between
`DesktopSearchListRepo`/`AndroidSearchListRepo`).
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

- `src/androidMain/sqldelight/data/db/ZArchiveDatabase.sq` (new schema, all 5 tables in one file)
- `src/androidMain/kotlin/data/AndroidDatabase.kt` (new, driver singleton)
- `src/androidMain/kotlin/data/AndroidSearchListRepo.kt` (rewritten from Phase 4's in-memory stub)
- `src/androidMain/kotlin/data/AndroidSearchResultRepo.kt` (rewritten from Phase 4's in-memory stub)
- `src/androidMain/kotlin/data/SettingsStore.android.kt` (rewritten from Phase 4's in-memory stub)
- `src/androidMain/kotlin/data/CfThrottleStore.android.kt` (rewritten from Phase 3's no-op stub)

## Implementation notes (deviations/decisions made during execution)

- **`SettingsStore.android.kt` and `CfThrottleStore.android.kt` were also rewired to the real
  database** in this phase, not just the two saved-list/result repos the plan's file list named.
  Both were introduced as temporary stubs in earlier phases (Phase 4 for settings, Phase 3 for the
  Cloudflare-throttle store) specifically pending Phase 5's database — the plan's own prose already
  says as much ("Port ... the settings-table read/write pattern... since SearchViewModel's settings
  flags ... persist through this same mechanism"), so this isn't scope creep, just completing what
  the plan flagged as deferred here.
- **One `.sq` file for all 5 tables** (`ZArchiveDatabase.sq`), not split per-table — SQLDelight
  doesn't require a 1:1 file-to-table mapping, and one file matches how `AppDatabase.kt` (desktop)
  already keeps every table in a single file.
- **Went with a fully synchronous, desktop-mirroring `refresh()`-into-`MutableStateFlow` pattern**
  (matching `DesktopSearchListRepo`/`DesktopSearchResultRepo`'s exact shape) rather than SQLDelight's
  reactive `Query.asFlow().mapToList()` + `coroutines-extensions` mechanism the plan mentioned as an
  option. Every mutating query is followed by a synchronous re-query that updates the `StateFlow`'s
  `.value` directly — this is simpler, needed no extra dependency, and keeps Android's
  implementation behaviorally identical in shape to desktop's, not just in outcome.
- **`CfThrottleRule`'s escalation logic (two independent tiers, 2-hour re-escalation window, max
  tier 3) was hand-translated line-for-line** from `AppDatabase.kt`'s `recordCfBlock` into
  `CfThrottleStore.android.kt` — SQLite/SQLDelight has no direct equivalent of Exposed's
  `transaction { }` block, but the surrounding control flow (check existing row, decide new-event
  vs. same-event, escalate at most one bucket) is plain Kotlin, not Exposed-specific, so it ported
  directly with only the row-access syntax changing.
- Seed-list card count is **36, not "35-ish"** as loosely described in earlier planning — copied
  verbatim from `DesktopSearchListRepo.kt` and confirmed via the real Android verification run
  logging the exact count.
