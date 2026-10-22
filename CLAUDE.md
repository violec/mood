# mood — notes for Claude

A personal journal app: media tracking (films, TV, books, games, albums) plus body, habits, money,
kitchen and projects. One web file wrapped as an Android app, also installable on PC from GitHub Pages.
Owner: Can (GitHub `violec`). Talk to them in the language they write in (Turkish or English).
They read results on their phone: put everything they need in the **final message** of a turn,
since text written between tool calls may not reach them.

## Layout
- `mood.html` — **the whole app** (~12.6k lines: CSS, then the I18N table, then JS modules). The single source of truth.
- `android/` — Kotlin WebView shell (`app/src/main/java/app/mood/journal/MainActivity.kt`, `Reminders.kt`).
  The gradle build copies `mood.html`, `fonts/`, `vendor/` into assets (those copies are gitignored, never edit them).
- `index.html`, `manifest.webmanifest`, `sw.js`, `icons/`, `.nojekyll` — the PC web app on GitHub Pages
  (https://violec.github.io/mood/). Pushing `main` updates it. The service worker is network-first and skips localhost.
- `fonts/`, `vendor/` (three.js, qrcode) — bundled so the app works offline.
- `BUILD-PLAN.md` — phase checklist (all phases done). `README-ANDROID.md` — older build/sync guide for the owner.
- `tools/tr_missing.py` — finds untranslated strings (see below).

## How the code is organised
- `db` is the journal object; `save()` writes it, `render()` redraws the current tab (`ui.view`).
- Storage, three copies, newest `savedAt` wins at start (`storeReady`), `hydrate()` normalises the shape:
  IndexedDB `mood-data` / store `kv` (keys `journal`, `syncBase`, `friendCache`), localStorage `mood.journal.v1`,
  and in the APK `MoodNative.saveJournal/loadJournal` + daily snapshots (newest 10).
- Native bridge (`MoodNative.*`, only in the APK): `httpGet` (fetch without CORS, answers via `onNativeHttp`),
  `saveFile`, `shareText`, `shareImage`, `saveJournal`, `saveJournalCopy`, `loadJournal`, `listJournalBackups`,
  `loadJournalBackup`, `haptic`, `keepAwake`, `setTheme`, `requestSteps`, `writeWater`, `setReminders`, `consumeShare`.
  Use `nativeGet(url)` from JS; it resolves `null` outside the APK.
- New modules go right before `/* ============ settings ============ */\nfunction openSettings(){`.
  New CSS goes right before `/* ================= kitchen ================= */`.
- UI helpers: `openModal/closeModal`, `openSheet(title, [{label, icon, fn, danger}])`, `askConfirm`, `askText`,
  `toast(msg, {undo})` (a leading ⚠ / trailing ✓ sets its style), `ICONS.*` (drawn SVG, no emojis), `esc()`.
  Home/tab sections carry `data-sec` + `data-sec-label` so users can reorder/hide them (`settings.layout`).
- Media types: `TYPES` (movie, tv, book, game, album). Search = `SOURCES[type]` merged by `searchMedia()`;
  details after saving = `enrich()`. Album covers are square everywhere via a CSS `:has()` rule keyed on the album icon.
- Covers: `pickCover`, `openCoverPicker` (+ `moreCovers` per type), `cacheCover`.
- AI: `geminiCall` / `geminiJSON` with free fallbacks (Groq, OpenRouter `:free`), `aiOn()` gates every AI feature.
- Sync: encrypted secret gist `mood-journal.enc` (PBKDF2 → AES-GCM), `merge3`, config per device in localStorage
  `mood.sync.v1`; pushes at most once a minute, `ghFail` handles GitHub rate limits.
- Friends: unencrypted secret gist `mood-profile.json` (titles, ratings, status; the whole library), code `MOOD-FRIEND:<gistId>`.
- "What next?" (v2.9): home card + swipe deck, `wnPool` → `wnScore` → `wnPick`, state in `db.ai.wn`.

## Translations (every visible string)
- Always `tl('english text', {vars})`. Turkish goes in `I18N.tr`: add new lines just before
  `    'more tabs': 'diğer sekmeler',` (later keys win if duplicated).
- Check a region: `python tools/tr_missing.py "<start text>" "<end text>"`. It misses strings chosen
  inside a ternary in `tl(...)`, so check those by eye.

## Editing tips
- `mood.html` is big: grep for an anchor, read ~100 lines around it, edit. For multi-part changes a small python
  script with exact `replace` + `assert count == 1` works well; back up the file first.
- Python heredocs mangle backslashes in JS regexes; use the Edit tool for lines with `\`.
- Syntax check after edits:
  `node -e "const h=require('fs').readFileSync('mood.html','utf8');const m=h.match(/<script>([\s\S]*?)<\/script>\s*<\/body>/);new Function(m[1]);console.log('ok')"`

## Testing
- Preview: `.claude/launch.json` (gitignored, recreate on a new machine):
  `{"version":"0.0.1","configurations":[{"name":"mood","runtimeExecutable":"python","runtimeArgs":["-m","http.server","5173","--bind","127.0.0.1"],"port":5173}]}`
  then open `http://localhost:5173/mood.html` at 390×844 (phone size).
- Before testing, back up the dev data (`localStorage.setItem('_DEVBAK', JSON.stringify(db))`) and restore it after.
- Never point tests at the owner's real GitHub gist or real accounts; mock GitHub calls.
- External APIs (MusicBrainz 1 req/s, Deezer via jsonp/native) can rate-limit during heavy testing; the app tolerates it.

## Building the APK
- Bump `versionCode` / `versionName` in `android/app/build.gradle.kts` (currently 13 / "2.9").
- Local toolchain in `%USERPROFILE%\mood-build` (jdk 17, Android SDK 34, gradle 8.7); `android/local.properties`
  holds `sdk.dir`. `build-local.bat` builds and copies `mood.apk` to the repo root (gitignored).
  On a new PC: copy that folder over, or install the same versions again.
- **Signing:** `android/app/mood-signing.keystore` (alias `androiddebugkey`, password `android`) is the key the phone's
  installed app was signed with. It is gitignored (this repo is public and must stay public: GitHub Pages
  on a free account needs that). The key lives in the **private repo `violec/mood-private`**. On a new machine:
  `git clone https://github.com/violec/mood-private` next to this repo, then copy
  `mood-private/mood-signing.keystore` → `android/app/mood-signing.keystore`. Never commit it here.
  Without it, gradle falls back to the machine's own debug key and the APK won't install over the old app
  ("App not installed"). GitHub Actions builds use their own key for the same reason, so they're not for the phone.
  Verify: `apksigner verify --print-certs mood.apk` → SHA-256 `4f6267e4…f475af49`.
- After building: commit + push (this also updates the PC app), then send `mood.apk` to the owner.

## Rules from the owner
- Free services only, nothing that costs money (Gemini free tier, Groq/OpenRouter free models, TMDB, iTunes, Deezer…).
- Data must never be lost on update; keep the three-copy storage and snapshots working.
- Don't commit secrets (API keys they share for testing, the keystore).

## Where things stand (Oct 2026)
v2.9 shipped. Recent: library bulk select + undo, "save + another", albums (iTunes, Deezer, MusicBrainz, Wikipedia,
AOTY link), friend's whole library, square album covers, cover picker for every type, "what next?" swipe deck.
Not yet tried with real accounts: GitHub sync, friends, Groq/OpenRouter fallback, Steam bridge on the phone,
share-to-mood, Deezer through the APK's native fetch, touch swiping on a real phone.

## Plans discussed (not started)
1. **Jellyfin theme** (next, on the main PC): the owner has a Jellyfin theme and wants it closer to reference sites
   they'll send (links for public pages, screenshots for logged-in ones, with a note on what they like in each).
   First read their current Custom CSS (Dashboard → General → Custom CSS, stored in `config/branding.xml`),
   Jellyfin version (10.10 vs 10.11 class names differ) and home-screen plugins. Preview CSS live on their server
   in the browser pane (they sign in themselves), back up the old CSS, apply only after they confirm.
   CSS covers the web client and the official phone apps, not Android TV or native third-party clients.
2. **mood+**: a separate dark, Netflix-like streaming space inside the same app (header switch, its own nav,
   optionally a second launcher icon). Hero with backdrop + logo art, rows (continue watching, next up, queue,
   because you loved X, friends watching, requested), detail page, full-screen player. Could start before the
   server exists using TMDB "where to watch" (country TR) and open the streaming app.
3. **Self-hosted server** (future, own machine; now on their desktop; Radarr, Sonarr, Prowlarr): Jellyfin as the engine,
   mood as the client. Phases: connect + match library + "on server" badges + web player (hls.js) → requests
   (Radarr/Sonarr directly, or Jellyseerr once friends request) → native Android player (Media3/ExoPlayer) →
   albums via Lidarr (mood already stores MusicBrainz ids). Remote access is the hard part (Turkish ISPs often use
   CGNAT; Tailscale didn't work for them before; alternatives: Cloudflare Tunnel for the API, a VPS relay).
4. **AOTY importer**: albumoftheyear.org has no API and blocks apps; import from a saved ratings page / pasted text.
