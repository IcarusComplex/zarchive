# Phase 10 — Order lists (single-listing links, no cart automation)

Status: **Done.** Verified on the Pixel_9 emulator with a real 3-card search (sol ring/lightning
bolt/counterspell): strategy toggle recomputed reactively (Cheapest total: 3 stores/R98,00 to
Fewest packages: 1 store/R142,00, correctly consolidating into Battle Wizards), and unchecking a
line correctly dimmed it and recalculated the total live (R142,00 → R104,00 after unchecking
counterspell, exactly the R38,00 difference). `StoreOrderCard`/`OrderLineRow`/`UncoveredCard`/
`PlanStat` all render correctly with real card art and prices. Full combined regression gate
(`createDistributable` + `desktopTest` + `assembleDebug`) passes together; desktop `.exe` launched
standalone to confirm zero regression.
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
- `src/androidMain/kotlin/ui/OrderListsScreen.kt` (new — `OrderListsScreen`, `PlanStat`,
  `StoreOrderCard`, `OrderLineRow`, `UncoveredCard`)
- `src/androidMain/kotlin/ui/AndroidApp.kt` (wired `OrderListsScreen` into the Order Lists bottom-nav
  destination, replacing the Phase 6 placeholder)

## Implementation notes (deviations/decisions made during execution)

- **The entire "add all to cart" browser-automation block was dropped**, not just simplified —
  desktop's `StoreOrderCard` has ~150 lines of per-platform (Shopify/WooCommerce/BigCommerce/
  PrestaShop) cart-URL construction and sequential `openUrl`-with-delays logic gated behind
  `showCart`. None of it ported; Android's header row only opens the store's homepage.
  Per-line/per-store single-link opening (already the only paths desktop offers besides that
  automation) is the full extent of Android's core-parity Order Lists screen.
- **No `LazyColumn`** — same reasoning as Phase 7's `ResultsScreen`: a plain `Column` of
  `StoreOrderCard`s inside `AndroidApp`'s existing single scrollable container, avoiding Compose's
  infinite-height-constraint crash from nesting an unbounded `LazyColumn`.
- **`PlanStat`'s desktop "tap uncovered count to scroll to the uncovered-cards section"
  interaction was dropped** — it relied on a `LazyListState.animateScrollToItem` desktop has handy
  from its own `LazyColumn`; Android's plain-`Column` structure doesn't have an equivalent
  scroll-to-item primitive without extra plumbing, and this is a minor convenience, not
  core-parity-blocking. The uncovered-cards `UncoveredCard` section still renders at the bottom.
- Verified with **both** the strategy toggle (Cheapest total ↔ Fewest packages) recomputing plans
  live and a checkbox toggle recomputing totals live — both reactivity paths desktop relies on
  (`derivedStateOf` over `vm.results`) work identically on Android since `OrderOptimizer.kt` is
  already-common, unmodified code.
