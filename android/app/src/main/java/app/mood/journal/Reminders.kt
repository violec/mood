package app.mood.journal

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.util.Calendar

/**
 * pill + water reminders. mood.html pushes a schedule json via MoodNative.setReminders;
 * we arm the NEXT occurrence of every reminder with AlarmManager. when one fires,
 * the receiver shows a notification and re-arms the next occurrence. the schedule
 * json is persisted so a reboot (BootReceiver) or app-open re-arms everything.
 */
object Reminders {

    const val CHANNEL = "mood.reminders"

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "mood reminders", NotificationManager.IMPORTANCE_HIGH)
                    .apply { description = "pill times and water nudges" }
            )
        }
    }

    fun saveScheduleJson(ctx: Context, json: String) {
        ctx.getSharedPreferences("mood", Context.MODE_PRIVATE)
            .edit().putString("reminders", json).apply()
    }

    fun rearmAll(ctx: Context) {
        val json = ctx.getSharedPreferences("mood", Context.MODE_PRIVATE)
            .getString("reminders", null) ?: return
        try { arm(ctx, JSONObject(json)) } catch (_: Exception) {}
    }

    /** cancels the old alarms and arms the next occurrence of each reminder */
    fun arm(ctx: Context, sched: JSONObject) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        // cancel previously armed request codes
        val prefs = ctx.getSharedPreferences("mood", Context.MODE_PRIVATE)
        prefs.getStringSet("armedCodes", emptySet())!!.forEach { code ->
            am.cancel(pending(ctx, code.toInt(), Intent(ctx, ReminderReceiver::class.java)))
        }
        val armed = mutableSetOf<String>()

        val pills = sched.optJSONArray("pills")
        if (pills != null) for (i in 0 until pills.length()) {
            val p = pills.getJSONObject(i)
            val times = p.optJSONArray("times") ?: continue
            val weekdays = p.optJSONArray("weekdays") ?: continue
            for (t in 0 until times.length()) for (w in 0 until weekdays.length()) {
                val time = times.getString(t)
                val wd = weekdays.getInt(w)             // 0 = monday
                val whenMs = nextWeekly(wd, time) ?: continue
                val code = ("p:" + p.optString("id") + ":" + time + ":" + wd).hashCode()
                val it = Intent(ctx, ReminderReceiver::class.java).apply {
                    putExtra("type", "pill")
                    putExtra("title", p.optString("name", "pill time"))
                    putExtra("body", listOf(p.optString("dose", ""), time).filter { s -> s.isNotBlank() }.joinToString(" · "))
                    putExtra("weekday", wd); putExtra("time", time); putExtra("code", code)
                }
                exact(am, whenMs, pending(ctx, code, it))
                armed.add(code.toString())
            }
        }

        val water = sched.optJSONObject("water")
        if (water != null && water.optBoolean("on", false)) {
            val whenMs = nextWaterSlot(water)
            if (whenMs != null) {
                val code = "water".hashCode()
                val it = Intent(ctx, ReminderReceiver::class.java).apply {
                    putExtra("type", "water")
                    putExtra("title", "water break ☕")
                    putExtra("body", "a glass now keeps the chain alive")
                    putExtra("code", code)
                }
                exact(am, whenMs, pending(ctx, code, it))
                armed.add(code.toString())
            }
        }
        prefs.edit().putStringSet("armedCodes", armed).apply()
    }

    fun pending(ctx: Context, code: Int, intent: Intent): PendingIntent =
        PendingIntent.getBroadcast(ctx, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun exact(am: AlarmManager, whenMs: Long, pi: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms())
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMs, pi)
        }
    }

    /** next occurrence of weekday (0=mon) at HH:MM, strictly in the future */
    fun nextWeekly(wd: Int, time: String): Long? {
        val parts = time.split(":"); if (parts.size != 2) return null
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, parts[0].toIntOrNull() ?: return null)
            set(Calendar.MINUTE, parts[1].toIntOrNull() ?: return null)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        // Calendar: sunday=1 … saturday=7 → ours: monday=0 … sunday=6
        val targetDow = ((wd + 1) % 7) + 1
        while (cal.get(Calendar.DAY_OF_WEEK) != targetDow || cal.timeInMillis <= System.currentTimeMillis())
            cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** next slot in the daily window [from..to] at every-N-minutes cadence */
    fun nextWaterSlot(water: JSONObject): Long? {
        val every = water.optInt("everyMin", 120).coerceAtLeast(15)
        val from = water.optString("from", "10:00").split(":")
        val to = water.optString("to", "22:00").split(":")
        if (from.size != 2 || to.size != 2) return null
        val now = Calendar.getInstance()
        val start = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, from[0].toInt()); set(Calendar.MINUTE, from[1].toInt())
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, to[0].toInt()); set(Calendar.MINUTE, to[1].toInt())
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var slot = start.clone() as Calendar
        while (slot.timeInMillis <= now.timeInMillis) slot.add(Calendar.MINUTE, every)
        if (slot.timeInMillis > end.timeInMillis) {           // past today's window → tomorrow's first slot
            slot = start.clone() as Calendar
            slot.add(Calendar.DAY_OF_MONTH, 1)
            slot.add(Calendar.MINUTE, every)
        }
        return slot.timeInMillis
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.ensureChannel(ctx)
        val type = intent.getStringExtra("type") ?: "pill"
        val title = intent.getStringExtra("title") ?: "mood"
        val body = intent.getStringExtra("body") ?: ""
        val code = intent.getIntExtra("code", type.hashCode())

        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        try {
            nm.notify(code, NotificationCompat.Builder(ctx, Reminders.CHANNEL)
                .setSmallIcon(R.drawable.ic_mood)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build())
        } catch (_: SecurityException) { /* notifications not granted */ }

        // re-arm the next occurrence
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (type == "pill") {
            val wd = intent.getIntExtra("weekday", 0)
            val time = intent.getStringExtra("time") ?: return
            Reminders.nextWeekly(wd, time)?.let { Reminders.exact(am, it, Reminders.pending(ctx, code, intent)) }
        } else {
            val json = ctx.getSharedPreferences("mood", Context.MODE_PRIVATE).getString("reminders", null) ?: return
            try {
                val water = JSONObject(json).optJSONObject("water") ?: return
                if (water.optBoolean("on", false))
                    Reminders.nextWaterSlot(water)?.let { Reminders.exact(am, it, Reminders.pending(ctx, code, intent)) }
            } catch (_: Exception) {}
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) Reminders.rearmAll(ctx)
    }
}
