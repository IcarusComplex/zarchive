# ZArchive

**ZArchive** searches South African Magic: The Gathering stores for card singles — all at once, in one place. Paste a list of cards you need, hit search, and see what's in stock and at what price across every supported SA store. Includes an order optimiser that tells you the cheapest buying plan and how to cover your list from the fewest shops.

> **Supported stores:** The Unseen Hand · The Warren · Axion Now · Dragon's Lair · Something2Do · and more.

---

## Download & Install

### Step 1 — Download the latest release

1. Go to the [**Releases page**](https://github.com/IcarusComplex/zarchive/releases).
2. Under the latest **Stable** release, click the `.zip` file (e.g. `ZArchive-windows-x64-1.0.0.zip`) to download it.

### Step 2 — Extract the zip

1. Find the downloaded zip in your Downloads folder.
2. Right-click it → **Extract All…**
3. Choose a destination — your Desktop or Documents folder works well.
4. Click **Extract**.

You'll get a folder called `ZArchive`. Keep everything inside it together — the `.exe` needs the `app\` and `runtime\` folders next to it.

> **Tip:** You can rename or move the `ZArchive` folder anywhere you like. It's fully self-contained — no installer, no registry entries from this step.

### Step 3 — Run ZArchive

Double-click **ZArchive.exe** inside the extracted folder.

---

## First Launch — Windows SmartScreen Warning

Because ZArchive is not commercially code-signed, Windows may show a blue "Windows protected your PC" screen the first time you run it. This is normal for free, open-source software.

**To proceed:**
1. Click **"More info"** (small link below the main text).
2. Click **"Run anyway"**.

You only need to do this once per installation.

---

## How to Use

### Searching for cards

1. Type one card name per line in the search box.
2. Press **Alt+Enter** (or click the **⌕** search button) to start searching.
3. Results stream in as each store responds — you don't have to wait for every store to finish.

**Example:**
```
Shadowspear
Lightning Bolt
Thoughtseize
```

### Pasting a decklist

ZArchive understands standard decklist format. Paste directly from MTGO, Moxfield, or any other deck builder — quantity prefixes are automatically stripped:

```
4x Lightning Bolt
2 Thoughtseize
1 Shadowspear
[Creatures]       ← section headers are ignored
# comment         ← comment lines are ignored
```

### Reading the results

Results are grouped per card. Each card section shows:

| Column | What it means |
|---|---|
| **Name & Set** | The store's listing title (may include edition or condition) |
| **Store** | Which shop stocks it |
| **Status** | In Stock (green) or Out of Stock (dimmed) |
| **Price** | Listed price in ZAR |
| **Action** | Opens the listing in your browser |

**In Stock** and **Out of Stock** listings are split into separate tables. Click any row to open the product page.

Hovering a card thumbnail shows a larger card art preview.

### Card Summary panel

A collapsible **Card Summary** panel above the results shows at a glance which cards were found and which weren't. Click it to expand or collapse.

### Order Lists tab

Switch to the **Order Lists** tab (appears after a search) to see two buying plans:

- **Cheapest total** — picks the single cheapest in-stock listing for each card, grouped by store. Best when price is everything.
- **Fewest packages** — covers your full list from the smallest number of stores. Best when you want to minimise shipping costs / number of orders.

Each plan shows the total cost and flags any cards not in stock anywhere.

Click a store name to open its homepage. Click a listing row to open the specific product page.

---

## Settings

Two checkboxes are available in the header:

| Setting | Default | What it does |
|---|---|---|
| **Ignore basic lands** | On | Strips Plains, Island, Swamp, Mountain, Forest (and snow-covered variants) from your search list. Turn off if you genuinely need to buy basics. |
| **Auto-open Luckshack** | Off | When on, each card's Luckshack search automatically opens in your browser when you start a search. Luckshack is Cloudflare-protected and can't be searched directly by ZArchive — this is the next best thing. |

Settings are remembered between sessions.

### Luckshack links

Even with Auto-open Luckshack off, each card in the results has a **Luckshack** chip you can click to open that card's search on Luckshack in your browser.

---

## Automatic Updates

ZArchive checks for updates when it launches. If a newer stable version is available on GitHub, it will prompt you — you can update with one click. The app downloads the new version, replaces itself, and relaunches automatically.

To opt in to early-access (beta) builds, enable **Early Access** in settings. Early builds may have new features or experimental fixes not yet in a stable release.

---

## Where ZArchive stores data

ZArchive never writes into its own program folder. Your data lives here:

| What | Where |
|---|---|
| Card image cache | `C:\Users\<you>\.zarchive\images\` |
| Settings | Windows registry: `HKCU\Software\JavaSoft\Prefs\zarchive` |

The image cache can be safely deleted at any time — ZArchive will re-download art as needed.

---

## Uninstalling

1. Delete the `ZArchive` folder.
2. Delete `C:\Users\<you>\.zarchive\` (card image cache — optional, ~varies).
3. To remove settings: open **Registry Editor**, navigate to `HKCU\Software\JavaSoft\Prefs\zarchive`, and delete the key.

No other files are created anywhere on your system.

---

## Troubleshooting

**"The app opened and immediately closed"**
This usually means Windows blocked it. Run ZArchive.exe once from File Explorer (not a shortcut), follow the SmartScreen steps above, then it will work normally.

**"A store always shows no results"**
Some stores have Cloudflare protection or rate-limiting. Try searching again. If a store consistently returns nothing, it may be temporarily rate-limiting ZArchive.

**"Card art is not loading"**
Art is fetched from Scryfall and cached locally. Check your internet connection. If art previously loaded but isn't now, delete `C:\Users\<you>\.zarchive\images\` and search again.

**"I can't see the Order Lists tab"**
The tab only appears after you've run at least one search.

**"Prices look wrong"**
ZArchive pulls prices directly from each store's website at search time. If a price looks off, click the listing row to open the store's own page and verify.

---

## Supporting ZArchive

ZArchive is free and always will be. If it saves you time or money hunting singles, a coffee is always appreciated — it helps cover ongoing development time.

[![Buy Me A Coffee](https://img.shields.io/badge/Buy%20me%20a%20coffee-%E2%98%95-yellow?style=flat-square)](https://buymeacoffee.com/icaruscomplex)

No pressure at all — enjoy the app either way.

---

## About

ZArchive is free, open-source software released under the [MIT License](LICENSE).

Built with [Kotlin](https://kotlinlang.org/) + [Compose Desktop](https://www.jetbrains.com/lp/compose-multiplatform/). Card art via [Scryfall](https://scryfall.com/).

Contributions and bug reports welcome on the [Issues page](https://github.com/IcarusComplex/zarchive/issues).
