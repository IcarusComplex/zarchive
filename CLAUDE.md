# ZArchive

Kotlin Compose Desktop app that searches South African MTG stores for card singles
and shows official card art from Scryfall.

## Architecture

- `data/` — models (`SearchResult`, `Platform`, `STORES`, `KNOWN_PLATFORMS`) + price/relevance helpers
- `network/` — per-platform searchers (`Searchers.kt`), `BrowserSearcher.kt` (Playwright for
  Cloudflare/JS-token stores), `ScryfallFetcher.kt` (batch card-image lookup)
- `engine/` — `SearchEngine.kt` orchestrates concurrent store searches + the shared Ktor client
- `ui/` — Compose views (`App.kt`), `SearchViewModel.kt`
- `build-tools/` — packaging helpers: `MakeIco.java` (PNG→multi-res `.ico` generator),
  `ZArchive.ico` (generated app icon), `README.txt` (canonical end-user readme copied into builds)

Run with `.\gradlew.bat run` from the project root (injects `mtg.debug=true`).

> **Name:** the app is **ZArchive**. The earlier working title `mtgstorequery` is retired and must
> not appear in user-facing identifiers. (The on-disk project folder is still `…\Kotlin\mtgstorequery\`
> — that's a dev path only, intentionally not renamed.) The Gradle project is `zarchive`
> (`settings.gradle.kts`), so the app jar is `zarchive-<version>.jar`.

### Where data lives (per-user, outside the app folder)

The app never writes into its own install/program folder at runtime:

- **Image cache:** `~/.zarchive/images/<sha1>.jpg` (`CardImageService.dir`, under `user.home`). A
  packaged build's working dir may be read-only, so this MUST stay an absolute user-home path — never
  a relative path like `assets/`.
- **Settings:** `java.util.prefs` node `zarchive` (on Windows: `HKCU\Software\JavaSoft\Prefs\zarchive`).
  Holds the `ignoreBasicLands` and `autoOpenLuckshack` toggles.
- **Debug dumps:** `~/zarchive-debug/` — only written when `mtg.debug=true` (i.e. `gradlew run`),
  never in a packaged distribution.

### Bundled resources load from the classpath

`app_icon.png` and `banner_logo.png` live in `src/main/resources/` and are loaded via
`Thread.currentThread().contextClassLoader.getResourceAsStream(...)` (in `Main.kt` and `App.kt`),
**not** from a relative `resources/` file path. This is required so they resolve both under
`gradlew run` and inside the packaged app (where there is no working-dir `resources/` folder).
The originals also still exist in the top-level `resources/` dir (source of truth for the `.ico`).

## Behaviour notes

- Concurrent store searches via `async/await` on `Dispatchers.IO`.
- Platform auto-detected per store, cached per session; `KNOWN_PLATFORMS` overrides detection.
- **Luckshack** is Cloudflare-protected — we no longer scrape it; `searchLuckshack` returns a
  placeholder row linking to the store's search URL. A persisted user setting (`SearchViewModel
  .autoOpenLuckshack`, stored via `java.util.prefs`, toggled by the "Auto-open Luckshack" checkbox
  in the results toolbar) controls behaviour: **checked** auto-opens the search in the default
  browser; **unchecked** (default) shows a clickable "Click to search Luckshack" row instead. The
  flag is threaded `runSearch → BrowserSearcher(autoOpenLuckshack)`.
- **The Warren** is the only Playwright-driven store (JS-token-protected). It runs **headless**
  (`BrowserSearcher.launchRealBrowser` → `setHeadless(true)`) — no visible browser window.
- **Card images** (`network/CardImageService.kt`): each store listing's image is matched against the
  listing's *own* title (so "Shadowspear" and "Mardu Shadowspear" get different art), not the search
  query. `data.normalizeCardName` cleans the messy title (strips `[set]`/`(promo)`/condition/foil
  keywords, trailing collector numbers, `- Set` suffixes), then:
    1. **local disk cache** is checked first — `~/.zarchive/images/<sha1(name)>.jpg`;
    2. misses are resolved via Scryfall batch `/cards/collection` (exact name, ≤75/req);
    3. names that don't exact-match fall back to `/cards/named?fuzzy=`;
    4. the image is downloaded once and written to the disk cache.
  Resolution runs incrementally per store as results stream in (deduped by title). Scryfall **requires
  both a `User-Agent` and `Accept` header** (400s otherwise), so the service uses its own Ktor client
  with `Accept: application/json` — not the browser-header store client.
- Images are keyed in the UI by listing title (`vm.images[title]` → local file path); the search field
  auto-focuses on launch.
- MTGGoldfish price comparison was **removed** — SA secondary-market prices don't track USD and we
  don't always match the exact printing.

### Per-platform pricing & availability (matching what the store website shows)

These were the hard-won fixes — keep them:

- **Shopify** (`searchShopify`): the `/search/suggest.json` endpoint only returns the *minimum* variant
  price, which often mismatches the website. So for each candidate we additionally fetch
  `/products/{handle}.json` and use **`variants[0].price`** — Shopify's default displayed variant on
  the product page (typically NM non-foil), which is what the site shows. Done concurrently with a
  `Semaphore(3)`. **Availability comes from the suggest API**, not the product JSON — the product
  JSON's per-variant `available` field is unreliable/absent on the public endpoint (defaulting it to
  `true` made out-of-stock items show as in-stock).
- **WooCommerce** (`searchWooCommerce`): SA prices use comma-as-decimal (`R30,00`). `data.parsePrice`
  detects European decimal format (`\d,\d{2}` ending) and parses it correctly — otherwise `R30,00`
  was read as `R3000`. Also prefer the sale price: select `.price ins` (sale) before `.price`
  (which includes the struck-through original `<del>`).
- `parsePrice` lives in `data/Models.kt`; treat it as the single source of truth for ZAR parsing.

### Results UI behaviours

- **Per-card section borders:** each card's results group is wrapped in a `Surface` (8px rounded,
  `SurfaceContainerLowest`, 1px `OutlineVariant` border) with a header row + divider, so it's visually
  obvious where one card's results start and end.
- **Per-store status panel:** the status row has a collapsible, two-column grid of every store with a
  live state — PENDING (hourglass) / CHECKING (gold sync) / DONE (emerald check). Backed by
  `SearchViewModel.storeStatuses: mutableStateMapOf<String, StoreStatus>` and the engine's
  `onProgress(storeName)` / `onStoreComplete(storeName)` callbacks (both thread the store name).
- **Card summary hover popup:** hovering a card-summary entry shows the card art in a `Popup` positioned
  to the **right** of the entry (falls back to the left if it won't fit). No debounce. Same size as the
  results-table popup (`POPUP_CARD_W`/`POPUP_CARD_H`). Images are eagerly pre-warmed into a shared
  `imageCache` via `LaunchedEffect` so the preview shows immediately, not only after a table row was
  hovered.
- **Luckshack fast-path:** when `autoOpenLuckshack` is off, `BrowserSearcher.search` returns the
  placeholder *before* touching the single-threaded Playwright executor, so Luckshack completes
  instantly instead of queuing behind The Warren's multi-minute session.

## Design system — "Arcane Market Ledger"

Source design: `C:\Users\nicob\Downloads\stitch_multi_store_product_aggregator\` (DESIGN.md + HTML mock).
A dense, "void"-dark data-table aesthetic for power users. Information density over whitespace;
gold accents for prices/primary actions; tonal layering (not drop shadows) for depth.

### Colors (defined in `ui/App.kt`)

| Role | Hex |
|---|---|
| background / surface | `#0B1326` |
| surface-container-lowest | `#060E20` |
| surface-container-low (table wrapper) | `#131B2E` |
| surface-container (table header row) | `#171F33` |
| surface-container-high (row hover) | `#222A3D` |
| surface-container-highest (chips/thumb bg) | `#2D3449` |
| on-surface (primary text) | `#DAE2FD` |
| on-surface-variant (secondary text) | `#D0C5AF` |
| outline | `#99907C` |
| outline-variant (borders) | `#4D4635` |
| **primary (gold — prices, actions)** | `#F2CA50` |
| on-primary | `#3C2F00` |
| secondary (indigo) | `#C0C1FF` |
| on-secondary-container (store name) | `#B0B2FF` |
| **tertiary (emerald — in-stock)** | `#58E7AA` |
| error (out-of-stock / errors) | `#FFB4AB` |

### Typography

- Headlines/titles: Hanken Grotesk (fall back to default sans).
- Body/UI: Inter (fall back to default sans).
- **Prices, set numbers, data**: JetBrains Mono — use `FontFamily.Monospace` so numeric columns
  align. Prices are gold, bold, monospace.

### Layout

- Sticky header, 72px tall: gold title, inline search input (with leading search icon), ghost
  icon buttons (filter / settings) on the right.
- Content max-width 1440px, 32px horizontal margin.
- Results grouped per card. Each group: a `Matches: "<card>"` header with a listing-count badge +
  card thumbnail, then a table.
- **Table columns (strict order):** `[Thumbnail 80] [Name & Set 1fr] [Store 200] [Status 140]
  [Price 120 right] [Action 100 center]`.
- Listings split into an **In Stock** table and a dimmed **Out of Stock** table (opacity ~70%).
- Rows: hover brightens to surface-container-high; click opens the listing URL.
- Status chips: In Stock = emerald text on dark-green w/ inner glow; Out of Stock = muted-red ghost border.
- Roundedness: inputs/buttons/chips/thumbnails 4px; cards/containers 8px.
- Ignore the marketing footer/sitemap from the HTML mock — the page ends at the results.

## Preserved functionality (do not drop when restyling)

- Multi-card input (one card per line; Enter searches, Shift+Enter newline).
- **Sort:** fixed **price-ascending** (`List<SearchResult>.sortedByPrice()` — nulls last). The old
  Default/Name/Price sort controls were **removed** by request; do not reintroduce sort buttons.
- "Copy results" export to clipboard.
- Per-card "Not stocked" store summary + error rows.
- Search progress bar + status text; Cancel button while searching.
- Debug chip (opens debug dump folder) when `mtg.debug=true`.

## Building & distribution (Windows)

Self-contained Windows distribution via the Compose Desktop / jpackage `createDistributable` task —
bundles a JRE so end users install nothing.

- **Build:** `.\gradlew.bat createDistributable`
  → app image at `build\compose\binaries\main\app\ZArchive\` containing `ZArchive.exe`, a bundled
  JRE (`runtime\`), and the app jars (`app\`). Double-clicking `ZArchive.exe` runs it.
- **Package for sharing:** zip that `ZArchive` folder → `dist\ZArchive-windows-x64-<version>.zip`
  (~315 MB). jpackage **wipes the app dir on each build**, so re-copy `build-tools\README.txt` into it
  before zipping.
- **Config** (`build.gradle.kts` → `compose.desktop.application.nativeDistributions`):
  `packageName = "ZArchive"`, `includeAllModules = true` (fat-but-safe JRE — every JDK module, ~156 MB),
  and `windows { iconFile = build-tools/ZArchive.ico; menuGroup; menu; shortcut; upgradeUuid }`.
- **App icon:** Windows needs a `.ico`. `build-tools\MakeIco.java` generates `build-tools\ZArchive.ico`
  (multi-res 16–256px, PNG-compressed entries, padded to square) from `resources\app_icon.png`.
  Regenerate with: `javac -d build-tools build-tools\MakeIco.java; java -cp build-tools MakeIco resources\app_icon.png build-tools\ZArchive.ico`.
- **MSI installer:** not built. `targetFormats(Msi)` is declared and the `windows{}` Start-Menu options
  are set, but `packageMsi` needs the **WiX 3.14 toolset** installed (absent here). The portable zip is
  the shipping artifact; it has no auto Start-Menu entry (users can pin `ZArchive.exe` manually).
- **Size note:** ~191 MB of the zip is Playwright's `driver-bundle` jar, which ships the driver for all
  5 OS/arch targets though only `win32_x64` is used. Kept bundled by choice (reliable headless browser
  for The Warren). The JRE (`includeAllModules`) is the other large chunk. Both could be trimmed
  (win32-only repack + jlink module detection ≈ halves the zip) if size ever matters.
- **First-run friction:** the exe is **unsigned**, so Windows SmartScreen shows "Windows protected your
  PC" → users click "More info" → "Run anyway". Documented in `build-tools\README.txt`.
