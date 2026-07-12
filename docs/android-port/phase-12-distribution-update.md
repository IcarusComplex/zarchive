# Phase 12 — Android distribution and in-app update flow

Status: Done
Depends on: Phase 3 (shared GitHubService), Phase 9 (dialog shells)
Blocks: none downstream (leaf feature phase, but should land before calling core parity "shippable")

## Goal

Ship the Android app the same way desktop already ships — sideloaded via GitHub releases — and give
it an in-app "update available, go install it" flow that reuses the already-common version-compare
logic without needing any of desktop's swap-script machinery.

## Scope

- Extend `.github/workflows/release.yml` (or add a parallel job) to build a signed (or, for an
  initial internal-only release, unsigned/debug-signed) Android APK on the same `v*` tag push,
  uploading it as a new release asset (`ZArchive-android-<version>.apk`) alongside the existing
  Windows/macOS zips — matching the existing multi-platform-single-release pattern.
- `network/GitHubService.kt`'s `checkForUpdate`/`isNewerVersion` (already extracted to
  shared-visibility in Phase 3) gets an Android-appropriate asset filter (`.apk` instead of `.zip`,
  matching on an "android" keyword the same way windows/macos are matched today). `downloadRelease`
  can either be reused as-is (download to Android app-private storage) or replaced with Android's
  `DownloadManager` for a more native "shows in notification shade" experience — recommend starting
  with the existing Ktor streaming download reused as-is (already proven common in Phase 3) to
  minimize new surface area, and only reaching for `DownloadManager` if the plain approach feels
  wrong in practice.
- Real implementation of `PlatformActions.android`'s update-trigger hook (stubbed in Phase 4/9): once
  the APK is downloaded, launch Android's package installer via a `FileProvider`-backed
  `Intent(Intent.ACTION_VIEW)` with the APK's content URI and MIME type
  `application/vnd.android.package-archive` — Android handles the actual install-over-existing-app
  flow itself; no custom swap script needed (per the user's explicit decision), which is a
  significant simplification versus desktop's `buildWindowsSwapScript`/`buildMacSwapScript`. Needs a
  `FileProvider` entry in `AndroidManifest.xml` and the `REQUEST_INSTALL_PACKAGES` permission (or
  directing the user through the system "install from unknown sources" settings prompt if not
  granted).
- Wire `DownloadProgressDialog` (built in Phase 9) to the real download progress callback.

## Key risks

Android's install-unknown-apps permission flow is a real first-run-friction point (conceptually
similar to desktop's SmartScreen/Gatekeeper friction already documented in CLAUDE.md, but a different
mechanism) — needs to be documented for end users the same way `build-tools/README.txt`/
`README-mac.txt` document today's friction. Signing strategy needs an explicit decision (debug-signed
is fine for personal/sideload use but every future release must be signed with the same key or
Android will refuse the update-over-install; this needs to be locked in immediately, not revisited
per release).

## Verification

Full end-to-end release-flow dry run: bump the version, tag, confirm CI produces and attaches an APK
to the draft/test release; sideload an older build on the Pixel_9 emulator, trigger "check for
updates," confirm it detects the new version, downloads it, and successfully launches the Android
installer with the new APK; confirm the install-over-existing-app path works (same signing key) and
the app relaunches showing the new version in Settings/About.

## Critical files

- `.github/workflows/release.yml` (extended)
- `src/androidMain/kotlin/ui/PlatformActions.android.kt` (real update-trigger implementation)
- `src/androidMain/AndroidManifest.xml` (`FileProvider` entry, install-packages permission)
- `src/commonMain/kotlin/network/GitHubService.kt` (android asset-filter addition)

## Verification summary

Verified on the Pixel_9 emulator and on this machine's Gradle build, with the **signing-strategy
decision made** (see below), not deferred:

- `keytool -genkeypair` produced a real release keystore (`zarchive-release.jks`, RSA 4096, alias
  `zarchive`, 10000-day validity). Confirmed PKCS12's store/key password must be identical (a distinct
  `-keypass` is silently ignored) — both env vars are set to the same value.
- `./gradlew assembleRelease` with `ANDROID_KEYSTORE_BASE64`/`ANDROID_KEYSTORE_PASSWORD`/
  `ANDROID_KEY_ALIAS`/`ANDROID_KEY_PASSWORD` set locally produced a correctly-signed APK; confirmed
  with `apksigner verify --print-certs` that the certificate fingerprint matches the generated
  keystore exactly.
- Installed the signed release APK on the Pixel_9 emulator (after `adb uninstall` — release and debug
  signatures differ, so `INSTALL_FAILED_UPDATE_INCOMPATIBLE` is expected and correctly enforced when
  switching between them). App launches cleanly with no `FATAL`/`AndroidRuntime` logcat entries.
- The 4 signing secrets (`ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`,
  `ANDROID_KEY_PASSWORD`) are pushed to the real `IcarusComplex/zarchive` GitHub repo via `gh secret
  set` — never committed to the repo itself.
- Full combined regression gate (`createDistributable desktopTest assembleDebug` in one invocation)
  passes; `ZArchive.exe` launches standalone with zero regression from any Phase 12 change.

**Deliberately not yet done**: a full live release-tag dry run (bump version, push a real `v*` tag,
let `release.yml` build+sign+attach a real APK to a real public GitHub Release, then sideload an
older build and exercise the actual in-app "check for update -> download -> installer" path end to
end). This publishes a live, externally-visible GitHub Release on the shipping app's repo, which is
a shared/hard-to-fully-reverse action — deferred pending explicit user go-ahead rather than assumed.
The local `assembleRelease` + `apksigner verify` verification above confirms the signing pipeline
itself is correct; what's unverified is only the CI job wiring and the real download-and-install
path against a live release asset.

## Implementation notes (deviations/decisions made during execution)

- **Signing strategy decision**: asked the user directly (this was flagged in the plan as needing an
  explicit decision, not something to assume). Chose "generate a new signing keystore now" over
  reusing an existing keystore, shipping unsigned, or deferring the phase. The keystore and its
  passwords live only in this machine's scratchpad and as GitHub Actions secrets — never in the repo.
- **`java.vm.name` for Android detection in shared code**: `GitHubService.checkForUpdate` (in
  `jvmCommonMain`, shared by desktop and Android) needed a way to tell it's running on Android, since
  Android's `os.name` is just `"Linux"` with no signal like desktop's `"mac"`/`"win"`. Used
  `System.getProperty("java.vm.name")` containing `"dalvik"` or `"art"` — reliable and needs no new
  `expect`/`actual`.
- **Asset filter widened to accept both `.zip` and `.apk`** in the same `checkForUpdate` list-building
  step, then narrowed by platform keyword (`"android"`/`"macos"`/`"windows"`) — falls back to the
  first matching asset if no platform keyword hits, mirroring the existing desktop fallback behavior.
  No behavior change for desktop.
- **`FileProvider` scoped to `context.cacheDir` only** (`file_paths.xml` exposes a single
  `<cache-path name="updates" path="." />`), not the whole app storage — the downloaded
  `update.apk` lives in cache and nothing else needs to be shared via the provider.
  `REQUEST_INSTALL_PACKAGES` permission is declared; Android additionally prompts the user for
  "install unknown apps" consent at install time if not already granted (equivalent friction to
  desktop's SmartScreen/Gatekeeper prompts, just a different mechanism) — not separately documented
  in an end-user README yet since no public release has shipped.
- **`UpdateDialog` branches on `canAutoInstall`** (`downloadUrl != null && vm.canInstallUpdate`):
  shows "Download & Install" (wired to the new `PlatformActions.triggerUpdateInstall`) when true,
  otherwise falls back to "Open release page" (`onOpenUrl`) — so a release missing an Android asset,
  or a future platform without an install-flow implementation, degrades gracefully instead of
  breaking.
- Hit the same "XML comment containing `--`" parser failure twice more in this phase (in the new
  `file_paths.xml` and the new `<provider>` block's comment in `AndroidManifest.xml`) — same fix as
  earlier phases (replace `--` with a period), noted here only because it's now a confirmed recurring
  gotcha across three separate phases.
