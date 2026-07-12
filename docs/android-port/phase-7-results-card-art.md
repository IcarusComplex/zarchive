# Phase 7 — Results display plus card art (the largest single UI phase)

Status: Not started
Depends on: Phase 6
Blocks: Phase 8, Phase 10, Phase 11 (results rendering used by saved-results, order lists, Warren)

## Goal

Port the actual results-viewing experience — the reason the app exists — including the tap-to-toggle
card art preview the user has already decided on (replacing desktop's hover-popup pattern). This
phase carries the most visual/interaction surface area of the whole port.

## Scope (desktop reference to Android target)

- `SearchResultsTab` (2387-2467) to `CardSection` (2669-2862) to `ListingTable`/`TableHeaderRow`
  (2877-2933) to `ListingRow` (2972-3091) chain ports as the main results list, using
  `LazyColumn`/nested `LazyColumn` sections instead of any AWT-specific layout. `StatusChip`
  (3093-3116) and `CountBadge` (2863-2876) port near-verbatim (pure Compose, no desktop API use).
- `CardThumbnail` (3118-3162) plus `ShimmerOverlay` (3164-3198) plus `CardImagePopup` (3199-3211) —
  the hover-driven popup (`Popup` plus custom `PopupPositionProvider`,
  `MutableInteractionSource`/`collectIsHoveredAsState()`) is replaced, per user decision, with a
  tap-to-toggle larger preview: tapping a thumbnail expands an inline/overlay larger card image;
  tapping again (or tapping elsewhere) collapses it. This needs a new small piece of local UI state
  (which thumbnail/row is "expanded") rather than any hover-detection code — none of the hover
  machinery ports.
- `CardSummaryPanel`/`CardSummaryEntry` (3212-3355+, collapsible found/pending summary grid) ports
  with the same tap-to-toggle popup replacement for its hover-preview behavior. `FilterField`
  (2634-2668, "Find a card in the list..." substring filter) ports verbatim as ordinary Compose text
  field plus filtering logic (already common via `SearchViewModel`). `IconTooltip` (2934-2971) is
  likely dropped or replaced with Android's native long-press tooltip affordance if kept at all; not
  a core-parity requirement.
- `LuckshackLinks` (2326-2386) ports with the desktop mouse-wheel-to-horizontal-scroll remap
  dropped — Android gets native horizontal swipe on a `LazyRow` for free.
- Image loading: desktop's `javax.imageio.ImageIO` bitmap decode (used implicitly wherever cached
  JPEG files become `ImageBitmap`) is replaced on Android with `BitmapFactory.decodeFile` plus
  `.asImageBitmap()` — start here, since Phase 3's `CardImageService` already produces local JPEG
  files identically to desktop; only reach for Coil if loading/placeholder/shimmer ergonomics
  justify the extra dependency.

## Key risks

Getting the tap-to-toggle interaction feeling native (not just "hover code with taps substituted") —
spend real design attention here since it's explicitly called out as a UX decision point. Nested
`LazyColumn`-in-`LazyColumn` scrolling performance for large result sets is a known Compose
pitfall — verify with a large multi-card query (20+ cards) before considering this phase done, not
just a 2-card smoke test.

## Verification

On Pixel_9: run a search that's known to produce results across in-stock and out-of-stock listings
for several cards, confirm the table layout, price sorting (fixed price-ascending, no sort controls —
must not reintroduce them per CLAUDE.md), in-stock/out-of-stock split with dimmed out-of-stock table,
tap-to-expand card art on both a `ListingRow` thumbnail and a `CardSummaryEntry`, confirm the card
summary's `FilterField` substring-filters correctly, confirm Luckshack link chips render as native
horizontally-swipeable chips and open the correct URL via `PlatformActions` when tapped. Test with a
large (20+ card) list for scroll performance.

## Critical files

- `src/main/kotlin/ui/App.kt` (reference only — `SearchResultsTab` through `CardSummaryEntry`, lines
  noted above)
- `src/androidMain/kotlin/ui/ResultsScreen.kt` (new)
- `src/androidMain/kotlin/ui/CardArtPreview.kt` (new, tap-to-toggle)
- `src/androidMain/kotlin/ui/CardSummaryPanel.kt` (new)
