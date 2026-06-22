---
description: Launch the ZArchive Compose Desktop app
trigger: explicit
---

# Run ZArchive

ZArchive is a Kotlin Compose Desktop app. Launch it by opening a new PowerShell window with gradlew running inside it:

```powershell
Start-Process powershell -ArgumentList '-NoExit', '-Command', 'Set-Location "C:\Users\nicob\Documents\Software\Kotlin\mtgstorequery"; .\gradlew.bat run'
```

Run this via the PowerShell tool. It opens a detached terminal window where Gradle compiles and starts the app. No `run_in_background` needed — the child process is fully detached.

## Notes

- First build after a code change takes 30–60 s on a warm Gradle cache.
- The app writes debug dumps to `~/zarchive-debug/` (only when `mtg.debug=true`, which `gradlew run` injects).
- The Compose window appears once Gradle finishes compiling and linking.
