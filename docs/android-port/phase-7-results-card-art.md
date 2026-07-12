# Phase 7 — Results display plus card art (the largest single UI phase)

Status: **Done.** Verified on the Pixel_9 emulator with real multi-store searches: a single-card
query ("sol ring") rendered real per-printing Scryfall art, price-ascending sort, in-stock/
out-of-stock split (dimmed + grayscale), and a live listing count; a 4-card query
(lightning bolt/counterspell/brainstorm/swords to plowshares, 293 listings) exercised the
`CardSummaryPanel` (4/4 found, per-card thumbnails, tap-to-scroll-to-section) and the Luckshack
chip row. Tap-to-toggle card art (full-screen `ModalScrim` + `EnlargedCardPreview`, dismiss by
tapping the scrim or the close icon) verified on both a `ListingCard` thumbnail and a
`CardSummaryEntry` thumbnail. Tapping a row opens the listing URL in Chrome (verified). A real,
Android-specific bug was found and fixed during verification (see notes below). Full combined
regression gate (`createDistributable` + `desktopTest` + `assembleDebug`) passes together; desktop
`.exe` launched standalone to confirm zero regression.
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
- `src/androidMain/kotlin/ui/ResultsScreen.kt` (new — `ResultsScreen`, `CardSection`, `ListingGroup`,
  `ListingCard`, `StatusChip`, `CountBadge`, `FilterField`, `LuckshackLinks`)
- `src/androidMain/kotlin/ui/CardArtPreview.kt` (new — `CardThumbnail`, `ShimmerOverlay`,
  `EnlargedCardPreview`, shared `imageCache`)
- `src/androidMain/kotlin/ui/CardSummaryPanel.kt` (new — `CardSummaryPanel`, `CardSummaryEntry`)
- `src/androidMain/kotlin/ui/ModalScrim.kt` (extended — added an `onDismiss` param so the scrim can
  close the card art preview, defaulting to a no-op for existing/future non-dismissible callers)
- `src/androidMain/kotlin/ui/AndroidApp.kt` (wired `ResultsScreen` in place of the Phase 6 placeholder;
  hoisted the results `ScrollState` + its root position for summary-tap-to-scroll)
- `src/androidMain/res/xml/network_security_config.xml` (new) + `src/androidMain/AndroidManifest.xml`
  (`android:networkSecurityConfig` attribute) — see implementation notes

## Implementation notes (deviations/decisions made during execution)

- **Desktop's wide 7-column table doesn't fit a phone.** `ListingRow` is replaced by `ListingCard`: a
  two-row layout (thumbnail + title/set on top, store + status chip + price + pin/link icons below)
  instead of desktop's fixed-width columns sized for an ~1100dp window.
- **Hover popup replaced with a full-screen tap-to-dismiss "lightbox"**, not an anchored
  `PopupPositionProvider` like desktop. Tapping any thumbnail (`ListingCard` or `CardSummaryEntry`)
  opens a single shared `ModalScrim` + `EnlargedCardPreview` overlay hoisted at the `AndroidApp` root;
  tapping the scrim (or the small close icon) dismisses it. Simpler and more mobile-idiomatic than
  replicating desktop's anchored-positioning math for a tap gesture, and it reuses the `ModalScrim`
  primitive Phase 6 already built for future dialogs.
- **`CardSummaryEntry` gained a small tappable thumbnail chip that desktop's row doesn't have.**
  Desktop's summary row shows art *only* via hover — there's no persistent thumbnail in the row
  itself. Touch has no hover equivalent, and the row's tap is already claimed by "scroll to this
  card's section" (`onCardClick`), so a separate small thumbnail was added as the tap target for
  the art preview. The "Show card on hover" checkbox (desktop-only setting) was dropped entirely —
  it's a hover-specific affordance with no mobile equivalent.
  - **`onCardClick` scroll-to-section is real, not a no-op.** Initially stubbed empty during first
    implementation pass, then wired: `AndroidApp` hoists the results `ScrollState` plus its own
    `positionInRoot()` (via `onGloballyPositioned`), `ResultsScreen` records each `CardSection`'s
    `positionInRoot()` in a plain (non-reactive) map as they compose, and tapping a summary entry
    computes `scrollState.value + (sectionY - scrollRootY)` and calls `animateScrollTo(...)` —
    verified on-device: tapping a summary entry animates the results scroll to bring that card's
    section header to the top of the viewport.
- **No `LazyColumn` for per-card sections — a plain `Column` inside `AndroidApp`'s existing
  `verticalScroll` container.** `AndroidApp` (Phase 6) already wraps the whole Search Results tab
  content in one scrollable `Column`; nesting an unbounded-height `LazyColumn` inside that would hit
  Compose's "infinite height constraints" measurement error. Using a plain `Column` avoids that
  entirely and matches the single-scroll-container shape Phase 6 established. Verified acceptable
  scroll performance on-device with a 4-card / 293-listing result set; true virtualization (a real
  `LazyColumn` with the search screen itself, not just results, inside it) is a candidate follow-up
  if a much larger result set ever shows jank, but wasn't needed here.
- **Real Android-specific bug found and fixed: cleartext HTTP blocking.** 3 of the 20 stores in
  `data.STORES` use plain `http://` URLs (D20 Battleground, Geek Home, Underworld Connections).
  Android blocks cleartext traffic by default since API 28; desktop's Ktor client has no such
  restriction, so these stores silently worked on desktop but failed on Android with "CLEARTEXT
  communication ... not permitted" errors — invisible until this phase actually rendered the
  per-store error rows. Fixed with a `network_security_config.xml` scoped to exactly those 3 domains
  (not a blanket `usesCleartextTraffic="true"`), referenced from `AndroidManifest.xml`. Verified with
  a before/after real search: 49 listings (broken) to 69 (fixed) for "sol ring". This is arguably a
  Phase 3 (networking) gap rather than a Phase 7 (UI) one, but it was only discoverable once real
  error text had somewhere to render, so it's fixed and documented here.
- **`sortedByPriceAsc()` and `formatZar()` duplicated** (not shared with desktop's private
  equivalents in `App.kt`) — same precedent as Phase 6's `ui/theme/Theme.kt` duplicating the color
  palette: trivial, dependency-free pure functions, not worth a shared-module extraction yet.
