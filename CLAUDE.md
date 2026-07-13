# ZArchive

Kotlin Compose Desktop app that searches South African MTG stores for card singles
and shows official card art from Scryfall.

## Architecture

- `data/` — models (`SearchResult`, `Platform`, `STORES`, `KNOWN_PLATFORMS`) + price/relevance helpers
- `network/` — per-platform searchers (`Searchers.kt`), `BrowserSearcher.kt` (Playwright for
  Cloudflare/JS-token stores), `ScryfallFetcher.kt` (batch card-image lookup),
  `CardImageService.kt` (disk-cached Scryfall art), `GitHubService.kt` (update checks + downloads)
- `engine/` — `SearchEngine.kt` orchestrates concurrent store searches + the shared Ktor client
- `ui/` — Compose views (`App.kt`), `SearchViewModel.kt`
- `build-tools/` — packaging helpers: `MakeIco.java` (PNG→multi-res `.ico` generator),
  `ZArchive.ico` (generated app icon), `README.txt` / `README-mac.txt` (end-user readmes copied into builds)

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
- **Local database:** `~/.zarchive/zarchive.mv.db` — H2 embedded file-mode database (JetBrains
  Exposed 0.50.1 + H2 2.2.224). Stores settings (replacing `java.util.prefs` / Windows registry
  after a one-time migration on first run) and saved search lists. `AppDatabase.kt` owns the
  schema; `SearchListRepo.kt` owns list CRUD. On first run, a seed list "Aragorn & Arwen EDH" is
  inserted. The `_migrated_from_prefs` and `_seed_v1_lists` flags in the `settings` table guard
  both one-time operations.
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
- Platform auto-detected per store; **only successful detections are cached** for the session
  (`platformCache`, a `ConcurrentHashMap`). `UNKNOWN`/`UNREACHABLE` are *not* cached, so a transient
  network blip or momentary rate-limit doesn't strand a store as "check manually" until restart —
  it's retried on the next search. `KNOWN_PLATFORMS` overrides detection.
- **Luckshack** is Cloudflare-protected and **not a store/search result at all** — it's been removed
  from `STORES`, so it never produces result rows or appears in order lists. It surfaces only as a
  per-card **convenience link** (`ui/App.kt LuckshackLinks`, rendered outside the results/order panes)
  built from `data.luckshackSearchUrl(card)`. The persisted `SearchViewModel.autoOpenLuckshack`
  setting (prefs node `zarchive`, "Auto-open Luckshack" checkbox) still works: when **on**, running a
  search opens each card's Luckshack search in the browser (`SearchViewModel.openLuckshackSearches`);
  when **off** (default), the user clicks the link chips manually.
- **Relevance / matching** (`data.isRelevant` + `data.preferExactMatches`):
  - `isRelevant` requires every significant (>2-char) query word to appear as a **whole word** in the
    title (not a substring — so "Hop to It" → "hop" no longer matches "Hope Thief"), and rejects
    titles matching `NON_SINGLE_RE` (binders, sleeves, booster boxes, "Ultimate Guard", etc. — sealed
    product / accessories that share a card's name but aren't singles).
  - `preferExactMatches(card, listings)` keeps only listings whose normalised name (or a `//` face)
    **equals** the query when any such exact match exists, else returns all. Stops "Reprieve" from
    surfacing "Graceful Reprieve". Applied at every display/use point: `CardSection`, the card summary,
    and both order-list plans.
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
  auto-focuses on launch. The **searched card names** are also resolved up front (keyed by the card
  name) so the summary / order-list / thumbnails **fall back** to the card's art (`images[card]`) when
  a messy listing title fails to resolve its own printing.
- MTGGoldfish price comparison was **removed** — SA secondary-market prices don't track USD and we
  don't always match the exact printing.

### Per-platform set hint extraction (for correct Scryfall art)

`SearchResult.setHint` carries an authoritative set name/code from the store's structured payload.
`CardImageService.extractMeta` uses it in preference to title-derived guesses when present.
`SearchViewModel` passes a `Map<title, setHint?>` to `resolveImages` so the hint travels through.

| Platform | Payload source | What we extract | Notes |
|---|---|---|---|
| **Shopify** | `suggest.json` body HTML | `<td>Set:</td><td>SET NAME</td>` table row | D20 Battleground style; Jsoup parse of `body` field |
| **Shopify** | `suggest.json` body HTML | `<b>Set:</b> Set Name (CODE)` bold-label format | Wzrd TCG style; parsed as fallback after table-row check; yields e.g. "Chronicles (CHR)" which `findCode` resolves via substring match |
| **Shopify** | `/products/{handle}.js` options array | `options[n].name == "Set"` → `option{n}` on first variant | Some Shopify stores; variant setHint wins over body setHint |
| **Shopify** | `suggest.json` product `tags` array | First tag matching `Set:<value>` prefix, value taken verbatim | Untapped Potential TCG; value is either a bare set name (`"Set:Lorwyn Eclipsed"`, resolved via `findCode`) or a `CODE-collectorNum` SKU (`"Set:TDM-358"`, resolved via `PAREN_SET_CODE_RE`); checked as fallback after body-HTML table/bold-label parsing comes up empty |
| **WC Store API** | `short_description` + `sku` JSON fields | `short_description`: last `\|` segment if ≥ 2 pipes, or full text if ≤ 60 chars; otherwise `sku` field (e.g. `"RVR-347"`) which `PAREN_SET_CODE_RE` resolves to a set code | The Hidden Realm; SKU is the reliable fallback — `short_description` contains card mechanic text, not set info |
| **WooCommerce HTML** | Product page HTML (redirect case + list tiles) | `.woocommerce-product-details__short-description` last `\|` segment | Only if text contains ≥ 2 pipes; null otherwise |
| **Warren (headless)** | `product_name` field | Not needed — `product_name` is always `"Card Name - Set Name"` | `DASH_SUFFIX_RE` in `extractSetNameHint` handles it |
| **CardCache (Shopify)** | Title text `[SET - #N]` | Set code extracted by `SET_CODE_RE` | No `setHint` needed; title format is sufficient |
| **BigCommerce** | Search result HTML tiles | Nothing — product tiles have no set field | `setHint` always null for Battle Wizards |
| **OpenCart** | Search result HTML tiles | Nothing — product tiles have no set field | No stores currently active |
| **PrestaShop** | Search listing HTML | Nothing structured available | Product page fetched for cart ID but no consistent set field in AI Fest's HTML |

Key implementation points:
- `PAREN_SUFFIX_RE` has **no end anchor** — it uses `findAll` so `"Card (Set Name) [#242]"` correctly finds the paren group and the `COLLECTOR_NUM_RE` filter (`^#\d+$`) rejects the bracket token.
- `CODE_NUMBER_RE` (`^[A-Z]{2,6}-\d+$`) rejects `"WHO-823"` style collector-reference groups from paren/bracket extraction — without this, `"(WHO-823)"` would be returned as a setName, `findCode("WHO-823")` would fail (no set named that, and "who" is only 3 chars, below the step-3 minimum), and the `else`/`PAREN_SET_CODE_RE` branch would never be reached. Filtered here → `else` branch extracts `"WHO"` correctly.
- `BRACKET_SET_NAME_RE` catches D20-style `[Long Set Name]` suffixes; `SET_CODE_RE` catches short `[RNA]` codes and `[PCY - 45]` with collector number.
- `PAREN_SET_CODE_RE` catches `"(CODE-number...)"` paren notation where the set code is embedded with a collector number, e.g. `"(WHO-823 - Doctor Who Foil)"` → extracts `"WHO"`, validates against `ScryfallSetIndex.isKnownCode`. This runs **only when setCode and setName are both null** (the paren body was filtered by NOISE_RE), so it can't misfire on normal set-name parens. **NOISE_RE must never be the last resort** — if a paren group contains noise words (like "Foil") the body as a whole is filtered, but the embedded code prefix is still recoverable via this pattern.
- `extractMeta` priority: **treatment** (showcase/borderless/etc.) → **setCode** from title brackets → **externalSetHint** from payload → **extractSetNameHint** (title parsing). When treatment is detected and no bracket setCode is present, the set hint is still captured in `setName` so `resolveImages` can resolve it to a code and pass it to `treatmentSearch` (e.g. "Extended Art" + "Streets of New Capenna" → `set:snc frame:extendedart`). When both treatment and a bracket setCode are present, setName is left null (setCode is passed directly to `treatmentSearch`).
- `resolveImages` meta-resolution priority: setCode already set → setName → findCode() → `PAREN_SET_CODE_RE` + isKnownCode(). The last two passes handle: (a) `CODE-NUMBER` paren groups like `(WHO-823)` that NOISE_RE rejects as whole candidates but whose code prefix is still valid; (b) `CODE-NUMBER` SKUs like `"RVR-347"` passed as setName when findCode returns null.

### Scryfall set index (`network/ScryfallSetIndex.kt`)

`ScryfallSetIndex` downloads the full Scryfall set catalogue (`https://api.scryfall.com/sets`) once and caches it at `~/.zarchive/sets.json` (7-day TTL). It maps set names → set codes and tracks parent → child set relationships from Scryfall's `parent_set_code` field.

**Token/minigame sets are excluded** from `nameToCode` during parsing — stores never sell singles from them, and including e.g. "Warhammer 40,000 Tokens" would cause `findCode("Warhammer 40,000")` to return `"t40k"` (shorter than "Warhammer 40,000 Commander") instead of `"40k"`.

**`findCode(hint)`** resolves a store-supplied set name to a Scryfall set code:
1. Strip `"Universes Beyond: "` brand prefix (stores include it; Scryfall set names don't).
2. Exact name match (case-insensitive).
3. Hint is a substring of a known set name → take shortest match. Handles "Warhammer 40,000" → "Warhammer 40,000 Commander" (`40k`).
4. A known set name is a substring of the hint → take longest match. Handles extra category words.

**`isKnownCode(code)`** returns true if a string is a valid Scryfall set code (e.g. `"WHO"` → true). Used by `CardImageService` to validate codes extracted from `PAREN_SET_CODE_RE`.

**`childCodesOf(code)`** returns child set codes for a parent (e.g. `"blb"` → `["blc"]`). Used in `CardImageService.resolveImageUrls` step 1.5: if the card isn't found in the exact resolved set, the image service retries with child sets (Commander precons, etc.) before falling back to fuzzy search. Results are cached under the *parent* set's cache key — any printing from the same set family is acceptable.

**Resolution pipeline in `CardImageService.resolveImages`:**
1. `extractMeta` → base meta (setCode from title brackets, or setName from payload/title).
2. `setIndex.findCode(setName)` or `PAREN_SET_CODE_RE` + `isKnownCode` → resolve to setCode.
3. Batch `/cards/collection` with setCode (step 1).
4. Child-set batch for metas not found in step 1 (step 1.5).
5. `e:"setName"` search for remaining unresolved metas with a setName (step 2).
6. Treatment-specific search (step 3).
7. Fuzzy `/cards/named` fallback (step 4).

### Per-platform pricing & availability (matching what the store website shows)

These were the hard-won fixes — keep them:

- **Shopify** (`searchShopify`): the `/search/suggest.json` endpoint only returns the *minimum* variant
  price, which often mismatches the website. So for each candidate we additionally fetch
  `/products/{handle}.js` and use the **first available variant's price** — matching what Shopify
  shows on the product page when a lower-priced condition variant (e.g. MP) is out of stock and the
  NM copy is the live default. Falls back to `variants[0].price` if no variant reports available.
  Done concurrently with a `Semaphore(3)`. **Availability comes from the suggest API**, not the
  product JSON — the product JSON's per-variant `available` field is unreliable/absent on the public
  endpoint (defaulting it to `true` made out-of-stock items show as in-stock).
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
  - **Flicker fix:** the popup `Box` has its own `MutableInteractionSource` (`popupInteraction`);
    `showPopup = hoveredRaw || popupHovered` keeps the popup open while the cursor crosses the gap
    between the entry and the card image. Without this, moving the mouse from the entry onto the popup
    itself caused a hover-exit on the entry, collapsing the popup mid-hover.
- **Top-level tabs (`ResultsTab`):** the app splits into two panes — **Search Results**
  (`SearchResultsTab`: the card summary + per-card sections) and **Order Lists** (`OrderListsPane`).
  The tabs render as **folder tabs in the header banner**, below the logo and just above the divider
  line (`FolderTabs`, hoisted tab state lives in `App()`); the active tab is filled with the content
  colour + top-rounded so it reads as connected to the panel below. Tabs only show once a search has
  run. Clean split so the buying plan never clutters the live results.
- **Card-summary search field:** `CardSummaryPanel` has an internal `FilterField` ("Find a card in
  the list…") that substring-filters the summary grid by card name. `FilterField` now takes an
  optional `placeholder`.
- **Optimised order lists (`data/OrderOptimizer.kt`):** pure functions `cheapestPlan` and
  `fewestStoresPlan` build an `OrderPlan` (`List<StoreOrder>` of `OrderLine`s + `uncoveredCards`) from
  the current results. Both run **reactively** via `derivedStateOf` over `vm.results` — they recompute
  as listings stream in, never on a button. Only in-stock listings at a **named** store (`title != null
  && available != false && store.isNotBlank()`) are considered, and `preferExactMatches` is applied
  per card so near-name listings don't pollute the plan.
    - **Cheapest total:** lowest-priced listing per card anywhere, grouped by store (minimises spend).
    - **Fewest packages:** greedy set-cover picking the smallest set of stores covering every available
      card (tie-break: cheaper combined price for newly-covered cards), then each card sourced from the
      cheapest *picked* store. Minimises number of orders, price aside.
  `OrderListsPane` has a strategy toggle, a totals row (`PlanStat`), a `StoreOrderCard` per store
  (header opens the store, each `OrderLineRow` opens the listing), and an `UncoveredCard` listing
  cards not in stock anywhere. `STORES[store]` supplies the per-store header URL.

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

## Building & distribution

### Windows

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
  (multi-res 16–256px, PNG-compressed entries, padded to square) from two source images:
  `resources\app_icon.png` (sizes 256, 128, 64, 48, 32, 24) and `resources\low_res_icon.png` (size 16 only).
  Regenerate with: `javac -d build-tools build-tools\MakeIco.java; java -cp build-tools MakeIco resources\app_icon.png resources\low_res_icon.png build-tools\ZArchive.ico`.
- **MSI installer:** not built. `targetFormats(Msi)` is declared and the `windows{}` Start-Menu options
  are set, but `packageMsi` needs the **WiX 3.14 toolset** installed (absent here). The portable zip is
  the shipping artifact; it has no auto Start-Menu entry (users can pin `ZArchive.exe` manually).
- **Size note:** ~191 MB of the zip is Playwright's `driver-bundle` jar, which ships the driver for all
  5 OS/arch targets though only `win32_x64` is used. Kept bundled by choice (reliable headless browser
  for The Warren). The JRE (`includeAllModules`) is the other large chunk. Both could be trimmed
  (win32-only repack + jlink module detection ≈ halves the zip) if size ever matters.
- **First-run friction:** the exe is **unsigned**, so Windows SmartScreen shows "Windows protected your
  PC" → users click "More info" → "Run anyway". Documented in `build-tools\README.txt`.

### macOS

- **Build:** run `./gradlew createDistributable` on a macOS machine (arm64). Produces
  `build/compose/binaries/main/app/ZArchive/ZArchive.app`.
- **Package for sharing:** zip `ZArchive/ZArchive.app` + `build-tools/README-mac.txt` into
  `ZArchive-macos-arm64-<version>.zip`.
- **macOS version constraint:** jpackage requires `CFBundleVersion` MAJOR ≥ 1. The `macPackageVer`
  variable in `build.gradle.kts` remaps `0.x.y → 1.0.y`. At 1.0.0+ this passes through unchanged.
- **Gatekeeper (macOS Sequoia):** the app is unsigned. First-run requires: right-click → Open, or
  after the "can't be opened" block: System Settings → Privacy & Security → scroll down → "Open Anyway".
  Documented in `build-tools/README-mac.txt`.
- **CI:** GitHub Actions `release.yml`, `macos-latest` runner (Apple Silicon). Triggered on `v*` tag push.

### CI / GitHub Actions (`.github/workflows/release.yml`)

- Triggers on `v*` tag push to `main`.
- Builds Windows on `windows-latest`, macOS on `macos-latest`, and Android on `ubuntu-latest` — all
  three in parallel.
- Windows job zips `ZArchive/` (the full app folder including `README.txt`) → uploads as
  `ZArchive-windows-x64-<version>.zip`.
- macOS job zips `ZArchive/ZArchive.app` + `README-mac.txt` → uploads as
  `ZArchive-macos-arm64-<version>.zip`.
- Android job runs `./gradlew assembleRelease` (signed via the `ANDROID_KEYSTORE_BASE64` /
  `ANDROID_KEYSTORE_PASSWORD` / `ANDROID_KEY_ALIAS` / `ANDROID_KEY_PASSWORD` secrets — the release
  keystore noted below) → uploads a universal APK as `ZArchive-android-<version>.apk`.
- A fourth `release` job (`needs: [build-windows, build-macos, build-android]`) waits for all three,
  downloads their artifacts, and attaches all three assets to the GitHub release it creates.
- **No tokens or PATs are bundled in the artifacts** — the GitHub API is hit unauthenticated for
  update checks; only public release metadata and asset download URLs are needed.

## Auto-update system (`network/GitHubService.kt`, `ui/SearchViewModel.kt`)

Flow: app checks GitHub releases API → prompts user → downloads platform zip → extracts in-process
→ writes a swap script to a temp file → calls `exitProcess(0)` → script runs after JVM exits,
renames old install, moves new in, relaunches `ZArchive.exe` / `ZArchive.app`.

**Security:** never bundle a PAT or GitHub token in the JAR — the jar is easily reverse-engineered.
Update checks use the public `/releases/latest` endpoint (no auth required for public repos).

### `network/GitHubService.kt`

- `checkForUpdate(includePrerelease)`: hits `/releases/latest` (or `/releases` for prereleases),
  compares tag to `BuildInfo.VERSION`. Filters release assets by platform keyword in filename
  (`"windows"` or `"macos"` from `os.name`), falls back to first zip if no platform match found.
- `downloadRelease(url, dest, onProgress)`: streams with a **1 MB buffer** and **500 ms progress
  throttle** to avoid flooding the EDT. When the CDN response has no `Content-Length` header,
  passes `Float.NaN` as progress so the UI shows an indeterminate bar instead of stuck-at-0%.
  Uses `exitProcess(0)` (not `exitApplication()`) after install — `exitApplication()` waits for
  Playwright threads to shut down, which can hold file handles open during the swap.

### `ui/SearchViewModel.kt` — `buildWindowsSwapScript()`

Generated as a `.ps1` to a temp file; launched via `ProcessBuilder("powershell.exe", "-File", ...)`.

Critical constraints (all learned from failures):

- **No non-ASCII characters** in the generated script. PowerShell 5.1 reads UTF-8-without-BOM as
  ANSI — an em dash (`—`, bytes `0xE2 0x80 0x94`) crashes the parser before line 1 executes,
  leaving no log at all. Every comment must use plain ASCII dashes only.
- `$renamed` must be **initialised before the `try` block** so the `catch` restore guard can
  reference it.
- `Rename-Item` is a non-terminating cmdlet — errors do NOT trigger `catch` unless you add
  `-ErrorAction Stop`. Without it, the script logs "Renamed" and falls through even on failure.
- **5× retry loop** (2 s sleep between) before giving up on rename — JVM file handles may linger
  a moment after `exitProcess(0)`.
- **Robocopy fallback** if all rename attempts fail (Windows Explorer holds the folder open, which
  blocks `Rename-Item` indefinitely but not robocopy's file-by-file copy):
  `robocopy $src $dst /E /IS /IT /PURGE /NFL /NDL /NJH /NJS` — exit code < 8 = success.
- **Always relaunches** `ZArchive.exe` outside the `try/catch`, regardless of swap outcome.
- Log: `%TEMP%\zarchive-updater.log` — the first place to look when debugging a failed swap.

### `ui/SearchViewModel.kt` — `buildMacSwapScript()`

Generated as a `.sh` to a temp file; `chmod +x` applied before launch via `ProcessBuilder`.

Critical constraints:

- **No `set -e`** — `set -euo pipefail` (or even just `-e`) causes the script to exit silently on
  any non-zero return, including benign commands like `xattr` cleanup, leaving no relaunch.
  Use `set -uo pipefail` only (keeps undefined-var and pipe-fail protection).
- Explicit `if/else` per step with restore-on-failure: if the second `mv` (new app into place) fails,
  `mv "$BACKUP_DIR" "$INSTALL_DIR"` restores the original before relaunching.
- **Always relaunches** via `open "$INSTALL_DIR"` at the end, regardless of swap outcome.
- **`chmod` fix after extraction:** Java `ZipFile` strips Unix permission bits on extraction. After
  moving the new `.app` into place:
  - `chmod -R a+x "$INSTALL_DIR/Contents/MacOS"` — makes the main binary executable.
  - `find "$INSTALL_DIR/Contents/runtime" -path "*/bin/*" -type f -exec chmod +x {} +` — makes
    JRE binaries executable.
  - `xattr -dr com.apple.quarantine "$INSTALL_DIR"` — clears Gatekeeper quarantine on the new copy.
- Log: `$TMPDIR/zarchive-updater.log`.

### `ui/App.kt` — `DownloadProgressDialog`

Two-phase progress: `downloadPhase` state (`Downloading` / `Extracting`) shown in the dialog label.
Progress bar: indeterminate (`LinearProgressIndicator` with no `progress` arg) when `progress.isNaN()`,
determinate (`progress = { progress.coerceIn(0f, 1f) }`) otherwise. Label shows "…" when NaN.

### Debugging the update chain

The swap script that executes is **baked into the running version**, not the downloaded version.
To test a script fix in version N, you must be running N and updating to N+1 — the script in N+1
only runs the *next* time an update is applied.

Log files (`%TEMP%\zarchive-updater.log` / `$TMPDIR/zarchive-updater.log`) are the primary
diagnostic tool — check them first when a swap silently fails.

### Launcher concept (planned, not implemented)

A native `ZArchiveLauncher.exe` (Go binary, ~2 MB) that holds no JVM file handles and does the
folder swap cleanly before starting the main JVM was discussed as a long-term improvement. Not
implemented — the robocopy fallback is sufficient for now.
