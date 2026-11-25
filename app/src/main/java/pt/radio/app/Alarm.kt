package pt.radio.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONObject
import java.util.Calendar

/** Despertador: guarda a hora, os dias e a rádio, e marca o próximo toque no sistema. */
object Alarm {
    const val ACTION = "pt.radio.app.ALARM"
    const val STOP = "pt.radio.app.ALARM_STOP"
    const val SNOOZE = "pt.radio.app.ALARM_SNOOZE"

    private fun prefs(c: Context) = c.getSharedPreferences("radio", Context.MODE_PRIVATE)

    fun get(c: Context): JSONObject =
        try { JSONObject(prefs(c).getString("alarm", "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun put(c: Context, o: JSONObject) { prefs(c).edit().putString("alarm", o.toString()).apply() }

    fun canExact(c: Context): Boolean {
        if (Build.VERSION.SDK_INT < 31) return true
        return (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms()
    }

    private fun firePi(c: Context): PendingIntent =
        PendingIntent.getForegroundService(c, 71, Intent(c, RadioService::class.java).setAction(ACTION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun showPi(c: Context): PendingIntent =
        PendingIntent.getActivity(c, 72, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    /** Próxima hora a tocar, a partir de agora. Dias: 1 = segunda … 7 = domingo; vazio = uma vez. */
    fun nextTime(o: JSONObject, from: Long = System.currentTimeMillis()): Long {
        val h = o.optInt("h", 7); val m = o.optInt("m", 30)
        val days = o.optJSONArray("days")
        val set = HashSet<Int>()
        if (days != null) for (i in 0 until days.length()) set.add(days.getInt(i))
        val cal = Calendar.getInstance().apply { timeInMillis = from; set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, m) }
        for (k in 0..7) {
            if (cal.timeInMillis > from) {
                val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1 // segunda = 1
                if (set.isEmpty() || set.contains(dow)) return cal.timeInMillis
            }
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    private fun setAt(c: Context, at: Long) {
        val am = c.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setAlarmClock(AlarmManager.AlarmClockInfo(at, showPi(c)), firePi(c))
    }

    /** Guarda e marca. Devolve "perm" se faltar a autorização para alarmes exatos. */
    fun save(c: Context, json: String): String {
        val o = try { JSONObject(json) } catch (e: Exception) { JSONObject() }
        val old = get(c)
        if (!o.optBoolean("on", false)) {
            cancel(c); old.put("on", false); old.remove("next"); put(c, old); return ""
        }
        if (!canExact(c)) {
            try {
                c.startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                    .setData(android.net.Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {}
            return "perm"
        }
        val next = nextTime(o)
        o.put("next", next)
        put(c, o)
        setAt(c, next)
        return next.toString()
    }

    fun cancel(c: Context) {
        (c.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(firePi(c))
    }

    /** Depois de tocar: marca o próximo dia, ou desliga se era só uma vez. */
    fun fired(c: Context) {
        val o = get(c)
        val days = o.optJSONArray("days")
        if (days == null || days.length() == 0) { o.put("on", false); o.remove("next"); put(c, o); return }
        val next = nextTime(o, System.currentTimeMillis() + 60000)
        o.put("next", next); put(c, o)
        if (canExact(c)) setAt(c, next)
    }

    fun snooze(c: Context, min: Int) {
        if (!canExact(c)) return
        val at = System.currentTimeMillis() + min * 60000L
        setAt(c, at)
        val o = get(c); o.put("snooze", at); put(c, o)
    }

    /** Depois de reiniciar o telemóvel, volta a marcar. */
    fun restore(c: Context) {
        val o = get(c)
        if (!o.optBoolean("on", false) || !canExact(c)) return
        val next = nextTime(o); o.put("next", next); put(c, o); setAt(c, next)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { Alarm.restore(context) }
}
