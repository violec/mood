package app.mood.journal

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
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
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId

/**
 * mood — a webview shell around mood.html that adds what the web can't do:
 *   1. health connect reads (steps + walking sessions) pushed into the page
 *   2. a SAF "sync folder" the page writes json into (drive/syncthing → desktop)
 *
 * js contract (see the "android native bridge" section in mood.html):
 *   window.MoodNative.autoSync() / requestSteps(days) / pickSyncFolder() / writeSyncFile(name, text)
 *   callbacks: window.onNativeSteps(json) / onNativeFolder(name) / onNativeError(msg)
 */
class MainActivity : ComponentActivity() {

    private lateinit var web: WebView
    private var lastSilentSync = 0L

    private val healthPerms = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    private val prefs by lazy { getSharedPreferences("mood", MODE_PRIVATE) }

    private val permLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        if (granted.containsAll(healthPerms)) syncHealth(90)
        else jsError("health permissions were not granted")
    }

    private val folderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
        prefs.edit().putString("syncTree", uri.toString()).apply()
        val name = DocumentFile.fromTreeUri(this, uri)?.name ?: "folder"
        js("window.onNativeFolder(${JSONObject.quote(name)})")
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this)
        setContentView(web)
        // paper-tone system bars from the first frame; js retunes them on theme switch
        window.statusBarColor = android.graphics.Color.parseColor("#EDE4CF")
        window.navigationBarColor = android.graphics.Color.parseColor("#EDE4CF")
        WindowCompat.getInsetsController(window, web).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true      // localStorage — the journal lives here
            allowFileAccess = true
            // the ui is a local file:// page; without this, fetch() to the cover-art
            // apis (tvmaze, open library, itunes, steam, wikipedia, gemini) is blocked
            // by webview cors rules — this is what broke search on the phone
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = true
        }
        web.webViewClient = object : WebViewClient() {
            // keep the app inside its shell; open external links in the browser
            override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                val url = req.url.toString()
                return if (url.startsWith("http")) {
                    startActivity(Intent(Intent.ACTION_VIEW, req.url)); true
                } else false
            }
        }
        web.addJavascriptInterface(Bridge(), "MoodNative")
        web.loadUrl("file:///android_asset/mood.html")
    }

    override fun onResume() {
        super.onResume()
        // silent refresh at most every 5 minutes; page also asks on first load
        if (System.currentTimeMillis() - lastSilentSync > 5 * 60_000) silentSync()
    }

    /* ---------------- bridge (called from js, arrives on a binder thread) ---------------- */

    inner class Bridge {
        @JavascriptInterface fun autoSync() { runOnUiThread { silentSync() } }

        @JavascriptInterface fun requestSteps(days: Int) {
            runOnUiThread {
                if (!ensureAvailable()) return@runOnUiThread
                lifecycleScope.launch {
                    if (hasPerms()) syncHealth(days) else permLauncher.launch(healthPerms)
                }
            }
        }

        @JavascriptInterface fun pickSyncFolder() { runOnUiThread { folderLauncher.launch(null) } }

        /** paper/ink theme changed in the page → tint the system bars to match */
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

        @JavascriptInterface fun writeSyncFile(name: String, content: String) {
            val tree = prefs.getString("syncTree", null) ?: return
            lifecycleScope.launch(Dispatchers.IO) {
                try {
                    val dir = DocumentFile.fromTreeUri(this@MainActivity, Uri.parse(tree)) ?: return@launch
                    val file = dir.findFile(name) ?: dir.createFile("application/json", name) ?: return@launch
                    contentResolver.openOutputStream(file.uri, "wt")?.use { it.write(content.toByteArray()) }
                } catch (e: Exception) {
                    jsError("sync write failed: ${e.message}")
                }
            }
        }
    }

    /* ---------------- health connect ---------------- */

    private fun client(): HealthConnectClient? =
        if (HealthConnectClient.getSdkStatus(this) == HealthConnectClient.SDK_AVAILABLE)
            HealthConnectClient.getOrCreate(this) else null

    private fun ensureAvailable(): Boolean {
        if (client() != null) return true
        jsError("health connect is not available — install/update it from the play store, then let samsung health sync into it")
        return false
    }

    private suspend fun hasPerms(): Boolean =
        client()?.permissionController?.getGrantedPermissions()?.containsAll(healthPerms) == true

    private fun silentSync() {
        val c = client() ?: return
        lifecycleScope.launch {
            if (c.permissionController.getGrantedPermissions().containsAll(healthPerms)) {
                lastSilentSync = System.currentTimeMillis()
                syncHealth(35)
            }
        }
    }

    private fun syncHealth(days: Int) {
        lifecycleScope.launch {
            try {
                val json = withContext(Dispatchers.Default) { readHealthJson(days.coerceIn(1, 365)) }
                js("window.onNativeSteps(${JSONObject.quote(json)})")
            } catch (e: Exception) {
                jsError("health connect read failed: ${e.message}")
            }
        }
    }

    /** builds [{date:"yyyy-mm-dd", steps:n, walkMin:n, walkKm:n}, …] for the page parser */
    private suspend fun readHealthJson(days: Int): String {
        val c = client() ?: return "[]"
        val zone = ZoneId.systemDefault()
        val endDay = LocalDate.now()
        val startDay = endDay.minusDays(days.toLong())
        val byDate = LinkedHashMap<String, JSONObject>()
        fun row(d: String): JSONObject = byDate.getOrPut(d) {
            JSONObject().put("date", d).put("steps", 0).put("walkMin", 0).put("walkKm", 0)
        }

        // daily step totals
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

        // walking sessions → minutes + km per day
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
            val min = ((s.endTime.toEpochMilli() - s.startTime.toEpochMilli()) / 60_000).toInt()
            r.put("walkMin", r.getInt("walkMin") + min)
            try {
                val dist = c.aggregate(
                    AggregateRequest(
                        metrics = setOf(DistanceRecord.DISTANCE_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(s.startTime, s.endTime)
                    )
                )[DistanceRecord.DISTANCE_TOTAL]
                if (dist != null) {
                    val km = Math.round((r.getDouble("walkKm") + dist.inKilometers) * 10.0) / 10.0
                    r.put("walkKm", km)
                }
            } catch (_: Exception) { /* distance is a nice-to-have */ }
        }

        return JSONArray(byDate.values.toList()).toString()
    }

    /* ---------------- helpers ---------------- */

    private fun js(code: String) = runOnUiThread { web.evaluateJavascript(code, null) }
    private fun jsError(msg: String) = js("window.onNativeError && window.onNativeError(${JSONObject.quote(msg)})")
}
