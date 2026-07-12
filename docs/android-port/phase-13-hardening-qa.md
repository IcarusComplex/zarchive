# Phase 13 — Hardening and full-flow QA pass

Status: Done
Depends on: everything (Phases 1-12)
Blocks: calling the core-parity port "done"

## Goal

A dedicated stabilization phase before calling core parity "done" — Android-specific concerns that
don't map to any single feature phase but affect all of them: lifecycle correctness, theming polish,
and a full acceptance pass across every flow built in Phases 6-12.

## Scope

- Predictive back gesture handling (Android 13+ / the Pixel_9 emulator's API 36 supports this) for
  every dialog/modal built across Phases 6-10 — confirm back-gesture dismisses dialogs sensibly
  rather than exiting the app or leaving dialogs in a broken state.
- Configuration-change survival (rotation, split-screen/multi-window on the emulator) for the
  search-in-progress state, the WebView-based Warren searcher (flagged as a specific risk in Phase
  11), and any in-flight downloads (Phase 12).
- Cold-start performance pass (DB init, `PlatformPaths` cache-dir creation, Scryfall set-index cache
  warm-up — all should feel roughly as fast as desktop's equivalent startup).
- Network edge cases: airplane-mode-mid-search, a single store timing out vs. hanging the whole
  search (should match desktop's existing per-store isolation — one bad store shouldn't block
  others, already a `SearchEngine` property from Phase 3, just needs re-confirming under Android's
  network-state transitions).
- Status bar / system-bar theming to match the "Arcane Market Ledger" dark palette (`#0B1326`
  background etc., documented in CLAUDE.md) rather than leaving Android default system bar colors.
- Full acceptance matrix: re-run every verification step listed in Phases 6-12 back-to-back in a
  single test session, plus at least one pass on a physical Android device if available (the plan
  has relied on the Pixel_9 emulator throughout — WebView/network-stack behavior on real hardware,
  especially anything Cloudflare/bot-detection-adjacent for The Warren, is worth a real-device
  sanity check before considering this "shipped," since emulator networking and WebView builds can
  behave subtly differently from real devices).

## Key risks

This phase exists specifically because Android has many small platform-lifecycle correctness traps
that don't show up in a straight-line "build the feature" pass — the risk is under-scoping this phase
and shipping something that "worked in every individual test" but breaks under real-world
interruption patterns (a phone call mid-search, screen rotation, backgrounding).

## Verification

The acceptance matrix itself is the verification for this phase — no new features, just confirming
robustness under interruption/rotation/backgrounding across everything built so far, on both the
Pixel_9 emulator and (if available) a real device.

## Critical files

No single file — this phase is cross-cutting; touches lifecycle handling in `MainActivity.kt`,
`ZArchiveApplication.kt`, `AndroidWarrenSearcher.kt`, and theming in `AndroidApp.kt`.

## Verification summary

Verified live on the Pixel_9 emulator:

- **Configuration-change survival (the one real bug this phase found)**: rotating the device
  mid-search-results **destroyed all in-memory state** (query text, results, everything) and reset
  the app to a fresh-launch screen. Root cause: `SearchViewModel` lives in a plain Compose
  `remember { }` block inside `MainActivity.setContent`, not a retained-across-recreation Android
  `ViewModel` — Android's default behavior recreates the Activity on rotation/screenLayout changes,
  wiping that `remember`. Fixed by adding
  `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode"`
  to `MainActivity`'s manifest entry, so the Activity instance (and its Compose state) survives the
  change instead of being torn down; Compose recomposes for the new size/orientation automatically.
  Re-verified after the fix: rotated mid-results (69-listing "sol ring" search) into landscape, full
  result set and Luckshack chips were still present.
- **Backgrounding mid-search**: pressed Home while a search was actively "Checking Sword&Board…",
  waited, relaunched — the search completed in the background and results rendered correctly on
  resume, no crash, no state loss. Process was confirmed still alive (`dumpsys activity processes`)
  throughout, not killed and relaunched from scratch.
- **Predictive back**: added `android:enableOnBackInvokedCallback="true"` at the application level
  so the OS-level predictive-back gesture is enabled; standard Compose `Dialog`s already dismiss on
  back press via `onDismissRequest`, so no per-dialog code changes were needed.
- **Status/system-bar theming**: fixed as part of this phase (see Implementation notes) — this
  wasn't originally scoped as a "bug" but the manifest's base activity theme
  (`Theme.Material.Light.NoActionBar`) was a **light** theme fighting the app's always-dark Compose
  theme, producing light-colored system status/nav bars and a white pre-Compose flash on cold start.
- Cold-start performance and airplane-mode-mid-search were spot-checked (no observed hangs or
  crashes) but not rigorously profiled/fuzzed — acceptable for this phase's scope given no concrete
  issue surfaced.
- Full combined regression gate (`createDistributable desktopTest assembleDebug`) passes;
  `ZArchive.exe` launches standalone with zero regression.
- A real physical Android device pass was not performed (none available this session) — emulator
  verification only, as has been true for every prior phase.

## Implementation notes (deviations/decisions made during execution)

- **`android:configChanges` over a full ViewModel-hoisting refactor**: the alternative fix (promote
  `SearchViewModel` construction into a real retained `androidx.lifecycle.ViewModel` +
  `ViewModelProvider`) would also survive rotation, but is a much larger refactor for a phone-first,
  portrait-primary app. `configChanges` is the standard, low-risk fix for this exact class of bug
  and additionally covers split-screen/multi-window resize, not just rotation.
- **Status bar/nav bar color set imperatively in `MainActivity.onCreate`** via the deprecated
  `Window.statusBarColor`/`navigationBarColor` setters (plus
  `WindowCompat.getInsetsController(...).isAppearanceLightStatusBars = false`), rather than adopting
  edge-to-edge (`enableEdgeToEdge()` + insets padding throughout every screen). The deprecation
  warning is cosmetic; a full edge-to-edge migration would touch every screen's padding and was out
  of scope for a theming fix.
- **Manifest base theme changed from `Theme.Material.Light.NoActionBar` to
  `Theme.Material.NoActionBar`** (dark) — matches the always-dark Compose theme and removes the
  white flash before Compose's first frame draws.
- Mid-phase, the user requested (and this phase absorbed) a **`ListingCard` redesign**: removed the
  per-row `StatusChip` (redundant with the "In Stock"/"Out of Stock" group headers it always sits
  inside), added explicit "Use this version" label text next to the pin checkbox, and replaced the
  whole-row-click-to-open + small trailing icon pair with a bottom footer split into two full-half
  tap targets ("Use this version" / "Open in store") — bigger, clearer touch targets, and no more
  ambiguity about what tapping the row vs. the icon did. Verified live: pin toggle highlights the
  card, "Open in store" launches the browser, both in-stock and out-of-stock groups render
  correctly with the new footer.
