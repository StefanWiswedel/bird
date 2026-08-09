package dk.biomon.station

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Sunrise, sunset and civil twilight for the station's own coordinates, computed locally.
 *
 * WHY THE ACTIVITY STRIP IS NOT BINNED BY CLOCK TIME. Copenhagen's sunrise moves from
 * about 04:26 at midsummer to about 08:37 at midwinter — over four hours. Birds key on the
 * sun, not on the clock, so fixed clock bins smear one dawn chorus across different columns
 * through the year and destroy exactly the pattern the strip exists to show. Anchoring the
 * bins to solar events makes "dawn" mean the same thing in June and December.
 *
 * NO NETWORK AND NO DEPENDENCY (build.gradle.kts's rule, and the station must work with the
 * wifi down anyway). This is the standard sunrise equation — mean solar anomaly, equation
 * of the centre, ecliptic longitude, declination, hour angle — accurate to a minute or so
 * at these latitudes, which is far inside the width of a six-way bin.
 */
object Solar {

    private const val DEG = Math.PI / 180.0
    private const val JD_EPOCH = 2440587.5          // Julian day at 1970-01-01T00:00:00Z
    private const val OBLIQUITY = 23.4397

    /** Standard refraction-corrected altitude of the sun's upper limb at sunrise/sunset. */
    const val ALT_SUNRISE = -0.833
    /** Civil twilight: enough light to make out shapes, and where the chorus starts. */
    const val ALT_CIVIL = -6.0

    /**
     * The six periods a day is divided into for the activity strip, in time order.
     *
     * Deliberately COARSE. Six bins across a full-width strip stays legible on a phone at
     * any width, needs no horizontal scroll, and cannot imply a precision the data does not
     * have — a 24-column grid would suggest hourly resolution over what is often a handful
     * of bouts.
     */
    val BIN_LABELS = listOf("night", "dawn", "morning", "afternoon", "dusk", "late")

    data class Day(
        val civilDawnMs: Long, val sunriseMs: Long, val solarNoonMs: Long,
        val sunsetMs: Long, val civilDuskMs: Long,
        /** Seven boundaries, six bins, strictly increasing. */
        val binEdgesMs: LongArray,
        /** False when the sun never crossed a threshold that day and the edges are
         *  interpolated instead of observed — the strip still works, but it is not solar
         *  any more and the API says so rather than quietly presenting a guess (§2d). */
        val solar: Boolean
    )

    /** Local midnight for [date] (`YYYY-MM-DD`) in [tz], as epoch ms. */
    private fun startOfDay(date: String, tz: TimeZone): Long {
        val parts = date.split("-")
        val c = Calendar.getInstance(tz)
        c.clear()
        c.set(parts[0].toInt(), parts[1].toInt() - 1, parts[2].toInt(), 0, 0, 0)
        return c.timeInMillis
    }

    /**
     * Solar events for one local date at [lat]/[lon].
     *
     * Bin edges are midnight, civil dawn, an hour after sunrise, solar noon, an hour before
     * sunset, civil dusk, midnight. The hour after sunrise is where the dawn chorus
     * actually is — it is not the same thing as "morning", and a bin that lumped them
     * together would hide the single strongest pattern in the data.
     */
    fun forDate(date: String, lat: Double, lon: Double, tz: TimeZone = TimeZone.getDefault()): Day {
        val dayStart = startOfDay(date, tz)
        val dayEnd = dayStart + 86_400_000L
        val noonGuess = dayStart + 43_200_000L

        val transit = solarTransit(noonGuess, lon)
        val sunrise = hourAngleEvent(transit, lat, lon, ALT_SUNRISE, rising = true)
        val sunset = hourAngleEvent(transit, lat, lon, ALT_SUNRISE, rising = false)
        val dawn = hourAngleEvent(transit, lat, lon, ALT_CIVIL, rising = true)
        val dusk = hourAngleEvent(transit, lat, lon, ALT_CIVIL, rising = false)

        val solar = sunrise != null && sunset != null && dawn != null && dusk != null
        // Fallbacks keep the strip usable inside the polar circle and on any date where the
        // sun does not cross a threshold at all. They are NOT presented as solar times.
        val riseMs = sunrise ?: (dayStart + 6 * 3_600_000L)
        val setMs = sunset ?: (dayStart + 18 * 3_600_000L)
        val dawnMs = dawn ?: (riseMs - 40 * 60_000L)
        val duskMs = dusk ?: (setMs + 40 * 60_000L)

        // Monotonic by construction: coerceIn chains each edge past the previous one, so a
        // degenerate day (midnight sun, an event that lands outside its own date) produces
        // a squashed bin rather than a negative-width one the UI would render inside out.
        val e0 = dayStart
        val e1 = dawnMs.coerceIn(e0, dayEnd)
        val e2 = (riseMs + 3_600_000L).coerceIn(e1, dayEnd)
        val e3 = transit.coerceIn(e2, dayEnd)
        val e4 = (setMs - 3_600_000L).coerceIn(e3, dayEnd)
        val e5 = duskMs.coerceIn(e4, dayEnd)
        return Day(dawnMs, riseMs, transit, setMs, duskMs,
            longArrayOf(e0, e1, e2, e3, e4, e5, dayEnd), solar)
    }

    /** Which of the six bins [atMs] falls in. */
    fun binOf(day: Day, atMs: Long): Int {
        for (i in 0 until 6) if (atMs < day.binEdgesMs[i + 1]) return i
        return 5
    }

    // ---- the sunrise equation ---------------------------------------------------------

    private fun julian(ms: Long): Double = ms / 86_400_000.0 + JD_EPOCH
    private fun fromJulian(jd: Double): Long = Math.round((jd - JD_EPOCH) * 86_400_000.0)

    /** Julian day of solar noon nearest [nearMs] at longitude [lon]. */
    private fun solarTransit(nearMs: Long, lon: Double): Long {
        val n = floor(julian(nearMs) - 2451545.0 + 0.0008 - (-lon) / 360.0 + 0.5)
        val jStar = 2451545.0 + 0.0009 + (-lon) / 360.0 + n
        val m = meanAnomaly(jStar)
        val lambda = eclipticLongitude(m)
        return fromJulian(jStar + 0.0053 * sin(m * DEG) - 0.0069 * sin(2 * lambda * DEG))
    }

    private fun meanAnomaly(jd: Double): Double = (357.5291 + 0.98560028 * (jd - 2451545.0)) % 360.0

    private fun eclipticLongitude(m: Double): Double {
        val c = 1.9148 * sin(m * DEG) + 0.02 * sin(2 * m * DEG) + 0.0003 * sin(3 * m * DEG)
        return (m + c + 180.0 + 102.9372) % 360.0
    }

    /** Epoch ms of the sun reaching [altitudeDeg], or null when it never does that day. */
    private fun hourAngleEvent(transitMs: Long, lat: Double, lon: Double,
                               altitudeDeg: Double, rising: Boolean): Long? {
        val jTransit = julian(transitMs)
        val m = meanAnomaly(jTransit)
        val lambda = eclipticLongitude(m)
        val declination = asin(sin(lambda * DEG) * sin(OBLIQUITY * DEG)) / DEG
        val cosOmega = (sin(altitudeDeg * DEG) - sin(lat * DEG) * sin(declination * DEG)) /
                       (cos(lat * DEG) * cos(declination * DEG))
        if (abs(cosOmega) > 1.0) return null        // sun stays above, or never rises to, this altitude
        val omega = acos(cosOmega) / DEG
        return fromJulian(jTransit + (if (rising) -omega else omega) / 360.0)
    }
}
