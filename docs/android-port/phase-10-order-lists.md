# Phase 10 — Order lists (single-listing links, no cart automation)

Status: Not started
Depends on: Phase 2 (OrderOptimizer), Phase 6, Phase 7
Blocks: none downstream (leaf feature phase)

## Goal

Port the "buying plan" pane, explicitly excluding the batch "add all to cart" browser automation the
user has deferred — Android core parity just opens individual listing URLs.

## Scope (desktop reference to Android target)

- `ResultsPane` (1942-1992, the Results/Order-Lists tab container — the Order Lists half) to
  `OrderListsPane`/`StoreOrderCard`/`OrderLineRow` (referenced in CLAUDE.md's Order Optimizer
  section, built on the already-common `data/OrderOptimizer.kt` from Phase 2) ports as: strategy
  toggle (cheapest-total vs fewest-packages, both already-common pure functions), totals row
  (`PlanStat`), a card per store with its lines, an "uncovered cards" section. Every "open this
  listing"/"open this store" action uses `PlatformActions`' URL-open hook (`Intent.ACTION_VIEW`)
  instead of `Desktop.getDesktop().browse`.
- **Not ported:** the sequential `Desktop.getDesktop().browse()`-with-delays "add all to cart"
  automation (desktop-browser-specific, explicitly deferred). Android's Order Lists screen only
  offers per-line/per-store single-link opening in this phase.

## Key risks

Low — this is UI assembly over already-common, already-tested pure functions (`cheapestPlan`/
`fewestStoresPlan` from Phase 2). The only real decision is whether tapping a store card header
opens every listing at once via multiple Android `Intent.ACTION_VIEW` calls (likely janky / triggers
Android's "app wants to open multiple links" friction) or is scoped down to per-line opening only —
recommend per-line only for the core-parity release, revisit multi-open UX in the later
cart-automation phase (Phase 14).

## Verification

On Pixel_9: run a multi-card search producing a real order plan, switch between cheapest-total and
fewest-packages strategies and confirm totals recompute reactively (matching desktop's
`derivedStateOf`-driven reactivity — should update live as results stream in, not require a button),
tap a listing to open it in the default browser, confirm uncovered (out-of-stock-everywhere) cards
show correctly.

## Critical files

- `src/main/kotlin/ui/App.kt` (reference only — `ResultsPane`/`OrderListsPane` section)
- `src/commonMain/kotlin/data/OrderOptimizer.kt` (already moved, Phase 2 — reference only)
- `src/androidMain/kotlin/ui/OrderListsScreen.kt` (new)
