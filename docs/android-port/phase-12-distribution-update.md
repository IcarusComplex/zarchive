# Phase 12 — Android distribution and in-app update flow

Status: Not started
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
