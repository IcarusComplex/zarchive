# ZArchive

**ZArchive** searches South African Magic: The Gathering stores for card singles — all at once, in one place. Paste a list of cards you need, hit search, and see what's in stock and at what price across every supported SA store. Includes an order optimiser that tells you the cheapest buying plan and how to cover your list from the fewest shops.

> **Store owner?** If you'd like your store added or removed from ZArchive, please [open a GitHub Issue](https://github.com/IcarusComplex/zarchive/issues/new) with the label **`store-request`** and include your store's name and URL. I'll get back to you there.

---

## Download & Install

Go to the [**Releases page**](https://github.com/IcarusComplex/zarchive/releases) and download the zip for your platform from the latest **Stable** release.

### Windows

1. Download `ZArchive-windows-x64-<version>.zip`.
2. Right-click it → **Extract All…** → choose a destination (Desktop or Documents works well).
3. Open the extracted `ZArchive` folder and double-click **ZArchive.exe**.

Keep everything inside the `ZArchive` folder together — the `.exe` needs the `app\` and `runtime\` folders next to it. You can move or rename the folder freely; it's fully self-contained.

#### First launch — Windows SmartScreen

Because ZArchive is not commercially code-signed, Windows shows a "Windows protected your PC" warning the first time you run it.

1. Click **"More info"**.
2. Click **"Run anyway"**.

You only need to do this once per installation.

### macOS (Apple Silicon)

1. Download `ZArchive-macos-arm64-<version>.zip`.
2. Double-click the zip to extract it — you'll get **ZArchive.app**.
3. Move `ZArchive.app` wherever you like (Desktop, Applications, anywhere).
4. Double-click **ZArchive.app** to run it.

#### First launch — macOS Gatekeeper

Because ZArchive is not notarised with Apple, macOS will block it on first open. Pick either method:

**Option A — System Settings (no Terminal needed)**
1. Try to open ZArchive.app — click **Done** on the warning dialog.
2. Open **System Settings → Privacy & Security**.
3. Scroll down — you'll see *"ZArchive was blocked"*.
4. Click **Open Anyway**, enter your password, then open the app again.

**Option B — Terminal (one command)**
```
xattr -dr com.apple.quarantine /path/to/ZArchive.app
```
Then double-click normally — no more dialogs.

You only need to do this once.

> **Intel Mac:** The current release targets Apple Silicon only. Intel support may be added in future.

---

## How to Use

### Searching for cards

1. Type one card name per line in the search box on the left.
2. Press **Alt+Enter** (or click the **Search** button) to start.
3. Results stream in per store as they respond — you don't have to wait for everything to finish.

**Example:**
```
Shadowspear
Lightning Bolt
Thoughtseize
```

### Pasting a decklist

ZArchive understands standard decklist format. Paste directly from MTGO, Moxfield, or any deck builder — quantity prefixes are stripped automatically:

```
4x Lightning Bolt
2 Thoughtseize
1 Shadowspear
[Creatures]       ← section headers are ignored
# comment         ← comment lines are ignored
```

### Reading the results

Results are grouped per card. Each group shows:

| Column | What it means |
|---|---|
| **Name & Set** | The store's listing title (may include edition or condition) |
| **Store** | Which shop stocks it |
| **Status** | In Stock (green) or Out of Stock (dimmed) |
| **Price** | Listed price in ZAR |
| **Action** | Opens the listing in your browser |

In Stock and Out of Stock listings are split into separate tables. Click any row to open the product page. Hovering a thumbnail shows a larger card art preview.

### Card Summary panel

A collapsible **Card Summary** panel above the results shows at a glance which cards were found and which weren't.

### Order Lists tab

Switch to the **Order Lists** tab (appears after a search) to see two buying plans:

- **Cheapest total** — picks the cheapest in-stock listing for each card, grouped by store. Best when price is everything.
- **Fewest packages** — covers your full list from the smallest number of stores. Best when you want to minimise shipping costs.

Each plan shows the total cost and flags any cards not available anywhere. Click a store name to open its homepage; click a listing row to open the product page.

---

## Settings

Settings are available in the left panel and the gear menu in the title bar. All settings persist between sessions.

### Left panel toggles

| Setting | Default | What it does |
|---|---|---|
| **Ignore basic lands** | On | Strips Plains, Island, Swamp, Mountain, Forest (and snow variants) from your search list. |
| **Include The Warren** | Off | Includes The Warren in searches. It's slower than other stores because it uses a headless browser to bypass JS protection — expect a few extra seconds per search. |
| **Auto-open Luckshack** | Off | Opens each card's Luckshack search in your browser when a search starts. Luckshack is Cloudflare-protected and can't be searched directly — this is the next best thing. |

### Gear menu (title bar)

| Setting | What it does |
|---|---|
| **Early Access** | Opt in to pre-release builds. These may have new features or experimental fixes not yet in a stable release. |
| **Check for updates** | Manually trigger an update check. Shows the current version. |
| **Report a bug** | Opens a GitHub issue in your browser with the `bug` label pre-applied. |

### Luckshack links

Even with Auto-open Luckshack off, every card in the results has a **Luckshack** chip you can click to open that card's search in your browser.

---

## Automatic Updates

ZArchive checks for updates every time it launches. A slim status bar appears at the bottom of the window while checking, and briefly shows the result.

If a newer version is available, a dialog appears. Click **Download & Install** — ZArchive will download the update in the background (progress shown), then close itself, replace its own files, and relaunch automatically. No manual zip extraction needed.

To opt in to pre-release (Early Access) builds, enable **Early Access** in the gear menu. Pre-release builds may have new features not yet in a stable release.

---

## Where ZArchive stores data

ZArchive never writes into its own installation folder at runtime.

### Windows

| What | Where |
|---|---|
| Card image cache | `C:\Users\<you>\.zarchive\images\` |
| Settings | Windows registry: `HKCU\Software\JavaSoft\Prefs\zarchive` |

### macOS

| What | Where |
|---|---|
| Card image cache | `~/.zarchive/images/` |
| Settings | `~/Library/Preferences/co.za.zarchive.plist` (via Java Preferences API) |

The image cache can be safely deleted at any time — ZArchive will re-download art on next search.

---

## Uninstalling

### Windows

1. Delete the `ZArchive` folder.
2. Delete `C:\Users\<you>\.zarchive\` (image cache — optional).
3. To remove settings: open **Registry Editor**, navigate to `HKCU\Software\JavaSoft\Prefs\zarchive`, and delete the key.

### macOS

1. Delete `ZArchive.app`.
2. Delete `~/.zarchive/` (image cache — optional).
3. To remove settings: delete `~/Library/Preferences/co.za.zarchive.plist`.

---

## Troubleshooting

**"Windows protected your PC" / can't open the app**
Follow the SmartScreen steps above (More info → Run anyway). You only need to do this once.

**macOS says the app can't be opened / "Done" or "Move to Bin" dialog**
Go to **System Settings → Privacy & Security**, scroll down, and click **Open Anyway** next to ZArchive. Alternatively, run this in Terminal:
```
xattr -dr com.apple.quarantine /path/to/ZArchive.app
```
Then double-click normally.

**"A store always shows no results"**
Some stores use Cloudflare protection or rate-limiting. Try searching again later. If a store consistently returns nothing, it may be temporarily blocking automated requests.

**"Card art is not loading"**
Art is fetched from Scryfall and cached locally. Check your internet connection. If art was loading before but stopped, delete `~/.zarchive/images/` and search again.

**"I can't see the Order Lists tab"**
The tab only appears after you've run at least one search.

**"Prices look wrong"**
ZArchive pulls prices directly from each store at search time. If a price looks off, click the row to open the store's own product page and verify.

**"The Warren is missing from results"**
The Warren is excluded by default because it uses a headless browser and is noticeably slower. Enable **Include The Warren** in the left panel to include it.

---

## Supporting ZArchive

ZArchive is free and always will be. If it saves you time or money hunting singles, a Ko-fi is always appreciated — it helps cover ongoing development time.

[![Support on Ko-fi](https://img.shields.io/badge/Support%20on%20Ko--fi-%23FF5E5B?style=flat-square&logo=ko-fi&logoColor=white)](https://ko-fi.com/icaruscomplexza)

No pressure at all — enjoy the app either way.

---

## About

ZArchive is free, open-source software released under the [MIT License](LICENSE).

Built with [Kotlin](https://kotlinlang.org/) + [Compose Desktop](https://www.jetbrains.com/lp/compose-multiplatform/). Card art via [Scryfall](https://scryfall.com/).

Contributions and bug reports welcome on the [Issues page](https://github.com/IcarusComplex/zarchive/issues).
