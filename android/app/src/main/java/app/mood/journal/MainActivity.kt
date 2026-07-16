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
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.WindowCompat
import androidx.documentfile.provider.DocumentFile
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
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
 *   2. SAF sync folder (drive/syncthing → desktop)
 *   3. pill & water reminder notifications (AlarmManager, see Reminders.kt)
 *   4. android share target: text/screenshots shared into mood → recipe import
 *
 * js contract (see "android native bridge" in mood.html):
 *   MoodNative.autoSync() / requestSteps(days) / pickSyncFolder() / writeSyncFile(n, t)
 *   MoodNative.setTheme(dark, color) / writeWater(ml) / setReminders(json) / consumeShare()
 *   callbacks: onNativeSteps(json) / onNativeHealthExtra(json) / onNativeFolder(name)
 *              onNativeShare(json) / onNativeError(msg)
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var lastSilentSync = 0L
    private var pendingShare: JSONObject? = null

    private val healthPerms = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(HydrationRecord::class),
        HealthPermission.getWritePermission(HydrationRecord::class),
    )

    private val prefs by lazy { getSharedPreferences("mood", MODE_PRIVATE) }

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

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("syncTree", uri.toString()).apply()
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: "folder"
        js("window.onNativeFolder(${JSONObject.quote(name)})")
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
        setContentView(web)
        window.statusBarColor = android.graphics.Color.parseColor("#EDE4CF")
        window.navigationBarColor = android.graphics.Color.parseColor("#EDE4CF")
        WindowCompat.getInsetsController(window, web).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
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
                    if (hasAnyPerm()) syncHealth(days) else permLauncher.launch(healthPerms)
                }
            }
        }

        @JavascriptInterface fun pickSyncFolder() { runOnUiThread { folderLauncher.launch(null) } }

        @JavascriptInterface fun writeSyncFile(name: String, content: String) {
            val tree = prefs.getString("syncTree", null) ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dir = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(tree)) ?: return@launch
                    val file = dir.findFile(name) ?: dir.createFile("application/json", name) ?: return@launch
                    contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray()) }
                } catch (e: Exception) { jsError("sync write failed: ${e.message}") }
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

        return out.toString()
    }

    /* ---------------- helpers ---------------- */

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun jsError(msg: String) = js("window.onNativeError && window.onNativeError(${JSONObject.quote(msg)})")
}
