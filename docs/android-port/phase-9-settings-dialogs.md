# Phase 9 — Settings, update-check UI, and misc dialogs

Status: **Done.** Verified on the Pixel_9 emulator: Settings dropdown (gear icon) shows Early
Access/Check for updates/Report a bug/Support ZArchive; "Check for updates" against the real GitHub
API correctly triggers the `UpdateStatusFooter` (checking → auto-dismiss). Search Options opened as
a full-screen dialog shows all 20 stores with working individual + "select/deselect all" toggles
(reactive label confirmed) plus the Search Behaviour/Luckshack toggle sections. Crash reporting
verified end-to-end with a real forced crash (`adb shell am crash`, then force-stop + cold start):
the crash log was written in desktop's exact `[timestamp] Thread: ...` format, `CrashReportDialog`
rendered correctly on next launch showing the log, and dismissing it deleted the log file (confirmed
via `run-as ... ls`), matching desktop's read-once-then-clear contract exactly. Full combined
regression gate (`createDistributable` + `desktopTest` + `assembleDebug`) passes together; desktop
`.exe` launched standalone to confirm zero regression. `AddToSearchDialog`/
`AllAlreadySearchedDialog`/`SearchSummaryDialog`/`UpdateDialog`/`DownloadProgressDialog` are
implemented (same proven `ModalScrim` + `DialogSurface` pattern as the two dialogs manually
triggered) but not individually triggered in this pass — they need specific overlapping-search or
pending-update states to reach live.
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
- `src/androidMain/kotlin/androidapp/ZArchiveApplication.kt` (extended — `installCrashLogger()`
  called from `onCreate()`, `readAndClearCrashLog()` companion function)
- `src/androidMain/kotlin/androidapp/MainActivity.kt` (reads the pending crash log before
  `setContent`, passes it into `AndroidApp`)
- `src/androidMain/kotlin/ui/SettingsScreen.kt` (new — `SettingsMenu` as a Material3 `DropdownMenu`,
  `SearchOptionsDialog` as a full-screen `Dialog`)
- `src/androidMain/kotlin/ui/MiscDialogs.kt` (new — `UpdateStatusFooter`, `UpdateDialog`,
  `DownloadProgressDialog`, `CrashReportDialog`, `AddToSearchDialog`, `AllAlreadySearchedDialog`,
  `SearchSummaryDialog`)
- `src/androidMain/kotlin/ui/SearchInputScreen.kt` (added a "Search Options" row above the query
  field, opening `SearchOptionsDialog` — desktop's equivalent button lives in the permanent sidebar,
  which Android doesn't have)
- `src/androidMain/kotlin/ui/AndroidApp.kt` (wires `SettingsMenu` into the `TopAppBar`, all misc
  dialogs gated on their `SearchViewModel` flags, `UpdateStatusFooter` in `bottomBar` above the
  `NavigationBar`, startup `LaunchedEffect`s for `checkForUpdates()` and status auto-dismiss)

## Implementation notes (deviations/decisions made during execution)

- **`SettingsMenu` is a Material3 `DropdownMenu`, not a port of desktop's custom `Popup`.**
  `DropdownMenu`/`DropdownMenuItem` is the native Android equivalent and handles positioning/
  dismiss-on-outside-tap for free; the Early Access item's `onClick` toggles the flag without
  closing the menu (matches desktop), the other three items close it.
- **`SearchOptionsDialog` is a full-screen `Dialog`**, not desktop's fixed 560dp `AlertDialog` —
  same reasoning as `SavedListsDialog`/`SavedResultsDialog` in Phase 8: 20 stores plus multiple
  toggle sections need more room than a centered dialog gives on a phone.
- **All `ModalScrim`-based dialogs in `MiscDialogs.kt` use `Modifier.fillMaxWidth(0.92f)`** instead
  of desktop's fixed 380-480dp widths, which don't translate sensibly to phone screens of varying
  width.
- **"Search Options" access point had no obvious home and got a new small row** above the query
  field in `SearchInputScreen.kt` — desktop's button lives in the permanent sidebar (`LeftPanel`),
  which Phase 6 already established has no Android equivalent.
- **Crash logger installed in `ZArchiveApplication.onCreate()`** (mirroring desktop's
  `installCrashLogger()` being the first call in `main()`), writing to
  `PlatformPaths.debugDumpDir/crash.log` — the exact path `PlatformActions.android.kt`'s
  `crashLogFile` already pointed at since Phase 4. `readAndClearCrashLog()` lives as a companion
  function on `ZArchiveApplication` (not a new top-level file) since it's tightly coupled to the
  same file path and read-once-then-clear contract as the crash-logger installer.
- **Verification for `am crash` required a fresh cold start, not just `adb shell am start`.**
  `am crash <package>` schedules the crash asynchronously and Android's ActivityManager considers the
  (soon-to-be-dead) task "already top-most" for a brief window, so a same-instant `am start` is a
  no-op ("Warning: Activity not started..."). `am force-stop` followed by `am start` reliably forces
  a genuine `onCreate()`, which is what actually reads and clears the crash log.
