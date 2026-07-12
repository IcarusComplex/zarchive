# Phase 1 — Toolchain upgrade and Gradle KMP restructuring skeleton

Status: **Done.** All three verification gates passed on this machine:
- `.\gradlew.bat createDistributable` builds the desktop app unchanged; launched the built
  `ZArchive.exe` and ran a real search ("Lightning Bolt" → 71 listings, real prices/card art/saved
  lists all intact) — zero behavior change.
- `.\gradlew.bat assembleDebug` builds an installable Android debug APK; installed and launched on
  the Pixel_9 emulator, showing the placeholder screen with `data.BuildInfo.VERSION` ("v1.0.21")
  resolved correctly from the shared `commonMain` generated source — proving the generation wiring
  works across both targets.
- `.\gradlew.bat desktopTest` — all 107 existing tests pass, 0 failures/errors (file moves only, no
  behavior change).

Depends on: Phase 0 (version matrix from 0E)
Blocks: everything else

## Goal

Convert the single `kotlin("jvm")` module into a Kotlin Multiplatform module with `commonMain` /
`desktopMain` (jvm target, replacing today's `main`) / `androidMain` source sets, apply the version
decisions from Phase 0E, and get an empty-but-building Android app shell running on the Pixel_9
emulator — before moving a single line of real feature code. This is the highest-leverage,
must-be-boringly-solid phase: everything else depends on this compiling cleanly in both directions.

## Scope

- `build.gradle.kts`: replace `kotlin("jvm")` with `kotlin("multiplatform")`; add
  `id("com.android.application")`; keep `id("org.jetbrains.compose")` and
  `id("org.jetbrains.kotlin.plugin.serialization")`, add `org.jetbrains.kotlin.plugin.compose` if
  the Kotlin bump crosses 2.0. Define `jvm("desktop") { ... }` (mirrors today's single target —
  reuse `mainClass = "MainKt"`, the `nativeDistributions` block, `createDistributable`/jpackage
  config unchanged) and
  `android { compileSdk = 36; defaultConfig { applicationId = "co.za.zarchive"; minSdk = 26
  (pending 0E); targetSdk = 36 } }`.
- Introduce `gradle/libs.versions.toml` per the Phase 0E decision; migrate existing hardcoded
  versions (Ktor 2.3.10, Jsoup 1.17.2, Playwright 1.52.0 [desktop-only], coroutines 1.8.0,
  kotlinx-serialization 1.6.3, Exposed 0.50.1 [desktop-only], H2 2.2.224 [desktop-only]) into it.
- Move `src/main/kotlin` to `src/desktopMain/kotlin` (mechanical, no content changes yet); create
  empty `src/commonMain/kotlin`, `src/androidMain/kotlin`, `src/commonTest/kotlin` directories; move
  the existing `src/test/kotlin` to `src/desktopTest/kotlin` unchanged (regression gate:
  `./gradlew desktopTest` must reproduce today's green suite exactly).
- New `src/androidMain/AndroidManifest.xml` (app label "ZArchive", `INTERNET` permission — required
  for all networking — plus whatever 0B determined for WebView), a placeholder
  `src/androidMain/kotlin/MainActivity.kt` using `ComponentActivity` plus
  `setContent { placeholder Compose text }`, and an adapted `BuildInfo` generation (today's
  `generateBuildInfo` Gradle task writes `data/BuildInfo.kt` into a desktop-only generated dir —
  needs to either move to `commonMain`'s generated sources or be duplicated per target so both apps
  report the same `BuildInfo.VERSION`).
- Reuse the existing `version = "1.0.21"` Gradle property to also drive Android's
  `versionName`/`versionCode` so the two platforms never drift out of sync.
- `settings.gradle.kts`: confirm `google()` (already present) is enough, add any AGP-specific
  `pluginManagement` entries the chosen AGP version needs.
- Set `local.properties` → `sdk.dir` explicitly to the real SDK path
  (`%LOCALAPPDATA%\Android\Sdk`) — don't rely on the misconfigured `ANDROID_HOME` env var (it
  currently points at `C:\Android\android-sdk`, which only has `platform-tools`).

## Key risks

AGP/Kotlin/Gradle-wrapper version compatibility triangle (already largely de-risked by Phase 0E, but
the real module has more surface area — Compose Desktop's own plugin version must also tolerate
coexisting with an android target in the same module without conflicting Compose-compiler
configuration). `jpackage`/`createDistributable` for the desktop target must be proven unaffected —
this is a live shipping app; a broken `createDistributable` blocks all future Windows/macOS
releases, so this phase's exit gate explicitly includes running `./gradlew.bat createDistributable`
and confirming the existing Windows dist still produces a working `ZArchive.exe`, not just an
Android build.

## Verification

- `.\gradlew.bat createDistributable` still produces a working Windows app image (spot-check: launch
  it, run one real search).
- `./gradlew assembleDebug` (or the KMP-equivalent task name) produces an installable Android debug
  APK; `adb install` plus launch on the Pixel_9 emulator shows the placeholder screen.
- `./gradlew desktopTest` reproduces today's full green test suite with no changes to test results
  (file moves only).

## Critical files

- `build.gradle.kts`
- `settings.gradle.kts`
- `gradle/libs.versions.toml` (new)
- `src/androidMain/AndroidManifest.xml` (new)
- `src/androidMain/kotlin/androidapp/MainActivity.kt` (new)

## Implementation notes (deviations/decisions made during execution)

- **Android `namespace`/`applicationId`: `co.za.zarchive`**, not the Gradle `group` (`co.za.mtg`,
  intentionally kept as-is — CLAUDE.md documents it as a legacy internal identifier, not
  user-facing). `co.za.zarchive` matches the existing macOS `bundleID` already set in
  `nativeDistributions.macOS`, so all three platforms now share one identifier.
  `versionName`/`versionCode` are driven off the same Gradle `version` property per the plan;
  `versionCode` is currently a flat `1` (bump manually per release until Phase 12 automates it).
- **New Android code lives under package `androidapp`** (e.g. `androidapp.MainActivity`), not a
  reverse-domain package — matches this project's existing convention of short top-level packages
  (`data`, `network`, `engine`, `ui`) rather than introducing a new naming style. The manifest
  references it fully-qualified (`android:name="androidapp.MainActivity"`) since it doesn't share a
  prefix with the `co.za.zarchive` namespace — this is valid and avoids any confusion with
  `android.*` platform packages.
- **Version catalog uses `alias(libs.plugins.x)` in the `plugins {}` block** (not
  `id(...) version libs.versions.x.get()`), which requires each plugin to have a `[plugins]` entry
  in `gradle/libs.versions.toml` — done for all five plugins (kotlin-multiplatform,
  android-application, jetbrains-compose, kotlin-compose-compiler, kotlin-serialization).
- **Ktor/Jsoup/Playwright/coroutines/Exposed/H2 versions were left unchanged** (moved into the
  catalog verbatim, not upgraded) — Phase 1 is pure restructuring; the actual Ktor CIO→OkHttp
  engine swap and version bump (per the 0D spike, which needed Ktor 3.0.1+ for Kotlin 2.1.20
  compatibility) is Phase 3's job, scoped to when the networking code actually moves.
  `compose.materialIconsExtended` moved to `commonMain` (previously implicit in the single-module
  dependency block) since both platforms will eventually want icons; everything else desktop-only
  stayed in `desktopMain`.
- **`gradle.properties` (new, root)**: `android.useAndroidX=true` (hard AGP requirement, learned
  from the Phase 0 spike) + `kotlin.mpp.androidSourceSetLayoutVersion=2`.
- **`local.properties` (new, root, gitignored)**: `sdk.dir` pointing at the real SDK
  (`%LOCALAPPDATA%\Android\Sdk`) — `.gitignore` updated to exclude `local.properties`/`.cxx/`/
  `captures/`, since this path is machine-specific and wasn't previously gitignored (no Android
  module existed before).
- **`generateBuildInfo` now targets `commonMain`'s generated-sources dir** (via
  `kotlin.sourceSets.getByName("commonMain").kotlin.srcDir(...)`), and all Kotlin compile tasks
  depend on it via `tasks.matching { it.name.startsWith("compileKotlin") }` rather than naming each
  target's compile task explicitly — robust to future target additions.
- Compiler warnings (not errors) appeared in `ui/App.kt` about deprecated Material icon APIs
  (`Icons.Filled.OpenInNew` etc. → `Icons.AutoMirrored.Filled.OpenInNew`) — a side effect of the
  Compose Multiplatform 1.6.11→1.8.2 bump. Cosmetic, non-blocking; worth cleaning up opportunistically
  in a later UI phase but not required for Phase 1's scope.
