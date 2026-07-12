# Phase 9 — Settings, update-check UI, and misc dialogs

Status: Not started
Depends on: Phase 6, Phase 4 (settings flags), Phase 3 (update-check logic)
Blocks: Phase 11 (ZArchiveApplication holder), Phase 12 (DownloadProgressDialog wiring)

## Goal

Port the remaining "chrome" — settings surfaces and the smaller supporting dialogs that round out
core parity — deferring only the actual update-install mechanism (Phase 12) while building the
update-check UI now since the check itself is already common as of Phase 3.

## Scope (desktop reference to Android target)

- `SettingsMenu` (1149-1213) plus `SettingsCheckItem`/`SettingsLinkItem`/`SettingsActionItem`
  (1214-1304: Early Access toggle, check-updates action, report-bug/support links) to Android
  settings menu/screen, links opening via `PlatformActions` (`Intent.ACTION_VIEW`) instead of
  `Desktop.getDesktop().browse`.
- `SearchOptionsDialog` (2468-2591) plus `OptionsSectionHeader`/`OptionToggle` (2592-2633) — the real
  settings UI (store enable/disable grid, ignore-basic-lands, partial-match, auto-open-Luckshack) —
  ports closely, pure Compose state bound to the already-common `SearchViewModel` settings flags.
- `UpdateStatusFooter` (1305-1344, animated auto-dismissing banner) plus `UpdateDialog` (1357-1418,
  "update available" modal) to port the UI; the action taken on "update now" changes completely in
  Phase 12 (this phase can stub it to just open the release page via `PlatformActions`, matching the
  eventual "notify plus link to APK download" flow, and Phase 12 upgrades that stub to something
  richer like a `DownloadManager`-driven in-app download if desired — but the simple "open browser to
  the release" is enough to call this phase's UI complete).
- `DownloadProgressDialog` (1672-1733) — kept structurally similar per the user's note that the
  progress-UI shape carries over even though the mechanism changes; wire it up properly in Phase 12
  once there's a real download to show progress for. Build the dialog shell now, connect it later.
- `CrashReportDialog` (1734-1807: shows crash log, copy-to-clipboard, open GitHub issue) ports using
  `PlatformActions`' clipboard hook (Phase 4) and `Intent.ACTION_VIEW` for the GitHub issue link;
  crash-log capture on Android needs its own `Thread.setDefaultUncaughtExceptionHandler` equivalent
  (Android supports this API directly — same call as desktop's `Main.kt installCrashLogger`, just
  needs to run from `Application.onCreate()` instead of `main()`, writing to Phase 3's
  `PlatformPaths` crash-log location instead of `~/zarchive-debug/`).
- `AddToSearchDialog` (1419-1482), `AllAlreadySearchedDialog` (1483-1534), `SearchSummaryDialog`
  (1535-1671, auto-shown post-search stats for more than 5 cards) — all pure Compose-state dialogs,
  port directly.

## Key risks

Low, mostly volume of small screens rather than novel risk. The one thing to get right: Android's
crash-logger must be wired at `Application.onCreate()` (needs the Phase 1 `Application` subclass)
early enough to catch startup crashes, matching desktop's `installCrashLogger()` being the very
first call in `main()`.

## Verification

On Pixel_9: open Settings menu, toggle Early Access, trigger a manual "check for updates" (against
the real GitHub API — should correctly report "up to date" against the current release), open Search
Options and toggle each store on/off plus each behavior flag and confirm it persists (via Phase 5 DB)
across an app restart, force a crash (temporary debug hook) and confirm `CrashReportDialog` shows on
next launch with working copy-to-clipboard and GitHub-issue-link buttons, trigger the
more-than-5-card search summary dialog.

## Critical files

- `src/main/kotlin/ui/App.kt` (reference only — `SettingsMenu` through `SearchSummaryDialog`, lines
  noted above)
- `src/androidMain/kotlin/ZArchiveApplication.kt` (new, Application subclass for crash logger plus
  Context holder)
- `src/androidMain/kotlin/ui/SettingsScreen.kt` (new)
- `src/androidMain/kotlin/ui/MiscDialogs.kt` (new)
