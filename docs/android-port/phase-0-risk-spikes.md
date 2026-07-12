# Phase 0 — Risk spikes and version/library decisions

Status: **Done** — all five spikes resolved GO, no scope changes. Full write-up with real-device
evidence: `spikes/kmp-poc/DECISIONS.md` (throwaway prototype module, not meant to be merged as-is).
Depends on: nothing (first phase)
Blocks: Phase 1 (version matrix), Phase 3 (Jsoup/Ktor decisions), Phase 5 (DB library), Phase 11
(Warren feasibility)

## Resolved decisions (see `spikes/kmp-poc/DECISIONS.md` for full evidence)

- **0A (Jsoup)**: GO — Jsoup works via a shared `jvmCommonMain` intermediate source set on both
  desktop and Android, including under R8 minification with zero keep rules. Ksoup is not needed.
- **0B (Warren WebView)**: GO — validated against the live production site (not a mock). The
  fetch-interception mechanism (`WebViewCompat.addDocumentStartJavaScript` + a `@JavascriptInterface`
  bridge) works identically under both a real mobile UA and a spoofed desktop UA. Pagination
  works via a fire-and-forget `evaluateJavascript` call routed through the same interception path
  (sidesteps `evaluateJavascript` not awaiting Promises). No scope change needed — Warren stays in
  the core-parity release.
- **0C (SQLDelight)**: GO — confirmed with a real CRUD + Flow round-trip, including surviving a full
  `am force-stop` process kill (not just backgrounding).
- **0D (Ktor OkHttp)**: GO — confirmed against live endpoints (Scryfall headers, a real store,
  GitHub-releases redirect-follow, streaming download). Note: pin `ktor-client-core`/
  `ktor-client-okhttp` to a version whose transitive `kotlin-stdlib` matches whatever Kotlin version
  Phase 1 locks in (3.5.0 pulled in an incompatible newer stdlib during the spike; 3.0.1 worked).
- **0E (version matrix)**: Kotlin 2.1.20 / Compose Multiplatform 1.8.2 / AGP 8.13.0 / Gradle 8.13 /
  compileSdk+targetSdk 36 / minSdk 26 (deliberately not the newest AGP 9.2, which needs Gradle
  9.4.1 — 8.13 is the better-established choice). Every Kotlin file needs an explicit `package`
  declaration (today's desktop files have none) — a package-less `MainActivity` compiles but throws
  `ClassNotFoundException` at runtime with no build-time warning.

## Goal

Resolve every genuine unknown the rest of the plan is conditioned on, before committing to a
Gradle/module restructure that would be expensive to unwind. Each spike is a small, disposable
prototype (a scratch Gradle module or a branch of the current project that is discarded after the
answer is known) — not production code. Nothing here needs to survive into Phase 1.

## Scope — five independent spikes, can run in parallel

### 0A — Jsoup-on-Android viability (may eliminate the Ksoup migration entirely)

Every Jsoup call in `network/Searchers.kt` (8 call sites) is `Jsoup.parse(bodyString)` plus
`.select`/`.selectFirst`/`.text()` — pure DOM parsing, no `Jsoup.connect()`, no AWT/Swing dependency.
Jsoup itself runs fine as an ordinary Android library at runtime; the real question is build-time:
`commonMain` can't depend on a non-multiplatform JVM jar, but an **intermediate JVM-family source
set** (shared by both the desktop `jvm()` target and the `android()` target) can — a standard,
supported KMP pattern.

Spike: stand up a 2-target KMP module, add `org.jsoup:jsoup:1.17.2` to an intermediate `jvmCommon`
source set, port 2-3 real parsing functions from `Searchers.kt` verbatim against captured real HTML
fixtures from the actual SA stores, and confirm they compile once and run correctly on **both** the
desktop JVM target and the Pixel_9 emulator, **including under a release/minified (R8) Android
build** (Jsoup's charset/XML detection uses some reflection R8 could strip without correct keep
rules).

Fallback only if 0A fails (R8 stripping issues, APK size concerns, or a genuine API gap): spike
Ksoup (Jsoup-API-compatible multiplatform library) against the same fixtures/functions, cataloguing
any `.select`/`.selectFirst`/`.attr`/`.text`/`.outerHtml` gaps that would force a rewrite of parts of
`Searchers.kt`'s selector logic.

### 0B — WebView fetch-interception spike for The Warren (highest-uncertainty spike in the plan)

Build a standalone Android test activity that:
1. Creates a `WebView`, feature-detects `WebViewFeature.isFeatureSupported(DOCUMENT_START_SCRIPT)`
   (not guaranteed on all WebView versions/devices), and uses
   `WebViewCompat.addDocumentStartJavaScript` to monkey-patch `window.fetch` before page scripts run.
2. Adds a `@JavascriptInterface` bridge object via `addJavascriptInterface`.
3. Navigates to `https://thewarren.co.za/search?f=1&q=<card>&t=1&c=3&sc=7&is=1&o=0` (the exact URL
   `BrowserSearcher.searchWarren` uses today) with a UA override — test **both** an unmodified
   WebView UA and a spoofed desktop-Chrome UA matching `BrowserSearcher.createContext`'s
   `Mozilla/5.0 (Windows NT 10.0; Win64; x64) ... Chrome/124.0.0.0 Safari/537.36`, since untested
   mobile-UA behavior is the real unknown here.
4. Confirms the intercepted response for `**/product/search**` arrives at the JS bridge with real
   JSON pricing data (mirrors `page.waitForResponse` in `BrowserSearcher.kt`).
5. From Kotlin, calls `webView.evaluateJavascript` running the same paginated
   `fetch('/openapi/1.0.0fe/client/product/search?limit=20&offset=...')` calls
   `BrowserSearcher.searchWarren` issues today, on the now-initialized session, and confirms page 2+
   results parse.

Test both cold app start and a warm/reused WebView instance (WebView must stay attached to the view
hierarchy — likely a permanently-alive, zero-size or off-screen-attached WebView owned by an
application-scoped holder, not created/destroyed per search).

**If this fails outright, the fallback decision point is "ship without The Warren in the Android
core-parity release and revisit" — flag that back to the user as a possible scope change, don't
silently absorb it.**

### 0C — Android local DB driver spike and library decision

Stand up a minimal SQLDelight module (`app.cash.sqldelight` plugin, `android-driver` artifact) with
a `.sq` schema mirroring **all five** tables in `data/AppDatabase.kt`: `Settings`, `SearchLists`,
`SearchListCards`, `SavedResultSnapshots`, **and `CfThrottleRules`** (persisted per-store rate-limit
escalation state, actively read via `AppDatabase.loadActiveCfRules()` from `SearchEngine.kt:329` —
not vestigial, don't drop it from scope). Confirm CRUD plus a Flow-backed query round-trips
correctly on the Pixel_9 emulator, including the file actually persisting under `Context.filesDir`
across app restarts.

Recommend **SQLDelight over Room** for this project specifically: no annotation-processor/KSP
version churn stacked on top of an already-in-flux Kotlin/AGP upgrade (Phase 0E), typesafe generated
`Query` objects map cleanly onto the existing repos' `StateFlow<List<...>>` shape, and Room's main
differentiator (its newly-multiplatform "Room 2.7+ KMP" story) is moot here since desktop's
Exposed/H2 stays permanently separate. Spike deliverable is a go/no-go on SQLDelight; only fall back
to Room if the SQLDelight Android driver spike hits a real blocker.

### 0D — Ktor engine swap spike (CIO to OkHttp)

`ktor-client-okhttp` is a genuinely multiplatform engine artifact (unlike CIO, which is JVM/Native/JS
only, no Android target) usable from desktop-jvm and android targets alike.

Spike: swap the engine in a throwaway copy of `engine/SearchEngine.kt`'s client construction and
`network/CardImageService.kt`'s separate Scryfall client, and re-run the existing store-scraping
logic against 2-3 real store endpoints plus a Scryfall `/cards/collection` batch call, on desktop
first (fast iteration), checking:
- custom `User-Agent`/`Accept` headers survive (Scryfall 400s without both, noted in CLAUDE.md)
- redirect-follow behavior for GitHub release asset downloads (`GitHubService.downloadRelease`
  relies on `HttpRedirect` with `checkHttpMethod = false`)
- streaming download semantics (`resp.bodyAsChannel()` plus `readAvailable` loop) still work
  identically under OkHttp

Low risk expected, but must be confirmed before Phase 3 commits to it everywhere.

### 0E — Kotlin / Compose Multiplatform / AGP / Gradle version matrix

Determine concrete pinned versions and prove them with a trivial 2-target (`jvm("desktop")` plus
`android()`) Compose Multiplatform module that builds and shows "Hello" on both
`./gradlew :app:run` and the Pixel_9 emulator before any real code moves.

Compose Multiplatform's `org.jetbrains.compose` plugin has long supported an `android()` target
alongside `jvm()` in one module (not a novel combination), so a full Kotlin-version bump is not
automatically required — but given the target device is API 36 and the current pins (Kotlin 1.9.23,
Compose Multiplatform 1.6.11, Gradle 8.7, AGP unset) are dated, actively validate whether staying on
1.9.23/1.6.11 or moving to a newer paired set (which likely also means adding the separate
`org.jetbrains.kotlin.plugin.compose` compiler-plugin artifact if bumping to Kotlin 2.x, since the
Compose compiler plugin split out of the Kotlin distribution at Kotlin 2.0) avoids known
Android-target bugs.

Concrete decisions to lock:
- `compileSdk`/`targetSdk` (36, matching the AVD)
- `minSdk` (recommend 26 — confirm against `WebViewFeature` docs during 0B)
- Kotlin version
- Compose Multiplatform plugin version
- AGP version
- Gradle wrapper version (may need to bump past 8.7 depending on the chosen AGP)

Also introduce a Gradle version catalog (`gradle/libs.versions.toml`) as part of this decision — the
project currently hardcodes every version string directly in `build.gradle.kts`; once there are 3
source sets each pulling overlapping-but-not-identical dependency sets, hardcoded per-line versions
will drift.

## Key risks

0B (Warren WebView) is the standout — genuinely unknown, and Cloudflare/bot-detection or
mobile-specific site behavior could make it unworkable, unlike the others which are "probably fine,
confirm it." 0A could go either way but has a low-cost fallback (Ksoup). 0C/0D/0E are mechanical
validations, not open questions.

## Verification

Each spike ends with a short written decision (in the PR description or a scratch note, not a
permanent doc) and a working, demonstrable prototype:
- 0A/0D: run on desktop JVM first (fast) then confirmed on Pixel_9.
- 0B and 0C: inherently Android-only, must run on the Pixel_9 emulator.
- 0E: proof is "empty KMP module with `android()`+`jvm()` targets assembles a debug APK and
  installs/launches on Pixel_9, and runs the desktop `main()` unchanged."

## Critical files

- `src/main/kotlin/network/Searchers.kt` (Jsoup call-site inventory for 0A)
- `src/main/kotlin/network/BrowserSearcher.kt` (exact Warren URLs/headers/pagination contract for 0B)
- `src/main/kotlin/data/AppDatabase.kt` (schema shape for 0C, all 5 tables)
- `src/main/kotlin/network/GitHubService.kt` (header/redirect/streaming contract for 0D)
- `build.gradle.kts`, `settings.gradle.kts` (current pins for 0E)
