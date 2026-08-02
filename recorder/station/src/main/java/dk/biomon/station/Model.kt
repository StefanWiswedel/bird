package dk.biomon.station

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The one representation of a detection. See station/API.md — that file is the contract
 * and this is its Kotlin form; if they disagree, the contract is right.
 *
 * WHY `taxon` IS A NESTED OBJECT WITH A `group`, when everything today is a bird. The
 * station will grow other detectors (this repo already runs an insect-board vision
 * pipeline and an Orthoptera audio path). If "bird" is baked into column names and JSON
 * keys, adding a second taxon means a schema migration AND a dashboard rewrite. A generic
 * taxon carrying its own `group` and `rank` costs nothing now and makes that a no-op.
 *
 * WHY `detector` IS ALSO NESTED, AND WHY SCORES ARE NEVER MERGED. BirdNET emits a 0-1
 * sigmoid confidence; Perch emits an uncalibrated logit; the insect classifier emits a
 * softmax over 27 morphotypes. Averaging or ranking across them produces a number that
 * looks meaningful and is not. `score_type` names the scale so nobody has to remember.
 */
data class Taxon(
    val key: String,
    val scientific: String,
    val common: String,
    val group: String = "bird",
    val rank: String = "species",
    val modelIndex: Int = -1,
    /** On the Danish checklist (station_species.json's `regional`). Advisory only —
     *  never gates the model or drops a class, only raises the bar to "confirmed"
     *  (see Database.effectiveThreshold). Defaults to true (open) for stored rows that
     *  predate this field, and for any Taxon built without species-list context. */
    val regional: Boolean = true,
    /** 12 Jan-Dec fractions of this species' Danish GBIF records, or null if unknown.
     *  Transient species-list metadata — read once at capture time to decide
     *  in-season-ness, never persisted or serialized itself (see NewDetection). */
    val monthFractions: List<Float>? = null,
    /** Total Danish GBIF records behind [monthFractions]. A species with few records
     *  gets every month treated as plausible — see [seasonallyPlausible]. */
    val monthsTotal: Int? = null
) {
    /** Common name first. A bare binomial is unreadable to anyone but a specialist, and
     *  this project treats that as a rule rather than a preference. */
    val display: String get() = if (common.isBlank()) scientific else "$common ($scientific)"

    fun json(): JSONObject = JSONObject()
        .put("key", key).put("scientific", scientific).put("common", common)
        .put("rank", rank).put("group", group).put("model_index", modelIndex)
        .put("regional", regional)
}

data class Detection(
    val id: Long,
    val detectedAtMs: Long,
    val windowStartMs: Long,
    val windowMs: Int,
    val taxon: Taxon,
    val detector: String,
    val detectorVersion: String,
    val scoreType: String,
    val confidence: Float,
    val clipPath: String?,
    val clipSeconds: Double,
    /** Filled at READ time by the curator, never stored — see Curator. */
    val state: String = "candidate",
    val repeatCount: Int = 1,
    /** Snapshot of the species' Danish-checklist membership and this window's monthly
     *  plausibility, captured at INSERT time (see NewDetection) so re-evaluating a past
     *  detection judges it against the month it actually occurred in, not the month the
     *  dashboard happens to be viewed in. Advisory display context, not a filter result —
     *  `state` already reflects any penalty these caused (Database.effectiveThreshold). */
    val regional: Boolean = true,
    val inSeason: Boolean = true
) {
    fun json(station: JSONObject, photo: JSONObject?): JSONObject = JSONObject()
        .put("schema", SCHEMA)
        .put("id", id)
        .put("detected_at_ms", detectedAtMs)
        .put("detected_at", iso(detectedAtMs))
        .put("window", JSONObject().put("start_ms", windowStartMs).put("duration_ms", windowMs))
        .put("taxon", taxon.json())
        .put("detector", JSONObject()
            .put("name", detector).put("version", detectorVersion).put("score_type", scoreType))
        .put("confidence", Math.round(confidence * 1000f) / 1000.0)
        .put("state", state)
        .put("repeat_count", repeatCount)
        .put("regional", regional)
        .put("in_season", inSeason)
        .put("clip", if (clipPath == null) JSONObject.NULL else JSONObject()
            .put("url", "/api/clip/$id").put("seconds", clipSeconds).put("mime", "audio/wav"))
        .put("photo", photo ?: JSONObject.NULL)
        .put("station", station)

    companion object {
        const val SCHEMA = 1

        private val ISO = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
        }
        private val DAY = ThreadLocal.withInitial {
            SimpleDateFormat("yyyy-MM-dd", Locale.US)
        }

        /** Local time with a real offset, so the dashboard never has to guess a zone. */
        fun iso(ms: Long): String = ISO.get()!!.apply { timeZone = TimeZone.getDefault() }
            .format(Date(ms))

        /** Station-local calendar day. Stored per row so "today" is one indexed lookup
         *  rather than a timezone calculation over every row in the table. */
        fun localDate(ms: Long): String = DAY.get()!!.apply { timeZone = TimeZone.getDefault() }
            .format(Date(ms))
    }
}

/**
 * Curation settings. Every one of these is applied at QUERY time over rows already
 * stored, so moving a threshold re-filters instantly and reprocesses nothing.
 *
 * `retentionFloor` is the one exception and the asymmetry is deliberate: it decides what
 * is WRITTEN, so lowering it only affects future detections. Rows below the old floor
 * were never stored and cannot be recovered. The UI says so.
 *
 * DEFAULTS ARE A STARTING POINT, NOT A MEASUREMENT. This project's rule is to pick
 * thresholds from a measured table rather than a guess, and no such table exists yet for
 * this microphone in this location — it needs a night of real audio and a look at what
 * lands either side of the line. 0.65 is BirdNET-Pi's commonly used operating point and
 * is where to start, not where to stop. The repeat rule is the same shape as the fix that
 * took this project's live bird ID from 51 species to 7: a single high score is weak
 * evidence, the same species twice is much stronger.
 */
data class Settings(
    val displayThreshold: Float = 0.65f,
    val retentionFloor: Float = 0.10f,
    val repeatRequired: Int = 2,
    val repeatWindowMin: Int = 30,
    /** Whether out-of-region / out-of-season taxa get an automatic threshold penalty
     *  (Database.effectiveThreshold). A species with an explicit per-species override
     *  (the `species_settings` table, set via `POST /api/species/{key}/threshold`) is
     *  unaffected by this flag either way — an override is a human's precise answer for
     *  that species and always wins over the heuristic. */
    val regionSeasonFilterEnabled: Boolean = true
) {
    fun json(): JSONObject = JSONObject()
        .put("display_threshold", displayThreshold.toDouble())
        .put("retention_floor", retentionFloor.toDouble())
        .put("repeat_required", repeatRequired)
        .put("repeat_window_min", repeatWindowMin)
        .put("region_season_filter_enabled", regionSeasonFilterEnabled)

    companion object {
        fun from(o: JSONObject, base: Settings) = Settings(
            displayThreshold = o.optDouble("display_threshold", base.displayThreshold.toDouble())
                .toFloat().coerceIn(0f, 1f),
            retentionFloor = o.optDouble("retention_floor", base.retentionFloor.toDouble())
                .toFloat().coerceIn(0f, 1f),
            repeatRequired = o.optInt("repeat_required", base.repeatRequired).coerceIn(1, 10),
            repeatWindowMin = o.optInt("repeat_window_min", base.repeatWindowMin).coerceIn(1, 24 * 60),
            regionSeasonFilterEnabled = o.optBoolean("region_season_filter_enabled", base.regionSeasonFilterEnabled)
        )
    }
}

/** Station identity. Travels with every detection so a later remote push needs no extra
 *  plumbing to say where the observation came from. */
data class Station(
    val id: String,
    val name: String,
    val lat: Double?,
    val lon: Double?
) {
    fun json(): JSONObject = JSONObject()
        .put("id", id).put("name", name)
        .put("lat", lat ?: JSONObject.NULL).put("lon", lon ?: JSONObject.NULL)
        .put("tz", TimeZone.getDefault().id)
}

fun jsonArrayOf(items: List<JSONObject>): JSONArray =
    JSONArray().also { a -> items.forEach { a.put(it) } }

/** Is `calendarMonth1to12` a plausible month for a species, given its Danish GBIF
 *  record history? Mirrors the rule DESIGN.md documents for the same data (§4):
 *  a month counts as plausible at >= 2% of that species' annual Danish records;
 *  species with too little Danish data (< 30 records) get every month open, because a
 *  thin sample's "0% in July" is an artifact of sample size, not evidence of absence;
 *  a species with no Danish record data at all also fails open, for the same reason
 *  §4 requires it for `regional` — a missing class is worse than a false positive, and
 *  an over-eager seasonal filter would recreate exactly that failure mode by silence.
 */
fun seasonallyPlausible(monthFractions: List<Float>?, monthsTotal: Int?, calendarMonth1to12: Int): Boolean {
    if (monthFractions == null) return true
    if ((monthsTotal ?: 0) < 30) return true
    return monthFractions[calendarMonth1to12 - 1] >= 0.02f
}
