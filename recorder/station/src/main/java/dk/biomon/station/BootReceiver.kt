package dk.biomon.station

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

/** Starts the station back up after a reboot, an app update (MY_PACKAGE_REPLACED), or
 *  an OEM quick-boot — a station that needs a human to re-open the app after every
 *  power blip is not "always on" (DESIGN intent, station/API.md). Also (re)arms the
 *  Watchdog, since its alarm does not survive a reboot on its own. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val svc = Intent(ctx, StationService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(svc)
        else ctx.startService(svc)
        Watchdog.schedule(ctx)
    }
}
