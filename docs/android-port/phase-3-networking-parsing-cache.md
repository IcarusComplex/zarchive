# Phase 3 — Networking, HTML parsing, and file-cache platform abstraction

Status: **Done.** Verified with real network calls, not just compilation: desktop app ran a live
"Sol Ring" search (69 real listings across multiple store platforms, card art loaded) after the
OkHttp/Jsoup/PlatformPaths refactor; the Android placeholder app ran the same `runSearch` +
`CardImageService.resolveImages` against 2 real live stores (The Cantina, Dracoti) on the Pixel_9
emulator and got matching prices/listings plus a resolved Scryfall image. `createDistributable` +
`desktopTest` (107 tests) + `assembleDebug` all pass together in one invocation.
Depends on: Phase 0 (0A/0D outcomes), Phase 2
Blocks: Phase 4, Phase 11

## Goal

Port the store-search and card-art networking stack so it can run on both desktop and Android,
applying the Phase 0 spike outcomes (Ktor OkHttp engine, Jsoup-in-shared-JVM-source-set or Ksoup,
coroutine-native concurrency primitives, and an abstracted cache-directory concept). This is the
biggest structural phase before any UI work and the one most likely to surface real bugs, since it
touches every store-scraping code path.

## Scope

- `network/Searchers.kt` (Shopify/WooCommerce/BigCommerce/OpenCart/PrestaShop searchers),
  `network/CardImageService.kt` (Scryfall art resolution plus disk cache), and
  `network/ScryfallSetIndex.kt` (set-name to code matching plus disk-cached catalogue) move into
  either `commonMain` (if 0A confirms Jsoup can sit in an intermediate JVM-family source set shared
  by both leaves) or a new intermediate source set (e.g. `src/jvmCommon/kotlin/network/`) depending
  on exactly what 0A concluded. Either way, the parsing/matching logic (title/treatment regexes,
  set-code resolution, `isRelevant`/price-parsing integration) is written once, not duplicated per
  platform.
- `engine/SearchEngine.kt`: replace `java.util.concurrent.ConcurrentHashMap` (platform cache) and any
  `Executors`-based concurrency with kotlinx.coroutines-native equivalents (a `Mutex`-guarded
  `MutableMap`, or `MutableStateFlow`-backed cache) so the orchestration logic itself is fully
  common-source-set-compatible, not just JVM-compatible.
  **Also port the `CfThrottleRules` read path** (`AppDatabase.loadActiveCfRules()`, referenced at
  `SearchEngine.kt:329`) behind the same repository-interface pattern used for saved lists/results in
  Phase 4 — this is live, actively-used per-store rate-limit escalation state (persisted per-store
  throttle tier that escalates after a 429), not optional chrome.
- `network/GitHubService.kt`: the HTTP/version-compare logic (`checkForUpdate`, `isNewerVersion`) is
  portable and reusable for Android's own update-check (Phase 12) — extract `isNewerVersion` from
  private to internal/shared visibility now so it doesn't need reimplementing later.
  `downloadRelease`'s streaming-to-file logic can stay as-is structurally (both platforms support
  `java.io.File` plus Ktor `bodyAsChannel()`), but the platform-swap mechanism built on top of it
  (Phase 12) will differ completely.
- Cache-directory abstraction (new): an `expect fun platformCacheDir(name: String): File`-style
  abstraction (or a small interface, e.g. `PlatformPaths.imagesDir`, `PlatformPaths.setsIndexFile`,
  `PlatformPaths.debugDumpDir`) replacing every direct `System.getProperty("user.home")` plus
  `File(...)` construction in `CardImageService.kt` (image cache `~/.zarchive/images/<sha1>.jpg`) and
  `ScryfallSetIndex.kt` (`~/.zarchive/sets.json`, 7-day TTL). `desktopMain` actual keeps today's
  `~/.zarchive/...` paths unchanged (zero behavior change for existing users); `androidMain` actual
  wires to `Context.filesDir`/`Context.cacheDir` (needs a way to obtain `Context` — likely an Android
  `Application` subclass holding a static/singleton reference, set up in this phase even though the
  rest of the Android app shell isn't built yet).
- Client construction: single shared `HttpClient(OkHttp)` factory (per 0D) used by
  `SearchEngine.kt`'s shared client and `CardImageService.kt`'s separate Scryfall-header client.

## Key risks

This phase is where "the plan sounded fine until real HTML hit it" problems surface — e.g. a store's
response subtly differing between CIO and OkHttp engines (compression, header casing), or a
`.select()`/`.attr()` call that 0A didn't happen to exercise. Mitigate by porting store-by-store and
re-running each store's real search against production endpoints before moving to the next.
`BrowserSearcher.kt` (Playwright/Warren) is explicitly not touched in this phase — it stays
desktop-only until Phase 11 replaces its role for Android via WebView.

## Verification

- For every ported store searcher, run a real search (`gradlew.bat run`, search a known-in-stock
  card) on desktop and diff results against pre-Phase-3 behavior (same prices, same availability,
  same set-hint extraction — cross-check a card known to exercise each of the 10 per-platform
  set-hint rows in CLAUDE.md's table).
- Card art resolution: confirm the disk cache still lands at `~/.zarchive/images/` with identical
  filenames (sha1-of-name), and that the Scryfall batch/fuzzy/child-set fallback chain
  (`CardImageService.resolveImages`'s 7-step pipeline) still produces the same art for a card list
  that's known to exercise fuzzy fallback.
- GitHub update-check: confirm `checkForUpdate`/`isNewerVersion` still correctly detects "no update
  available" against the current released version.
- No Android UI exists yet, but confirm the moved network code at least compiles against the
  placeholder Android app shell from Phase 1 (a temporary "tap to run one test search, print to
  Logcat" button is a reasonable throwaway verification hook for this phase specifically, deleted
  once Phase 6 supersedes it).

## Critical files

- `src/jvmCommonMain/kotlin/network/Searchers.kt` (moved)
- `src/jvmCommonMain/kotlin/network/CardImageService.kt` (moved)
- `src/jvmCommonMain/kotlin/network/ScryfallSetIndex.kt` (moved)
- `src/jvmCommonMain/kotlin/network/GitHubService.kt` (moved, `isNewerVersion` widened to `internal`)
- `src/jvmCommonMain/kotlin/engine/SearchEngine.kt` (moved, concurrency primitives swapped,
  CfThrottle path ported, `BrowserBackedSearcher` abstraction added)
- `src/commonMain/kotlin/data/PlatformPaths.kt` (new `expect`)
- `src/commonMain/kotlin/data/CfThrottleRule.kt` (new, data class + `expect fun` pair)
- `src/commonMain/kotlin/network/BrowserBackedSearcher.kt` (new interface)
- `src/androidMain/kotlin/data/PlatformPaths.android.kt` (new)
- `src/desktopMain/kotlin/data/PlatformPaths.desktop.kt` (new)
- `src/androidMain/kotlin/androidapp/ZArchiveApplication.kt` (new, Android `Context` holder)

## Implementation notes (deviations/decisions made during execution)

- **Correction:** all 5 files landed in the new intermediate `jvmCommonMain` source set, **not**
  `commonMain` itself. The 0A spike's validated pattern (Jsoup + Ktor in a shared JVM-family source
  set) was implemented here as `jvmCommonMain` (`dependsOn(commonMain)`;
  `desktopMain`/`androidMain` both `dependsOn(jvmCommonMain)`), and
  `Searchers.kt`/`CardImageService.kt`/`ScryfallSetIndex.kt`/`GitHubService.kt`/`SearchEngine.kt`
  all moved there. `commonMain` itself stays Jsoup-free (Compose
  Multiplatform's actual multiplatform targets, if ever added beyond desktop+Android, couldn't
  depend on a JVM-only jar there).
- **`BrowserBackedSearcher` interface (new, `commonMain`)** wasn't in the phase's original file
  list — needed because `SearchEngine.kt`'s `checkStore`/`runSearch` directly typed a parameter as
  the concrete desktop-only `BrowserSearcher` (Playwright) and, in `runSearch`, directly
  *instantiated* one (`BrowserSearcher(parallelism)`) when no shared instance was supplied. Moving
  `SearchEngine.kt` to shared code required both the type and the construction to become
  pluggable: `BrowserBackedSearcher` is the shared interface (desktop's `BrowserSearcher` now
  implements it), and `runSearch` gained a `createBrowserSearcher: ((Int) -> BrowserBackedSearcher)?`
  factory parameter for the local-instance case. `ui/SearchViewModel.kt` needed **zero changes** —
  it always passes a `sharedBrowserSearcher`, so the factory path is never exercised there; this is
  exactly the same shape of "platform-provided override hook" the plan describes for Phase 11's
  Warren dispatch, arrived at one phase early out of necessity.
- **`CfThrottleRule` + two `expect fun`s (new, `commonMain`)** — also not in the original file list.
  `SearchEngine.kt` read/wrote Cloudflare throttle state via `AppDatabase.loadActiveCfRules()`/
  `recordCfBlock()` (Exposed/H2, desktop-only). Moved just the `CfThrottleRule` data class into
  `commonMain` (same `data` package as `AppDatabase.kt`, so no import needed there) and added
  `expect fun loadActiveCfThrottleRules()`/`recordCfThrottleBlock(...)`; desktop's `actual` delegates
  to the unchanged `AppDatabase` functions, Android's `actual` is a no-op stub (`emptyMap()`/no-op)
  until Phase 5 wires up its own database — same stub-until-Phase-5 pattern the plan already uses
  for the saved-list/result repos in Phase 4.
- **`ConcurrentHashMap` replacement**: used `Mutex`-guarded plain `Map`/`Set` for `platformCache`/
  `cfBlockedStores` (straightforward). For `PerHostRateLimiter`'s per-host `Semaphore` map, the
  mutex guards **only the get-or-create lookup**, not the actual `withPermit`/`delay`/`block()`
  execution — a coarser lock there would have serialized every HTTP request across every store,
  regressing the whole point of per-host concurrent throttling.
- **OkHttp engine + custom trust manager**: CIO's `engine { https { trustManager = ... } }` config
  shape doesn't exist on `OkHttpConfig`. Replaced with building a permissive `SSLContext` from the
  existing `X509TrustManager` and passing it to OkHttp's `Builder` via
  `engine { config { sslSocketFactory(...); hostnameVerifier { _, _ -> true } } }` — both the cert
  trust *and* hostname verification needed to be relaxed, since OkHttp checks them independently
  (CIO's single `trustManager` implicitly covered both).
- **Ktor version left at 2.3.10** (not bumped to 3.0.1 as the Phase 0D spike used) — the spike only
  needed a newer Ktor because it was a fresh, isolated module; here, swapping `ktor-client-cio` for
  `ktor-client-okhttp` at the *same* existing 2.3.10 version worked cleanly with no compatibility
  issues, which is the lower-risk change (engine swap and version bump are independent variables;
  no need to do both at once).
- **`GitHubService.checkForUpdate`/`isNewerVersion` moved wholesale**, including the desktop-specific
  `System.getProperty("os.name")` Windows/macOS asset-picking logic — left functionally unchanged
  (it simply won't match "windows"/"mac" on Android and falls back to the first `.zip` asset found,
  which is wrong for Android but harmless since no Android build exists yet; Phase 12 adds the real
  Android asset filter). Only fixed a nullability warning (`.orEmpty()`) surfaced by Android's
  slightly different `System.getProperty` API stub, and widened `isNewerVersion` from `private` to
  `internal`.
- **Real bug caught by testing the full regression gate *together*, not just individually**: the
  `generateBuildInfo` task-dependency wiring from Phase 1 (`tasks.matching { it.name.startsWith(
  "compileKotlin") }`) never actually matched Android's compile task (named `compileDebugKotlinAndroid`,
  which doesn't start with `"compileKotlin"`) — a latent bug since Phase 1 that only surfaced as a
  Gradle task-validation error when `createDistributable`, `desktopTest`, and `assembleDebug` were
  run together in one invocation (order-dependent). Fixed by matching on task **type**
  (`tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask<*>>()`) instead of a
  name-string prefix — robust to every current and future target/variant automatically. Worth
  remembering for later phases: always re-run the full gate as one combined invocation before
  calling a phase done, not just each task individually.
- Also caught and fixed by real-device testing (not compile-time): forgot to register
  `androidapp.ZArchiveApplication` in `AndroidManifest.xml`'s `<application android:name=...>` —
  compiled fine, but crashed at runtime with `UninitializedPropertyAccessException` the moment
  shared code touched `PlatformPaths` on Android. A `MainActivity`-side temporary verification hook
  (real `runSearch` + `CardImageService.resolveImages` call, logged to Logcat — deleted once Phase 6
  builds the real search screen) is what caught this; a placeholder-text-only screen would not have.
