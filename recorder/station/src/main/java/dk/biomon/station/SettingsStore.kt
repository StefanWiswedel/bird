package dk.biomon.station

import android.content.Context
import org.json.JSONObject

/** Persists [Settings] in SharedPreferences. Every field is a READ-time filter
 *  (API.md §"GET/POST /api/settings") except `retentionFloor`, which gates what gets
 *  written — see Database.insert callers, which read this before deciding to store. */
class SettingsStore(ctx: Context) {
    private val prefs = ctx.applicationContext.getSharedPreferences("station_settings", Context.MODE_PRIVATE)

    @Synchronized
    fun get(): Settings = Settings(
        displayThreshold = prefs.getFloat("display_threshold", 0.65f),
        retentionFloor = prefs.getFloat("retention_floor", 0.10f),
        repeatRequired = prefs.getInt("repeat_required", 2),
        repeatWindowMin = prefs.getInt("repeat_window_min", 30),
        regionSeasonFilterEnabled = prefs.getBoolean("region_season_filter_enabled", true)
    )

    @Synchronized
    fun update(patch: JSONObject): Settings {
        val merged = Settings.from(patch, get())
        prefs.edit()
            .putFloat("display_threshold", merged.displayThreshold)
            .putFloat("retention_floor", merged.retentionFloor)
            .putInt("repeat_required", merged.repeatRequired)
            .putInt("repeat_window_min", merged.repeatWindowMin)
            .putBoolean("region_season_filter_enabled", merged.regionSeasonFilterEnabled)
            .apply()
        return merged
    }
}
