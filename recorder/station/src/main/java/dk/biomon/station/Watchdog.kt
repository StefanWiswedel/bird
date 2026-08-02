package dk.biomon.station

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build

/**
 * Layer 3 of OxygenOS survival (StationService's class doc): a periodic AlarmManager
 * check that lives OUTSIDE the service process, because a killed process cannot restart
 * itself. If the pipeline's own heartbeat (written every ~1.5 s by
 * StationService.onWindow) is older than [STALE_MS], the service — or its whole process
 * — is assumed dead and gets an explicit restart, not just re-armed and hoped for.
 *
 * setExactAndAllowWhileIdle is used rather than a plain repeating alarm: Doze can defer
 * ordinary alarms for hours, which would make the watchdog itself unreliable exactly
 * when it matters most.
 */
class Watchdog : BroadcastReceiver() {
    companion object {
        private const val INTERVAL_MS = 10 * 60_000L   // check cadence
        private const val STALE_MS = 5 * 60_000L        // heartbeat older than this = dead
        private const val REQUEST_CODE = 1001

        private fun pendingIntent(ctx: Context): PendingIntent {
            val i = Intent(ctx, Watchdog::class.java)
            return PendingIntent.getBroadcast(ctx, REQUEST_CODE, i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun schedule(ctx: Context) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val triggerAt = System.currentTimeMillis() + INTERVAL_MS
            val pi = pendingIntent(ctx)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs: SharedPreferences = ctx.getSharedPreferences(StationService.PREFS, Context.MODE_PRIVATE)
        val lastBeat = prefs.getLong(StationService.HEARTBEAT_KEY, 0L)
        val stale = lastBeat == 0L || (System.currentTimeMillis() - lastBeat) > STALE_MS
        if (stale) {
            android.util.Log.w("Watchdog", "heartbeat stale (last=$lastBeat) — restarting StationService")
            val svc = Intent(ctx, StationService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
            else ctx.startService(svc)
        }
        // Re-arm regardless — a running service also depends on this to keep the
        // watchdog itself alive across Doze, since it is not a repeating alarm.
        schedule(ctx)
    }
}
