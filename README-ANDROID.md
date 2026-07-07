# mood — android build & sync guide

The `android/` folder is a complete Android Studio project. It wraps `mood.html` in a WebView and adds two native powers: **Health Connect** (auto step/walk sync from Samsung Health) and a **sync folder** (phone → desktop). The build always copies the latest `mood.html` into the app, so the web file stays the single source of truth.

## Get the APK (pick one)

**A. GitHub Actions — no Android Studio needed**
1. Create a free GitHub account + a new private repository.
2. Upload this whole folder (`mood.html`, `android/`, `.github/`) to the repo.
3. Repo → Actions tab → "build apk" → Run workflow.
4. When it finishes (~3 min), download the `mood-debug-apk` artifact, unzip → `app-debug.apk`.

**B. Android Studio**
1. Install Android Studio, open the `android` folder, let Gradle sync (needs JDK 17 — bundled).
2. Build → Build App Bundle(s)/APK(s) → Build APK(s).
3. APK lands in `android/app/build/outputs/apk/debug/`.

**Install on the phone:** copy the APK over (or download the artifact on the phone), tap it, allow "install unknown apps" when prompted.

## Phone setup (once)

1. Samsung Health → Settings → **Health Connect** → turn on sharing (steps, exercise).
2. Open **mood** → Steps tab → "⌁ sync health connect" → grant the permissions.
   After this it syncs silently every time the app opens (and at most every 5 min on resume).
3. For desktop sync: Settings (gear) → **choose sync folder** → pick a folder that reaches your PC:
   - easiest: a folder inside Google Drive (with "Drive for desktop" on the PC), or
   - Syncthing, OneDrive, Dropbox — anything that mirrors a folder.
   mood writes `mood-steps.json` + `mood-backup.json` there after every change.

## Desktop side

- Open `mood.html` in Chrome/Edge → Steps tab → **⛓ link sync file** → pick `mood-steps.json` in the synced folder. Steps then refresh silently every time you open it.
- To pull the full journal (media entries, notes, episodes): Settings → import json → `mood-backup.json`.

## Notes

- Debug APK is fine for personal use. It's unsigned-for-store, sideload only.
- If Gradle complains about the Health Connect library version, bump
  `androidx.health.connect:connect-client` in `android/app/build.gradle.kts` to the newest `1.1.0-*`.
- Health Connect requires Android 9+; on Android 13 or lower also install the
  "Health Connect" app from the Play Store (Android 14+ has it built in).
