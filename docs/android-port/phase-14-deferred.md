# Phase 14 (explicitly deferred / out of scope for the core-parity plan) — Search Monitor and cart automation

Status: Deferred — not sequenced, not part of the core-parity port

Not part of the core-parity port. Listed only so it isn't silently forgotten:

- **Background Search Monitor** (`SearchMonitorsPane`, `MonitorFoundDialog`/`MonitorFoundRow` in
  `ui/App.kt`) — a persistent interval-polling feature that needs `WorkManager` or a foreground
  service on Android; fundamentally different execution model from desktop's presumably
  always-running-process polling, deserves its own dedicated planning pass once core parity has
  shipped and been used for a while.
- **Order-list "add all to cart" batch automation** — desktop's sequential
  `Desktop.getDesktop().browse()`-with-delays approach has no direct Android analog; a redesign
  (WebView-driven automation, deep links if any target store supports them, or dropping the feature
  entirely in favor of per-line opening as shipped in Phase 10) should be scoped separately, informed
  by real usage data from the core-parity release.

When this phase is picked up, split it into its own `phase-14a-*`/`phase-14b-*` files following the
same format as Phases 0-13 (Goal / Scope / Key risks / Verification / Critical files), informed by
whatever real-world Android usage patterns emerge from the core-parity release first.
