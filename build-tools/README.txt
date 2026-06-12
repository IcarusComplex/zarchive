ZArchive — South African MTG store singles search
==================================================

HOW TO RUN
----------
1. Unzip this whole folder anywhere you like (e.g. your Desktop or Documents).
   Keep all the files together — ZArchive.exe needs the "app" and "runtime"
   folders next to it.
2. Double-click ZArchive.exe.

That's it. Java is bundled inside this folder — you do NOT need to install
anything else.

WHERE ZARCHIVE STORES DATA ON YOUR COMPUTER
-------------------------------------------
ZArchive does NOT write anything into the program folder while running. Your
data lives under your Windows user profile:

- Card image cache:
      C:\Users\<you>\.zarchive\images
  Downloaded card art (one .jpg per card). Safe to delete anytime — it just
  re-downloads on the next search. This is the only large folder it creates.

- Settings (the "Ignore basic lands" / "Auto-open Luckshack" checkboxes):
      Stored in the Windows registry under
      HKEY_CURRENT_USER\Software\JavaSoft\Prefs\zarchive
  Tiny; remembers your toggle choices between runs.

To fully remove ZArchive: delete the unzipped program folder, then delete the
C:\Users\<you>\.zarchive folder.

NOTES
-----
- Windows may show a "Windows protected your PC" SmartScreen prompt the first
  time (the app is not code-signed). Click "More info" -> "Run anyway".
- Type one card name per line, then press Enter to search.
