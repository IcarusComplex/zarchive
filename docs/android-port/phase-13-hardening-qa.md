# Phase 13 — Hardening and full-flow QA pass

Status: Not started
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
