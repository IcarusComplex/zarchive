ZArchive — South African MTG store singles search
==================================================

HOW TO RUN
----------
1. Open the ZArchive folder from the zip.
2. Drag ZArchive.app anywhere you like (Applications, Desktop, etc.).
3. Double-click ZArchive.app.

That's it. Java is bundled inside the .app — you do NOT need to install
anything else.

FIRST LAUNCH — GATEKEEPER WARNING
-----------------------------------
Because ZArchive is not notarised with Apple, macOS will block it the
first time. There are two ways to allow it (pick either one):

  OPTION A — System Settings (no Terminal needed):
  1. Try to open ZArchive.app — click Done on the warning dialog.
  2. Open System Settings → Privacy & Security.
  3. Scroll down — you will see "ZArchive was blocked".
  4. Click Open Anyway, enter your password, then open the app again.

  OPTION B — Terminal (one command, instant):
  Run this in Terminal, replacing the path with where you put the .app:
    xattr -dr com.apple.quarantine /path/to/ZArchive.app
  Then double-click ZArchive.app normally — no more dialogs.

You only need to do this once.

WHERE ZARCHIVE STORES DATA ON YOUR COMPUTER
-------------------------------------------
ZArchive does NOT write anything into the .app bundle while running.
Your data lives in your home directory:

- Card image cache:
      ~/.zarchive/images/
  Downloaded card art (one .jpg per card). Safe to delete anytime — it
  just re-downloads on the next search.

- Settings (toggles / preferences):
      ~/Library/Preferences/co.za.zarchive.plist
  Tiny; remembers your choices between runs.

To fully remove ZArchive: delete ZArchive.app, then delete ~/.zarchive/
and ~/Library/Preferences/co.za.zarchive.plist.

NOTES
-----
- This build is Apple Silicon (M-series) only.
- Type one card name per line, then press Alt+Enter to search.
