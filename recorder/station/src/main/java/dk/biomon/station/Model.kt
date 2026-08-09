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
    /** Wall-clock start of the clip's audio. A bout clip begins before the detection that
     *  triggered it (BoutRecorder's pre-roll), so `detected_at_ms - clip_start_ms` is where
     *  in the file this detection actually is. Null on rows that predate bout clips —
     *  unknown, which is not the same as zero. */
    val clipStartMs: Long? = null,
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
            .put("url", "/api/clip/$id").put("seconds", clipSeconds).put("mime", "audio/wav")
            .put("starts_at_ms", clipStartMs ?: JSONObject.NULL)
            // Where in the clip this detection begins. Computed here rather than in the
            // client so every consumer agrees, and null — never 0 — when the clip's start
            // is unknown, because "play from the beginning" and "we don't know where it is"
            // are different answers (§2d).
            .put("detection_offset_s", clipStartMs?.let {
                Math.round((detectedAtMs - it) / 100.0) / 10.0 } ?: JSONObject.NULL))
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
    /**
     * Confidence a window must reach before its AUDIO is kept. Deliberately SEPARATE from
     * [retentionFloor], because the two govern costs that differ by three orders of
     * magnitude: a detection row is ~358 bytes, and the 3 s clip beside it was 288,000.
     *
     * Measured on the 19 h corpus of 2026-08-02/03: at a single 0.10 floor the station
     * wrote 5,022 clips in 19 hours — 667 GB/year, against 908 MB/year of rows — and the
     * 8 GB cap therefore held FOUR DAYS of audio. Raising only the clip floor to 0.50
     * keeps 266 of those clips and takes the same cap to ~83 days, without discarding a
     * single row.
     *
     * The asymmetry is the point. Rows are cheap and unrecoverable (nothing can re-derive
     * them once the audio is gone), so they stay at 0.10 where a slider can still
     * re-filter them and where the §8 co-occurrence hypothesis remains testable — every
     * one of its 12 multi-species windows sits below 0.41. Clips are expensive and largely
     * redundant with the row that describes them, and nobody verifies a 0.2 candidate by
     * ear. So: keep every row, keep audio only where a human might actually listen.
     */
    val clipFloor: Float = 0.50f,
    val repeatRequired: Int = 2,
    val repeatWindowMin: Int = 30,
    /**
     * Seconds of silence that must separate two detections before they count as separate
     * BOUTS. This is the fix for the rule that let a 19-window noise event through.
     *
     * The old rule counted detections, and at a 3.0 s window on a 1.5 s hop consecutive
     * detections share half their audio — so "twice in 30 minutes" was satisfied by one
     * continuous sound lasting three seconds. Measured: of 13 rows over threshold it
     * rejected 2, and 30 of 47 Spotted Crake gaps were exactly 1.5 s, i.e. the hop.
     *
     * A gap requirement states the rule about the ANIMAL instead of about the analysis
     * window: it called, it stopped, and it called again a minute later. A machine that
     * runs continuously produces one bout no matter how long it runs.
     */
    val boutGapSeconds: Int = 60,
    /** Whether out-of-region / out-of-season taxa get an automatic threshold penalty
     *  (Database.effectiveThreshold). A species with an explicit per-species override
     *  (the `species_settings` table, set via `POST /api/species/{key}/threshold`) is
     *  unaffected by this flag either way — an override is a human's precise answer for
     *  that species and always wins over the heuristic. */
    val regionSeasonFilterEnabled: Boolean = true,
    /**
     * xeno-canto API key, for reference recordings (§11q). An ordinary setting, entered
     * once from the dashboard — NOT a build secret and not a repo secret: it is rate-limit
     * attribution against a public archive, not access to anything of the owner's.
     *
     * It still never leaves the phone. It is never logged, never accepted as a build
     * argument, and never echoed back: [json] reports whether a key is SET, not what it
     * is, so a dashboard screenshot or a mock recording of /api/settings cannot leak it.
     * The empty string means "no key", which is the state the station ships in and which
     * every code path must handle honestly (§2d).
     */
    val xenoCantoKey: String = ""
) {
    fun json(): JSONObject = JSONObject()
        .put("display_threshold", displayThreshold.toDouble())
        .put("retention_floor", retentionFloor.toDouble())
        .put("clip_floor", clipFloor.toDouble())
        .put("repeat_required", repeatRequired)
        .put("repeat_window_min", repeatWindowMin)
        .put("bout_gap_s", boutGapSeconds)
        .put("region_season_filter_enabled", regionSeasonFilterEnabled)
        // PRESENCE, never the value. See xenoCantoKey.
        .put("xeno_canto_key_set", xenoCantoKey.isNotBlank())

    companion object {
        fun from(o: JSONObject, base: Settings) = Settings(
            displayThreshold = o.optDouble("display_threshold", base.displayThreshold.toDouble())
                .toFloat().coerceIn(0f, 1f),
            retentionFloor = o.optDouble("retention_floor", base.retentionFloor.toDouble())
                .toFloat().coerceIn(0f, 1f),
            clipFloor = o.optDouble("clip_floor", base.clipFloor.toDouble())
                .toFloat().coerceIn(0f, 1f),
            repeatRequired = o.optInt("repeat_required", base.repeatRequired).coerceIn(1, 10),
            repeatWindowMin = o.optInt("repeat_window_min", base.repeatWindowMin).coerceIn(1, 24 * 60),
            // 0 disables the gap requirement and restores the old count-detections rule.
            // Kept reachable rather than removed: the old behaviour is what every stored
            // row was curated under, and a reader comparing against history needs it.
            boutGapSeconds = o.optInt("bout_gap_s", base.boutGapSeconds).coerceIn(0, 3600),
            regionSeasonFilterEnabled = o.optBoolean("region_season_filter_enabled", base.regionSeasonFilterEnabled),
            // Absent leaves the stored key alone; an explicit empty string clears it. A
            // PATCH that simply does not mention the key must never wipe it, which is what
            // would happen if this defaulted to "".
            xenoCantoKey = if (o.has("xeno_canto_key")) o.optString("xeno_canto_key").trim()
                           else base.xenoCantoKey
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
