ZArchive — South African MTG store singles search
==================================================

HOW TO RUN
----------
1. Drag ZArchive.app anywhere you like (Applications, Desktop, etc.).
2. Double-click ZArchive.app.

That's it. Java is bundled inside the .app — you do NOT need to install
anything else.

FIRST LAUNCH — GATEKEEPER WARNING
-----------------------------------
Because ZArchive is not notarised with Apple, macOS will block it the
first time you try to open it. Do this once to allow it:

  1. Right-click (or Control-click) ZArchive.app
  2. Choose "Open" from the menu
  3. Click "Open" in the dialog that appears

After that you can double-click normally.

If macOS says the app is "damaged", run this in Terminal:
  xattr -dr com.apple.quarantine /path/to/ZArchive.app

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
