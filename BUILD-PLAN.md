# mood — build plan

Goal: a professional, shareable Android app for tracking media (plus health, habits, spend, kitchen, projects).
Each phase is previewed at phone size before moving on.

## Phase 1 — quick fixes
- [x] Game covers: Wikipedia non-free covers (`pilicense=any`), drop broken Steam portrait links, never use the tiny Steam banner
- [x] Solid background behind the masthead, month titles and section labels (dots no longer run through text)
- [x] Bottom tab bar: no clipped tabs, Log button no longer overlaps
- [x] Icons: real gear for settings, clear status icons (planned / in progress / finished / dropped)
- [x] Remove "personal use only ◆ no. 001" stamp

## Phase 2 — Android shell
- [x] Back button closes popups and goes back a screen; exits only from the home tab
- [x] Export backup works in the APK (native save dialog)
- [x] Remove all PC sync (sync folder, linked step file, desktop step picker)
- [x] Bundle fonts (and three.js) so the app works offline
- [x] Build an APK on every branch; stop tracking the copied `android/app/src/main/assets/mood.html`
- [x] App icon + splash screen, haptic feedback

## Phase 3 — look & feel
- [x] Dark mode redesign: neutral charcoal, elevation by lighter surfaces, WCAG AA text, tuned accent, no grain
- [x] "Follow phone theme" setting
- [x] Replace all browser confirm/prompt popups with in-app dialogs
- [x] Icon set consistency pass — no emojis or typed symbols; drawn icons everywhere (buttons, toasts, badges)

## Phase 4 — media views
- [x] Library: grid/list toggle, clear status labels, one filter tray, duplicate warning on add (search tag + manual check)
- [x] Queue: sections as solid cards, one main button per item + ⋯ action sheet, labelled progress (ep 4/9, p. 210/480)
- [x] Queue: labelled actions (start / update / done) + "update progress" sheet (episodes, pages, %), pause / drop / move
- [x] Queue: swipe right = start / finish, swipe left = remove (with undo) / pause
- [x] Calendar: compact month grid with colored dots + tap-a-day agenda / month overview
- [x] "Piggy bank" activity log: each progress update saved with its date; calendar marks only active days
- [x] Entry details redesign (all media): hero, fact strip, action grid, progress, ratings, activity, notes
- [x] Calendar: upcoming TV episodes (air dates from TVmaze, refreshed daily, shows without a link are matched by title)
- [x] Rearrange / hide sections on every tab (header "arrange" button), reorder bottom-bar tabs (settings)

## Phase 4a — language (English / Turkish, switchable)
- [x] Translation system: every visible string goes through one lookup; language setting (English / Türkçe), defaults to the phone's language
- [x] Translate every tab, popup, toast and setting; dates, numbers and currency formatted per language
- [x] New features (Kitchen, Body, Home) are written bilingual from the start (rule for every phase from here on)
- [x] Savings strings get redone in phase 5 (the can + ilayda version is being replaced anyway)

## Phase 4b — Kitchen redesign
- [x] "Today in the kitchen" strip: use soon (expiring ≤3 days), you can cook (all ingredients home), to buy
- [x] Fridge: grouped shelves with icons, colour expiry chips, add via bottom sheet with smart category + shelf-life defaults, swipe "used up" → offer to add to market
- [x] Fridge rows: no per-row category dropdown (the shelf already says it; change via tap → edit sheet), aligned columns (name · qty · expiry), readable expiry ("2 days", "today", "expired"), add form out of the card into a "+ add" button
- [x] Recipes: photo, prep time, servings, "missing N" badge, servings scaler, cook mode (one big step at a time, screen stays on)
- [x] Recipes get macros per serving (Gemini); "I cooked this" → removes ingredients from fridge + logs the meal in Body
- [x] Market: grouped by aisle, ticking off → "put in fridge" with expiry guess, share list (WhatsApp etc.)

## Phase 4c — Body tab (Health becomes Body)
- [x] One daily record per date (meals, water, steps, workouts, weight) shared by every tab
- [x] Today rings on top: calories in / budget, protein / carbs / fat, water, steps; then meals, training, body stats, steps, sleep, pills — all rearrangeable
- [x] Profile → BMR (Mifflin-St Jeor) → TDEE; goal (lose / maintain / gain + pace) → daily calories + macros; half of burned calories added back to the budget
- [x] Meals in breakfast / lunch / dinner / snacks: type it (Gemini counts), photo of the plate (Gemini vision), favourites, Kitchen recipes — every item editable
- [x] Body stats: weight log with smoothed trend, BMI, body fat from tape measures (US Navy), goal weight + ETA; trend corrects the TDEE estimate over time
- [x] Training: Health Connect sessions + active calories, strength log (sets / reps / kg, personal bests), Gemini workout suggestions (goal, equipment, time)
- [x] Cross-links: fridge-aware meal ideas ("40 g protein left — chicken expires tomorrow → recipe"), water and steps shown in Today
- [x] Clear "estimates, not medical advice" note; all data stays on the phone

## Phase 4d — Home tab + game layer (built for an ADHD / autistic brain)
- [x] New **Home** tab (first tab by default): today at a glance — quests, level / XP, streaks, rings from Body, what's in progress from Queue, what to use from the fridge
- [x] **Daily quests:** 3 small, doable tasks a day picked from your goals ("2 more glasses", "10-minute walk", "read 10 pages"); clear "day cleared" state
- [x] **XP, levels & badges:** every logged thing gives XP; levels over time; milestone badges with drawn icons (no emojis); fixed, explained rules
- [x] **Unlockables:** accent colours and paper patterns earned by levelling up (card styles: later)
- [x] **Auto-habits:** habits tick themselves from data — water goal, protein / calorie target, steps, meals logged, workout, weight logged, all pills taken, slept 7h+, pages read / episode watched / game session, cooked at home, used food before it expired. Manual habits stay.
- [x] **No-shame streaks:** one missed day between done days is frozen automatically (once a week); "minimum day" mode — one tiny action keeps the chain on bad days
- [x] **Feedback:** calm default (subtle animation + haptic); settings for more (confetti, sound) or none (reduced motion)

## Phase 5 — money (decided after brainstorm)
Money tab becomes four sections: **Overview · Savings · Spending · Plan**.

**Savings — accounts**
- [x] Account kinds: shared (2+ members, names from data), personal, goal jar (target + date → "put ₺X a month")
- [x] Asset types: TRY cash, foreign currency, gold (gram), fund (units × price), time deposit (vadeli)
- [x] Time deposit: principal, annual rate, term, start date → net at maturity (withholding % editable, not hard-coded); maturity reminder; roll over / move to account
- [x] Prices: FX rates + gram gold fetched automatically (free no-key sources, ≈ market, shows "updated …"); fund prices entered by hand
- [x] Simple actions: Deposit (who, how much) / Update balance (difference = return) / Withdraw; return split by share in shared accounts
- [x] Plain-language timeline, labelled rates ("per month"), green = growth, red only for withdrawals and explained
- [x] Share code: export a shared account as text, the other person imports it (no server)
- [x] Share code as a QR (compressed code, qrcode-generator in vendor/) — the other phone scans it with its camera and pastes the text
- [x] Migrate existing can + ilayda entries into a shared account (no names in code)

**Spending**
- [x] Import a statement by screenshot / PDF straight into the app (Gemini key); remove the copy-paste prompt flow
- [x] Quick manual "+ expense" during the month; statement import merges matching rows
- [x] Insights (local rules, no AI needed): category vs 3-month average, subscription detector (recurring merchants, new / price-raised ones), big one-offs flagged and kept out of averages; neutral, no-shame tone
- [x] Game hooks: quest "add this month's statement", badge "a month under plan"

**Plan — hybrid**
- [x] Monthly income (several sources, irregular allowed)
- [x] Three buckets by percent: needs / wants / savings (default 50/30/20, adjustable); every spending category maps to a bucket
- [x] Optional limits only on the categories you choose inside a bucket
- [x] Savings share links to accounts ("20% → joint account")
- [x] Live bars during the month (manual + imported spending), plan vs actual at month end
- [x] What-if slider: change the savings % → goal dates move
- [x] Planner only does arithmetic on the user's own choices — no investment recommendations

**Overview**
- [x] Total saved (₺), this month vs plan, 1–2 insights, goal jar progress

## Phase 6 — title info ("data ripping")
- [x] Search several sources at once, merge duplicates, rank by title/year match, source badges
- [x] Wikipedia + Wikidata (synopsis in Turkish when trwiki has it, genres, platforms, director/author/studio, runtime, pages), richer TVmaze, Steam search in the apk
- [x] Google Books with a free key (guide + test in settings) — not tested without a key
- [x] Store genres, synopsis, runtime, platforms; "about" block on the entry
- [x] Validate covers (loads, portrait, largest) and save covers inside the app (IndexedDB, used when the link breaks / offline)
- [x] "Fix details & cover" per entry, "Repair library" in settings
- [x] Gemini fills only still-empty fields, marked "AI estimate"
- [x] Dollar funds (VOO): current price fetched by the apk (Yahoo Finance) — not testable in the browser
- [x] Turkish funds (TEFAS) price — dropped: entered by hand

## Phase 7 — Gemini
- [x] One shared Gemini helper (newest model first, older as fallback), JSON output, readable errors
- [x] Suggestions: "For you" row on Home (weekly), "More like this" on entries, "What should I start next?" in Queue, cross-media, not-interested memory
- [x] Import from text or screenshots → review screen (type / status / duplicates) → import, covers + details filled in after
- [x] Share-to-mood asks what it is: media list, recipe, or card statement (instagram links still go straight to recipes) — needs the apk to test
- [x] In-app API key guides (Gemini, TMDB, RAWG) with steps, a link, and a "test" button

## Phase 8 — extras
- [x] Import hub (+ → import, settings): Letterboxd export zip / list csv, Goodreads library csv, Trakt json export, Steam (games + wishlist, apk + web api key), any text / screenshot (gemini)
- [x] Choose what comes in: groups (watched, watchlist, each list / shelf, played / never played) and single items, each group lands in the library, the queue or as a mood list; duplicates marked (linked into lists, not copied)
- [x] E-reader: pick .epub files → title, author, year and the cover are read from each file (cover kept inside the app), status per book; page counts + details filled after
- [x] Stats page + year in review (finished per type, time spent, month by month, highest rated, genres, little facts)
- [x] In-app guide: "how mood works", tab by tab, with a welcome card on Home for new people
- [x] Search across everything (header button): library, lists, recipes, fridge, market, habits, projects, pills, meals, savings, spending
- [x] Review card image (cover, stars, your notes) → share / save; the apk shares through a FileProvider
- [x] First-launch setup: language, name, which tabs, two daily goals, then import / sample data / empty
- [x] Steam import tested with a real library (392 games, wishlist) through a mocked apk bridge; the real bridge runs in the apk
