package app.mood.journal

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Volume
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * mood — webview shell + the native powers the web can't have:
 *   1. health connect: steps, walking sessions, sleep, hydration (read) + hydration write
 *   2. system "save as" dialog for backups (webview can't download blobs)
 *   3. pill & water reminder notifications (AlarmManager, see Reminders.kt)
 *   4. android share target: text/screenshots shared into mood → recipe import
 *
 * js contract (see "android native bridge" in mood.html):
 *   MoodNative.autoSync() / requestSteps(days) / saveFile(name, text) / haptic(kind) / shareText(text) / keepAwake(on)
 *   MoodNative.setTheme(dark, color) / writeWater(ml) / setReminders(json) / consumeShare()
 *   callbacks: onNativeSteps(json) / onNativeHealthExtra(json) / onNativeShare(json) / onNativeError(msg)
 *   back button → window.moodBack() (true = handled in the page, false = leave the app)
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var lastSilentSync = 0L
    private var pendingShare: JSONObject? = null

    private val healthPerms = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
    )


    private val permLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPerms)) syncHealth(90)
        else if (granted.any { it.contains("STEPS") }) syncHealth(90)  // partial grants still useful
        else jsError("health permissions were not granted")
    }

    private val notifLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not — alarms are armed either way; notifications just won't show if denied */ }

    // backup export: the page hands over the json, the user picks where it goes
    private var pendingSave: String? = null
    private val saveLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri: Uri? ->
        val content = pendingSave
        pendingSave = null
        if (uri == null || content == null) return@registerForActivityResult
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray()) }
                js("window.toast && toast('backup saved ✓')")
            } catch (e: Exception) { jsError("backup save failed: ${e.message}") }
        }
    }

    // webview file inputs need a native chooser — without this they are silently dead
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooser = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris = mutableListOf<Uri>()
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            data.clipData?.let { cd -> for (i in 0 until cd.itemCount) uris.add(cd.getItemAt(i).uri) }
            if (uris.isEmpty()) data.data?.let { uris.add(it) }
        }
        fileCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        // paper by day, charcoal at night (res/values-night) — no white frame while the page loads;
        // the page then retints the bars to its own theme via setTheme()
        val night = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        val bg = getColor(R.color.window_bg)
        web.setBackgroundColor(bg)
        setContentView(web)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        WindowCompat.getInsetsController(window, web).apply {
            isAppearanceLightStatusBars = !night
            isAppearanceLightNavigationBars = !night
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true   // local page → cover-art/gemini fetches
        }
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                return if (url.startsWith("http")) { startActivity(Intent(Intent.ACTION_VIEW, req.url)); true } else false
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try { fileChooser.launch(params.createIntent()); true }
                catch (e: Exception) { fileCallback = null; jsError("no file picker available: ${e.message}"); false }
            }
        }
        web.addJavascriptInterface(Bridge(), "MoodNative")
        // back: let the page close popups / step back through tabs; leave the app only when it says so
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.moodBack ? window.moodBack() : false)") { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        web.loadUrl("file:///android_asset/mood.html")
        Reminders.ensureChannel(this)
        captureShare(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        captureShare(intent)
        deliverShare()
    }

    override fun onResume() {
        super.onResume()
        if (System.currentTimeMillis() - lastSilentSync > 5 * 60_000) silentSync()
    }

    /* ---------------- share target (instagram screenshots, copied captions…) ---------------- */

    private fun captureShare(intent: Intent?) {
        if (intent == null) return
        val j = JSONObject()
        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.let { j.put("text", it) }
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { j.put("images", JSONArray(listOf(uriToB64(it)).filterNotNull())) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val list = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: return
                j.put("images", JSONArray(list.mapNotNull { uriToB64(it) }))
            }
            else -> return
        }
        if (j.length() > 0) pendingShare = j
    }

    private fun uriToB64(uri: Uri): String? = try {
        contentResolver.openInputStream(uri)?.use { ins ->
            val bytes = ins.readBytes()
            if (bytes.size > 6_000_000) null
            else Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    } catch (_: Exception) { null }

    private fun deliverShare() {
        val j = pendingShare ?: return
        pendingShare = null
        js("window.onNativeShare && window.onNativeShare(${JSONObject.quote(j.toString())})")
    }

    /* ---------------- bridge ---------------- */

    inner class Bridge {
        @JavascriptInterface fun autoSync() { runOnUiThread { silentSync() } }

        @JavascriptInterface fun requestSteps(days: Int) {
            runOnUiThread {
                if (!ensureAvailable()) return@runOnUiThread
                lifecycleScope.launch {
                    // ask again whenever ANY permission is missing — otherwise users who
                    // granted the old steps-only set never get asked for sleep/hydration
                    val granted = client()?.permissionController?.getGrantedPermissions() ?: emptySet()
                    if (granted.containsAll(healthPerms)) syncHealth(days)
                    else permLauncher.launch(healthPerms)
                }
            }
        }

        @JavascriptInterface fun saveFile(name: String, content: String) {
            runOnUiThread { pendingSave = content; saveLauncher.launch(name) }
        }

        /** android share sheet (whatsapp, notes…) — used for the market list */
        @JavascriptInterface fun shareText(text: String) {
            runOnUiThread {
                val send = Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text) }
                startActivity(Intent.createChooser(send, null))
            }
        }

        /** cook mode: keep the screen on while a recipe is open */
        @JavascriptInterface fun keepAwake(on: Boolean) {
            runOnUiThread {
                if (on) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        /** light vibration on key actions — 'tap' for presses, 'confirm' for done/save, 'reject' for errors */
        @JavascriptInterface fun haptic(kind: String) {
            runOnUiThread {
                val c = when (kind) {
                    "confirm" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
                    "reject" -> if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
                    else -> HapticFeedbackConstants.KEYBOARD_TAP
                }
                web.performHapticFeedback(c)
            }
        }

        @JavascriptInterface fun setTheme(dark: Boolean, color: String) {
            runOnUiThread {
                try {
                    val c = android.graphics.Color.parseColor(color.trim())
                    window.statusBarColor = c
                    window.navigationBarColor = c
                    WindowCompat.getInsetsController(window, web).apply {
                        isAppearanceLightStatusBars = !dark
                        isAppearanceLightNavigationBars = !dark
                    }
                } catch (_: Exception) {}
            }
        }

        /** the "+250 ml" button also writes into health connect so samsung health sees it */
        @JavascriptInterface fun writeWater(ml: Int) {
            val c = client() ?: return
            lifecycleScope.launch {
                try {
                    val now = Instant.now()
                    c.insertRecords(listOf(HydrationRecord(
                        startTime = now.minusSeconds(60), startZoneOffset = null,
                        endTime = now, endZoneOffset = null,
                        volume = Volume.milliliters(ml.toDouble()),
                    )))
                } catch (_: Exception) { /* write permission missing — local log still counts */ }
            }
        }

        /** schedule json from the page → alarms; asks notification permission on android 13+ */
        @JavascriptInterface fun setReminders(json: String) {
            runOnUiThread {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    notifLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                Reminders.saveScheduleJson(this@MainActivity, json)
                try { Reminders.arm(this@MainActivity, JSONObject(json)) }
                catch (e: Exception) { jsError("reminder scheduling failed: ${e.message}") }
            }
        }

        @JavascriptInterface fun consumeShare() { runOnUiThread { deliverShare() } }

        // the journal's safety copy, in the app's own folder (survives anything the webview's storage does).
        // each day also keeps its last state in snapshots/ — the newest 10 are kept.
        @JavascriptInterface fun saveJournal(json: String) {
            try {
                val tmp = java.io.File(filesDir, "journal.tmp")
                tmp.writeText(json)
                tmp.renameTo(java.io.File(filesDir, "journal.json"))
                val snaps = java.io.File(filesDir, "snapshots").apply { mkdirs() }
                java.io.File(snaps, "${LocalDate.now()}.json").writeText(json)
                pruneSnapshots(snaps)
            } catch (_: Exception) {}
        }
        @JavascriptInterface fun saveJournalCopy(json: String, name: String) {
            try {
                val snaps = java.io.File(filesDir, "snapshots").apply { mkdirs() }
                java.io.File(snaps, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json").writeText(json)
                pruneSnapshots(snaps)
            } catch (_: Exception) {}
        }
        @JavascriptInterface fun loadJournal(): String =
            try { java.io.File(filesDir, "journal.json").takeIf { it.exists() }?.readText() ?: "" } catch (_: Exception) { "" }
        @JavascriptInterface fun listJournalBackups(): String {
            val arr = JSONArray()
            try {
                java.io.File(filesDir, "snapshots").listFiles()?.filter { it.name.endsWith(".json") }?.sortedByDescending { it.name }?.forEach {
                    arr.put(JSONObject().put("name", it.name.removeSuffix(".json")).put("size", it.length()))
                }
            } catch (_: Exception) {}
            return arr.toString()
        }
        @JavascriptInterface fun loadJournalBackup(name: String): String =
            try { java.io.File(java.io.File(filesDir, "snapshots"), name.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json").readText() } catch (_: Exception) { "" }

        // a png made in the page (review card) → the system share sheet
        @JavascriptInterface fun shareImage(b64: String, name: String) {
            runOnUiThread {
                try {
                    val dir = java.io.File(cacheDir, "shared").apply { mkdirs() }
                    val f = java.io.File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_"))
                    f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
                    val uri = androidx.core.content.FileProvider.getUriForFile(this@MainActivity, "$packageName.files", f)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(send, null))
                } catch (e: Exception) { jsError("could not share the image: ${e.message}") }
            }
        }

        // plain https GET for sources a web page may not read directly (steam store search, fund prices).
        // answers through window.onNativeHttp(id, text | null)
        @JavascriptInterface fun httpGet(id: String, url: String) {
            if (!url.startsWith("https://")) { js("window.onNativeHttp && window.onNativeHttp(${JSONObject.quote(id)}, null)"); return }
            lifecycleScope.launch {
                val text = withContext(Dispatchers.IO) {
                    try {
                        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                        c.connectTimeout = 9000; c.readTimeout = 9000
                        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) mood")
                        c.setRequestProperty("Accept", "application/json,text/plain,*/*")
                        if (c.responseCode in 200..299) c.inputStream.bufferedReader().use { it.readText() } else null
                    } catch (_: Exception) { null }
                }
                js("window.onNativeHttp && window.onNativeHttp(${JSONObject.quote(id)}, ${if (text == null) "null" else JSONObject.quote(text)})")
            }
        }
    }

    /* ---------------- health connect ---------------- */

    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(this) else null

    private fun ensureAvailable(): Boolean {
        if (client() != null) return true
        jsError("health connect is not available — install/update it, then let samsung health sync into it")
        return false
    }

    private suspend fun hasAnyPerm(): Boolean =
        (client()?.permissionController?.getGrantedPermissions() ?: emptySet()).isNotEmpty()

    private fun silentSync() {
        val c = client() ?: return
        lifecycleScope.launch {
            if (c.permissionController.getGrantedPermissions().isNotEmpty()) {
                lastSilentSync = System.currentTimeMillis()
                syncHealth(35)
            }
        }
    }

    private fun syncHealth(days: Int) {
        lifecycleScope.launch {
            try {
                val steps = withContext(Dispatchers.Default) { readStepsJson(days.coerceIn(1, 365)) }
                js("window.onNativeSteps(${JSONObject.quote(steps)})")
            } catch (e: Exception) { jsError("health connect read failed: ${e.message}") }
            try {
                val extra = withContext(Dispatchers.Default) { readExtraJson(14) }
                js("window.onNativeHealthExtra(${JSONObject.quote(extra)})")
            } catch (_: Exception) { /* sleep/hydration are optional */ }
        }
    }

    private suspend fun readStepsJson(days: Int): String {
        val c = client() ?: return "[]"
        val zone = ZoneId.systemDefault()
        val endDay = LocalDate.now()
        val startDay = endDay.minusDays(days.toLong())
        val byDate = LinkedHashMap<String, JSONObject>()
        fun row(d: String): JSONObject = byDate.getOrPut(d) {
            JSONObject().put("date", d).put("steps", 0).put("walkMin", 0).put("walkKm", 0)
        }
        val buckets = c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(
                    LocalDateTime.of(startDay, java.time.LocalTime.MIDNIGHT),
                    LocalDateTime.of(endDay.plusDays(1), java.time.LocalTime.MIDNIGHT)
                ),
                timeRangeSlicer = Period.ofDays(1)
            )
        )
        for (b in buckets) {
            val steps = b.result[StepsRecord.COUNT_TOTAL] ?: continue
            if (steps > 0) row(b.startTime.toLocalDate().toString()).put("steps", steps)
        }
        val sessions = c.readRecords(
            ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    startDay.atStartOfDay(zone).toInstant(),
                    endDay.plusDays(1).atStartOfDay(zone).toInstant()
                )
            )
        ).records.filter { it.exerciseType == ExerciseSessionRecord.EXERCISE_TYPE_WALKING }
        for (s in sessions) {
            val d = s.startTime.atZone(zone).toLocalDate().toString()
            val r = row(d)
            r.put("walkMin", r.getInt("walkMin") + ((s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 60_000).toInt())
            try {
                val dist = c.aggregate(AggregateRequest(
                    metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime)
                ))[DistanceRecord.DISTANCE_TOTAL]
                if (dist != null) r.put("walkKm", Math.round((r.getDouble("walkKm") + dist.inKilometers) * 10.0) / 10.0)
            } catch (_: Exception) {}
        }
        return JSONArray(byDate.values.toList()).toString()
    }

    /** sleep sessions (minutes per morning-date) + hydration (ml per day) */
    private suspend fun readExtraJson(days: Int): String {
        val c = client() ?: return "{}"
        val zone = ZoneId.systemDefault()
        val endDay = LocalDate.now()
        val startDay = endDay.minusDays(days.toLong())
        val out = JSONObject()

        try {
            val sleepByDate = HashMap<String, Long>()
            c.readRecords(ReadRecordsRequest(
                recordType = SleepSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    startDay.atStartOfDay(zone).toInstant(),
                    endDay.plusDays(1).atStartOfDay(zone).toInstant()
                )
            )).records.forEach { s ->
                val d = s.endTime.atZone(zone).toLocalDate().toString()   // the night belongs to the morning it ends
                val min = (s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 60_000
                if (min in 10..1000) sleepByDate[d] = (sleepByDate[d] ?: 0) + min
            }
            out.put("sleep", JSONArray(sleepByDate.map { (d, m) -> JSONObject().put("date", d).put("min", m) }))
        } catch (_: Exception) {}

        try {
            val buckets = c.aggregateGroupByPeriod(
                AggregateGroupByPeriodRequest(
                    metrics = setOf(HydrationRecord.VOLUME_TOTAL),
                    timeRangeFilter = TimeRangeFilter.between(
                        LocalDateTime.of(startDay, java.time.LocalTime.MIDNIGHT),
                        LocalDateTime.of(endDay.plusDays(1), java.time.LocalTime.MIDNIGHT)
                    ),
                    timeRangeSlicer = Period.ofDays(1)
                )
            )
            val arr = JSONArray()
            for (b in buckets) {
                val vol = b.result[HydrationRecord.VOLUME_TOTAL] ?: continue
                if (vol.inMilliliters > 0)
                    arr.put(JSONObject().put("date", b.startTime.toLocalDate().toString()).put("ml", Math.round(vol.inMilliliters)))
            }
            out.put("water", arr)
        } catch (_: Exception) {}

        // every workout session (walks, runs, gym…) with its active calories → the Body tab's training list
        try {
            val arr = JSONArray()
            c.readRecords(ReadRecordsRequest(
                recordType = ExerciseSessionRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    startDay.atStartOfDay(zone).toInstant(),
                    endDay.plusDays(1).atStartOfDay(zone).toInstant()
                )
            )).records.forEach { s ->
                val min = ((s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 60_000).toInt()
                if (min < 3) return@forEach
                val kcal = try {
                    c.aggregate(AggregateRequest(
                        metrics = setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime)
                    ))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories ?: 0.0
                } catch (_: Exception) { 0.0 }
                arr.put(JSONObject()
                    .put("id", s.metadata.id)
                    .put("date", s.startTime.atZone(zone).toLocalDate().toString())
                    .put("type", workoutType(s.exerciseType))
                    .put("title", s.title ?: "")
                    .put("min", min)
                    .put("kcal", Math.round(kcal)))
            }
            out.put("workouts", arr)
        } catch (_: Exception) {}

        return out.toString()
    }

    private fun workoutType(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING, ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL -> "run"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING, ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "cycle"
        ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL, ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_OPEN_WATER -> "swim"
        ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING -> "strength"
        ExerciseSessionRecord.EXERCISE_TYPE_HIGH_INTENSITY_INTERVAL_TRAINING -> "hiit"
        ExerciseSessionRecord.EXERCISE_TYPE_YOGA, ExerciseSessionRecord.EXERCISE_TYPE_PILATES -> "yoga"
        ExerciseSessionRecord.EXERCISE_TYPE_DANCING -> "dance"
        else -> "other"
    }

    /* ---------------- helpers ---------------- */

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun pruneSnapshots(dir: java.io.File) {
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.sortedByDescending { it.name }?.drop(10)?.forEach { it.delete() }
    }

    private fun jsError(msg: String) = js("window.onNativeError && window.onNativeError(${JSONObject.quote(msg)})")
}
