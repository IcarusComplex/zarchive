# Phase 2 — Move pure-Kotlin portable code into commonMain

Status: **Done.** `createDistributable`, `assembleDebug`, and `desktopTest` all pass; 107 tests green
(same count as Phase 1's baseline — 81 across the 5 moved suites + 26 in the untouched
`ParseCardListTest`).
Depends on: Phase 1
Blocks: Phase 3, Phase 10 (OrderOptimizer)

## Goal

Migrate the code that is already 100% portable today, verbatim, with zero behavior change, to build
early confidence in the KMP skeleton using the lowest-risk content before touching anything with I/O
or networking. This is the first proof that the restructuring actually lets both platforms share
code.

## Scope

- `data/Models.kt` to `src/commonMain/kotlin/data/Models.kt`: `SearchResult`, `Platform`, `STORES`,
  `parsePrice`, `isRelevant`, `normalizeCardName`, `KNOWN_PLATFORMS`. The one non-portable-looking
  line — `java.net.URLEncoder.encode(card, "UTF-8")` inside `luckshackSearchUrl` (Models.kt line 50)
  — does **not** need `expect`/`actual`: `java.net.URLEncoder` is part of the Android API surface
  too (Android is JVM-family, unlike a true JS/Native KMP target), so it can move to `commonMain`
  unchanged as long as the intermediate/leaf source sets are both JVM-family. Confirm this explicitly
  in this phase rather than assuming it (a quick compile check is sufficient — no spike needed).
- `data/OrderOptimizer.kt` to `src/commonMain/kotlin/data/OrderOptimizer.kt`: `cheapestPlan`,
  `fewestStoresPlan` — pure functions over `SearchResult`, no I/O, moves unchanged.
- Corresponding tests move to `src/commonTest/kotlin/data/`: `IsRelevantTest.kt`,
  `NormalizeCardNameTest.kt`, `OrderOptimizerTest.kt`, `ParsePriceTest.kt`,
  `PreferExactMatchesTest.kt` (all pure-function tests over the moved code — should run unmodified
  under `commonTest` via the desktop test target at minimum; Android unit test execution of the same
  suite is a nice-to-have proof, not required this phase). `ParseCardListTest.kt` stays in
  `desktopTest` for now (it tests parsing logic that currently lives in `ui/SearchViewModel.kt`,
  which hasn't moved yet — revisit in Phase 4).
- Everything that references these types elsewhere in `desktopMain` (`network/*.kt`,
  `engine/SearchEngine.kt`, `ui/App.kt`, `ui/SearchViewModel.kt`) needs only an import-path update,
  no logic change.

## Key risks

Low. The main risk is mechanical — import breakage cascading through the still-desktop-only
remainder of the codebase — not a design risk.

## Verification

- `./gradlew desktopTest` (now exercising `commonTest` plus `desktopTest`) passes identically to
  Phase 1's baseline.
- `.\gradlew.bat createDistributable` plus manual smoke test (one search against a real store)
  confirms zero desktop behavior change.
- No Android-side verification needed yet beyond confirming `androidMain` still compiles against the
  new `commonMain` types (it doesn't consume them yet, but nothing should break).

## Critical files

- `src/commonMain/kotlin/data/Models.kt` (moved)
- `src/commonMain/kotlin/data/OrderOptimizer.kt` (moved)
- `src/commonTest/kotlin/data/*.kt` (moved)

## Implementation notes (deviations from the plan's assumptions)

- **No import-path updates were needed anywhere.** The plan assumed consuming files
  (`network/*.kt`, `ui/App.kt`, etc.) would need import-path changes — they don't, because the
  package declaration (`package data`) didn't change, only the source set. Kotlin resolves
  `import data.SearchResult` identically regardless of which source set the class physically lives
  in, since `desktopMain`/`androidMain` both depend on `commonMain` and see its compiled classes on
  the classpath automatically. Zero consumer files were touched.
- **The 5 moved test files could NOT move "unmodified" as the plan assumed** — they used
  `org.junit.jupiter.api.Test` / `org.junit.jupiter.api.Assertions.*`, which is JVM-only (JUnit 5),
  not available in `commonTest`. Converted mechanically to `kotlin.test` (`import kotlin.test.*`
  replacing both JUnit imports) — every assertion call used (`assertTrue`, `assertFalse`,
  `assertEquals`, `assertNotNull`, `assertNull`) has a matching-signature `kotlin.test` equivalent,
  so only the two import lines changed per file, no assertion-call rewrites needed. Build wiring:
  `commonTest` gets `implementation(kotlin("test"))`; `desktopTest` changed from
  `implementation(kotlin("test"))` to `implementation(kotlin("test-junit5"))` (the bridge that lets
  `kotlin.test.Test`-annotated tests actually run under the JUnit 5 platform runner
  `useJUnitPlatform()` already configures). Verified: identical pass counts before/after (81 tests
  across the 5 converted files, unchanged).
- `implementation(libs.kotlinx.serialization.json)` moved from `desktopMain` to `commonMain`
  (`Models.kt`'s `@Serializable` needs it there now); still visible to `desktopMain`/`androidMain`
  transitively via the source-set hierarchy, so nothing downstream needed re-adding it.
- Android-side (`assembleDebug`) verified building successfully with the moved code, confirming the
  `java.net.URLEncoder` line needs no `expect`/`actual` — full production-level confirmation, not
  just the "quick compile check" the plan suggested would be sufficient.
