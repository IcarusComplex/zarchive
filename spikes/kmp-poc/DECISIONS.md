# Phase 0 spike decisions

This module is a throwaway prototype (per `docs/android-port/phase-0-risk-spikes.md`). Nothing here
is meant to survive into the real Phase 1 restructuring verbatim — it exists to prove feasibility and
pin concrete versions before touching the shipping desktop app.

## 0E — Toolchain version matrix: RESOLVED

Pinned versions, validated on this machine (Windows, real SDK at `%LOCALAPPDATA%\Android\Sdk`,
Pixel_9 emulator API 36 + a physical device also available):

| Component | Version | Notes |
|---|---|---|
| Kotlin | 2.1.20 | Minimum required by Compose Multiplatform 1.8.x per JetBrains docs |
| Compose Multiplatform | 1.8.2 | Fully K2, officially recommended pairing with Kotlin 2.1.20 |
| AGP | 8.13.0 | Deliberately NOT the newest AGP 9.2 (released Apr 2026, requires Gradle 9.4.1) — 8.13 is the more battle-tested choice, supports compileSdk up to 36.1 |
| Gradle wrapper | 8.13 | Minimum required by AGP 8.13.0 |
| compileSdk / targetSdk | 36 | Matches the Pixel_9 AVD's `android-36.1` platform |
| minSdk | 26 | Provisional — confirm against `WebViewFeature.DOCUMENT_START_SCRIPT` requirements in the 0B spike |
| JDK | 17 | Matches existing CI (`release.yml` already uses Temurin 17) |

**Proof of feasibility:** a single Gradle module with `kotlin("multiplatform")` + `id("com.android.application")` +
`id("org.jetbrains.compose")` + `id("org.jetbrains.kotlin.plugin.compose")`, defining both
`jvm("desktop")` and `androidTarget()`, with one `commonMain` Composable (`App()`):
- `./gradlew assembleDebug` builds a real APK; installed and launched on the Pixel_9 emulator
  (`co.za.zarchive.poc`), confirmed via screenshot rendering the shared Compose text.
- `./gradlew run` launches the same shared `App()` in a real desktop JVM window, confirmed via a
  Windows screenshot showing the "KMP POC (desktop)" window.
- Both built from the exact same `commonMain` source file with zero platform-specific forking.

**Gotchas hit and fixed (worth carrying into Phase 1):**
- `android.useAndroidX=true` must be set in `gradle.properties` — AGP fails hard
  (`AndroidXDisabledJetifierDisabled`) without it as soon as any AndroidX dependency (e.g.
  `activity-compose`) is on the classpath.
- Every Kotlin file needs an explicit `package` declaration matching the AGP `namespace` /
  manifest's `.MainActivity` shorthand — a package-less `MainActivity.kt` compiles fine but throws
  `ClassNotFoundException` at runtime (APK installs, launch silently fails back to the launcher, no
  build-time error). This is an easy trap when porting `ui/App.kt`, which currently has no package
  statement at all as a plain `kotlin("jvm")` project — every moved/new file across `commonMain`/
  `desktopMain`/`androidMain` needs a real package added during Phase 1.
- `compose.desktop.application.mainClass` must be the fully-qualified class name once a package is
  added (`co.za.zarchive.poc.MainKt`, not bare `MainKt`).

**Recommendation for Phase 1:** use this exact version set. Do not jump to AGP 9.x/Gradle 9.x/Kotlin
2.2.x yet — the 8.13/2.1.20/1.8.2 combination is proven working end-to-end on this machine and is
the more conservative, better-documented choice for a first migration of a shipping app.

## 0A — Jsoup-on-Android viability: RESOLVED, primary path works — Ksoup is NOT needed

Added `org.jsoup:jsoup:1.17.2` to a new intermediate `jvmCommonMain` source set
(`dependsOn(commonMain)`; `desktopMain` and `androidMain` both `dependsOn(jvmCommonMain)`) — the
standard KMP pattern for sharing a plain-JVM (non-multiplatform-published) dependency across JVM-family
leaf targets without exposing it to `commonMain` itself.

Ported `network/Searchers.kt`'s `searchWooCommerce`'s `li.product` listing-parse branch
(desktop project, lines ~280-313) close to verbatim into `src/jvmCommonMain/kotlin/WooParserSpike.kt`,
exercising the exact selector surface used throughout `Searchers.kt`: `Jsoup.parse()`, `.select()`,
`.selectFirst()`, `.text()`, `.attr()`, `.classNames()`.

**Fixture:** real HTML captured live via `curl` from `https://shop.dracoti.co.za/?s=lightning&post_type=product`
on 2026-07-11 (an actual WooCommerce store already in ZArchive's `STORES` list) — not synthetic
markup. A ~5.5KB slice containing 3 real `li.product` listings is embedded as a Kotlin raw string in
the spike file.

**Results — all green:**
- Debug build (`assembleDebug`): parsed all 3 listings correctly (title, price text, product ID,
  availability) on the Pixel_9 emulator. Confirmed via Logcat and an on-screen Compose `Text`.
- **Release build with R8 minification (`assembleRelease`, `isMinifyEnabled = true`) and
  deliberately NO Jsoup-specific ProGuard/R8 keep rules**: still parsed all 3 listings identically.
  `minifyReceleaseWithR8` succeeded and the app ran correctly post-shrink — the charset/XML-detection
  reflection risk flagged in the phase plan did not manifest for this selector surface.
- Desktop target (`compileKotlinDesktop`, `desktopJar`) compiles cleanly with Jsoup in the shared
  `jvmCommonMain` source set — no desktop regression from adding the intermediate source set.

**Decision: use path (a) — Jsoup directly in a shared `jvmCommon` intermediate source set.**
Ksoup is not needed. Recommend Phase 3 carry the exact same `jvmCommonMain` source-set pattern
established here (rather than putting `Searchers.kt`/`CardImageService.kt`/`ScryfallSetIndex.kt`
straight into `commonMain`, which cannot depend on Jsoup at all).

**Caveat:** only one selector-heavy function was exercised end-to-end. Phase 3 should still port
store-by-store and re-test against real endpoints (per the phase plan's own risk note) — this spike
de-risks the *mechanism* (Jsoup-in-jvmCommon, survives R8), not every individual selector across all
7 store platforms.

## 0D — Ktor CIO to OkHttp engine swap: RESOLVED

Added `io.ktor:ktor-client-core` + `io.ktor:ktor-client-okhttp` to the same `jvmCommonMain` source
set as 0A (both desktop and Android share one client-construction codepath). Wrote
`src/jvmCommonMain/kotlin/KtorSpike.kt`, testing the four real behaviors the phase plan flagged as
must-confirm, all against **live production endpoints** (not mocks):

| Check | Real endpoint | Result |
|---|---|---|
| Custom `User-Agent` + `Accept` headers (Scryfall 400s without both) | `api.scryfall.com/cards/named?fuzzy=Lightning Bolt` | OK (200, returned real card JSON, name parsed back out) |
| Plain GET against a real ZArchive store | `cardcache.co.za/search/suggest.json` (Shopify) | OK (200, 17.5KB response) |
| Redirect-follow (`HttpRedirect` + `checkHttpMethod = false`, mirrors `GitHubService.downloadRelease`) | `github.com/JetBrains/compose-multiplatform/releases/latest` | OK — followed 302 to the real tag URL (`.../releases/tag/v1.11.1`), final status 200 |
| Streaming download (`bodyAsChannel()` + `readAvailable` loop, mirrors `GitHubService.downloadRelease`) | `api.scryfall.com/sets` | OK — streamed 618,800 bytes correctly |

All four passed identically on **both** the Pixel_9 emulator and the desktop JVM target, from the
same shared `runKtorSpike()` function.

**Version gotcha (worth carrying into Phase 3):** the latest Ktor at spike time, `3.5.0`, failed to
compile (`Internal compiler error` / "Module was compiled with an incompatible version of Kotlin" —
its transitive `kotlin-stdlib` was `2.3.21`, incompatible with our pinned Kotlin `2.1.20` compiler).
**Downgraded to `ktor-client-core`/`ktor-client-okhttp` `3.0.1`** (paired with
`kotlinx-coroutines-core` `1.9.0`), which compiled and ran cleanly. **Phase 3 must pin a Ktor version
whose stdlib dependency matches whatever Kotlin version Phase 1 actually locks in** — don't blindly
take "latest Ktor," verify the pairing the same way this spike had to.

**Decision:** OkHttp engine is confirmed as a safe, drop-in replacement for CIO across both desktop
and Android, with no behavioral differences found in the specific patterns `SearchEngine.kt`,
`CardImageService.kt`, and `GitHubService.kt` rely on.

## 0C — Android local DB driver (SQLDelight): RESOLVED — SQLDelight confirmed, go

Added the `app.cash.sqldelight` Gradle plugin (2.0.2) + `android-driver` (androidMain) +
`coroutines-extensions` (jvmCommonMain). Wrote `src/androidMain/sqldelight/co/za/zarchive/poc/db/PocDatabase.sq`
mirroring **all 5 tables** from the desktop project's `data/AppDatabase.kt` (`settings`,
`search_lists`, `search_list_cards`, `saved_result_snapshots`, `cf_throttle_rules`) with hand-written
CRUD queries for the list+cards relationship (the most structurally complex of the five — a
one-to-many with position ordering, matching `SearchListRepo`'s shape).

`src/androidMain/kotlin/DbSpike.kt` (`runDbSpike`) opens an `AndroidSqliteDriver`, seeds one list +
2 cards only if the table is empty (mirrors `seedIfEmpty`'s guard pattern), and reads back via
`.asFlow().mapToList(Dispatchers.IO)` — the exact bridge `AndroidSearchListRepo` would use to expose
a `StateFlow<List<SavedSearchList>>` matching desktop's shape.

**Real device test — not just a build check:**
1. Fresh install + `pm clear` → first launch: `seeded fresh (first launch since install/data-clear).
   Lists now: Phase 0C Spike List (id=1, cards=Lightning Bolt,Counterspell)`
2. `am force-stop` (full process kill, not backgrounding) + relaunch: `already had 1 list(s) —
   persistence across restart confirmed. Lists now: Phase 0C Spike List (id=1,
   cards=Lightning Bolt,Counterspell)` — identical data, no re-seed, confirming the on-disk SQLite
   file survives process death exactly as required.

**Decision:** SQLDelight 2.0.2 is confirmed for Phase 5. No blockers hit; Room fallback is not
needed. Phase 5 can proceed directly with all 5 tables using this exact schema shape (translating
Exposed's `Table` definitions column-for-column, as already done here).

## 0B — WebView fetch-interception for The Warren: RESOLVED — GO, no scope change needed

This was flagged as the single highest-uncertainty item in the whole port. It is now fully resolved
against the live production site, not a mock.

**Mechanism implemented** in `src/androidMain/kotlin/WarrenWebViewSpike.kt`:
- `WebViewCompat.addDocumentStartJavaScript` (after confirming
  `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)` — `true` on the Pixel_9/API 36
  emulator) monkey-patches `window.fetch` before any page script runs, mirroring Playwright's
  `ctx.addInitScript` in `BrowserSearcher.kt`.
- A `@JavascriptInterface` bridge reports every intercepted `/product/search` response back to
  Kotlin via a `Channel`.
- **Pagination gotcha solved cleanly:** `evaluateJavascript` does **not** await Promises — a naive
  `evaluateJavascript("fetch(...)")` returns the JSON-stringified Promise object immediately, not
  the resolved body (this is a real, documented Android WebView limitation with no equivalent on
  desktop Playwright, where `page.evaluate` genuinely awaits). Fix: don't rely on
  `evaluateJavascript`'s return value at all — fire the pagination `fetch()` call
  fire-and-forget (`webView.evaluateJavascript("fetch('$apiPath');", null)`) and let the *already-
  patched* `window.fetch` interceptor report the paginated response back through the same bridge
  channel used for page 1. This reuses one mechanism for both cases instead of fighting
  `evaluateJavascript`'s semantics.

**Real, live results** (`Lightning Bolt` → 0 results, i.e. genuinely out of stock right now, not a
mechanism failure — confirmed via raw payload logging: `{"status":200,"message":"success","data":[]}`,
a well-formed authenticated response, just empty; `Sol Ring` → 5 real results with correct fields;
`dragon` → 281 total, 20 items on page 1, **pagination confirmed working**: page 2 (offset=20)
returned 3 more real items through the same interception path):

| UA tested | Feature support | Page 1 | Pagination (page 2) |
|---|---|---|---|
| Unmodified WebView UA (real mobile UA) | `DOCUMENT_START_SCRIPT` supported | OK — 20/281 items, real JSON | OK — 3 more items via fire-and-forget `evaluateJavascript` + interception |
| Spoofed desktop-Chrome UA (matching `BrowserSearcher.kt`'s string) | supported | OK — identical results | OK — identical |

**No difference found between mobile and desktop UA** — The Warren's session-bootstrap does not
appear to gate on User-Agent, so Phase 11 doesn't need to spoof a desktop UA at all (though it's
harmless to keep doing so for consistency with the desktop implementation if preferred).

**Decision: GO.** The Android WebView rewrite of `BrowserSearcher`'s Warren logic is fully viable
using the exact mechanism the user specified (WebView, not a backend proxy). Phase 11 can proceed
as planned — no scope change (dropping Warren from the core-parity release) is needed.

**Carried into Phase 11, not yet validated here (out of scope for a throwaway spike):**
- This spike created a fresh `WebView` per attempt; Phase 11 needs one **persistent, application-
  scoped, reused** `WebView` instance (per the plan's design — a fresh session-bootstrap per search
  would be wasteful). Warm-reuse behavior across multiple sequential searches should be validated
  when Phase 11 is actually built, not assumed identical to this cold-start-only spike.
- Rotation / backgrounding / process-death-mid-search resilience (explicitly deferred to Phase 11's
  own verification step, and to Phase 13's hardening pass).
- This spike's `WebView` was never attached to the visible view hierarchy (created standalone, not
  via an `AndroidView` composable) — it worked anyway here, but Phase 11 should still attach it
  (zero-size or off-screen) per the plan's own caution about detached WebViews being unreliable,
  since a one-off spike succeeding doesn't rule out reliability issues under sustained real-world use.

---

## Summary: Phase 0 is fully resolved

All five spikes (0A/0B/0C/0D/0E) came back GO with no scope changes. Phase 1 (KMP restructuring of
the real project) can proceed using the exact versions and patterns validated in this throwaway
module: Kotlin 2.1.20 / Compose Multiplatform 1.8.2 / AGP 8.13.0 / Gradle 8.13, Jsoup + Ktor-OkHttp
3.0.1 in a shared `jvmCommonMain` source set, SQLDelight 2.0.2 for the Android-only DB, and a WebView-
based rewrite of the Warren searcher.
