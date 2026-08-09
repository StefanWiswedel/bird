package dk.biomon.station

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.File

/** What the capture/inference loop hands to the database — one scored window that
 *  cleared the retention floor. Everything else about a Detection (state, repeat_count)
 *  is derived at READ time (API.md): nothing here is a threshold decision.
 *
 *  [taxonRegional]/[taxonInSeason] ARE a capture-time decision, unlike the rest of this
 *  file's read-time philosophy — deliberately: they judge the window against the
 *  species-list data and the calendar month AS THEY WERE when the bird was heard, so a
 *  detection from a July night is judged against July's plausibility forever, not
 *  against whatever month the dashboard happens to be viewed in. See
 *  Database.effectiveThreshold for how they feed into `state`. */
data class NewDetection(
    val detectedAtMs: Long,
    val windowMs: Int,
    val taxon: Taxon,
    val detector: String,
    val detectorVersion: String,
    val scoreType: String,
    val confidence: Float,
    /** All three are null/zero at insert time and filled in later by [Database.attachClip].
     *  A bout clip cannot exist when its first window is scored — it runs on past the
     *  trigger — so the row is written first and the audio catches up. See BoutRecorder. */
    val clipPath: String?,
    val clipSeconds: Double,
    val clipStartMs: Long? = null,
    val taxonRegional: Boolean = true,
    val taxonInSeason: Boolean = true
)

/** A stored row plus the read-time curation fields computed for it. */
data class DetectionRow(
    val id: Long, val detectedAtMs: Long, val windowMs: Int, val taxon: Taxon,
    val detector: String, val detectorVersion: String, val scoreType: String,
    val confidence: Float, val clipPath: String?, val clipSeconds: Double,
    val repeatCount: Int, val state: String,
    val clipStartMs: Long? = null,
    val taxonRegional: Boolean = true, val taxonInSeason: Boolean = true
) {
    fun toDetection(): Detection = Detection(
        id = id, detectedAtMs = detectedAtMs, windowStartMs = detectedAtMs, windowMs = windowMs,
        taxon = taxon, detector = detector, detectorVersion = detectorVersion, scoreType = scoreType,
        confidence = confidence, clipPath = clipPath, clipSeconds = clipSeconds,
        clipStartMs = clipStartMs,
        state = state, repeatCount = repeatCount, regional = taxonRegional, inSeason = taxonInSeason
    )
}

/**
 * One clip file inside a bout. A bout may own several, because the two gaps are different
 * questions (§11m): `boutGapSeconds` (60 s) decides whether detections are the same piece
 * of EVIDENCE, and BoutRecorder's 4 s post-roll decides whether it is worth recording the
 * silence between them. A bird calling every twenty seconds is therefore one bout and
 * several clips — and the bout, not the clip, is the thing a person judges.
 *
 * [detectionId] is what `/api/clip/{id}` is keyed on, not an identity of its own.
 */
data class BoutClip(val detectionId: Long, val startMs: Long?, val seconds: Double, val path: String)

/**
 * Why a bout has the audio it has. THE WHOLE POINT OF THIS TYPE is that "not yet" is not
 * "none" (§2d): a bout recorded ninety seconds ago whose clips are still being written
 * looks exactly like a bout that never had audio, and rendering the first as the second
 * invites blaming the recorder for a UI mistake.
 *
 * - `recorded`  — every detection that was meant to have audio has it.
 * - `partial`   — some do and some do not. Pruning takes whole files, so a multi-clip bout
 *                 can lose its beginning and keep its end; the row has to say so rather
 *                 than present what is left as the whole thing.
 * - `pending`   — recent enough that BoutRecorder may still be finishing the write.
 * - `none`      — nothing here ever cleared the clip floor, so no audio was ever recorded.
 * - `unavailable` — audio was recorded and is gone: pruned by the cap, or the write failed.
 */
data class BoutAudio(val state: String, val seconds: Double, val covered: Int, val expected: Int)

/**
 * A run of detections of one taxon with no gap longer than `boutGapSeconds` — the unit of
 * EVIDENCE, and now the unit of judgement. [countBouts] has counted these all along; this
 * materialises them so they can be listed, played and (in the verification flow) decided.
 *
 * [boutId] is the first detection's id. Derived, not stored, because curation stays a
 * read-time operation: change `boutGapSeconds` and bouts legitimately merge or split, and
 * the ids move with them. Stable for as long as that setting is.
 */
data class Bout(
    val boutId: Long,
    val taxon: Taxon,
    val startMs: Long,
    val endMs: Long,
    val detectionCount: Int,
    val detectionIds: List<Long>,
    val peakConfidence: Float,
    /** The bar this bout's rows had to clear — a per-species override, or the global
     *  default plus a plausibility penalty. Carried so the UI can show the count and the
     *  evidence together rather than a bare "37 bouts" that reads as fact. */
    val threshold: Float,
    val aboveThreshold: Boolean,
    val clips: List<BoutClip>,
    val audio: BoutAudio,
    /**
     * How much of this bout has been judged. Verdicts are stored per DETECTION, so when
     * `bout_gap_s` moves and two decided bouts merge, the result is genuinely part-decided
     * — `partial` says so instead of rounding to done or not-done.
     */
    val verifiedDetections: Int = 0,
    val verifyState: String = "none",       // none | partial | done
    /** The answer given, when every judged detection here agrees. Null when they disagree
     *  (a merged bout carrying two different verdicts) or nothing is judged yet. */
    val verdictIsGenuine: String? = null,
    val verdictIsSpecies: String? = null
)

/** One species on one day: the row of the day list. */
data class DaySpecies(
    val taxon: Taxon,
    val bouts: Int,
    val boutsAboveThreshold: Int,
    val detections: Int,
    val firstMs: Long,
    val lastMs: Long,
    val peakConfidence: Float,
    val threshold: Float,
    /** Highest-confidence bout that actually has audio, for a one-tap listen. */
    val bestBoutId: Long?,
    val bestClip: BoutClip?,
    /** Bouts per solar bin (six), for the activity strip. */
    val activity: IntArray,
    val status: String
)

data class DaySummary(val date: String, val detectionCount: Int, val speciesCount: Int)
data class SpeciesAgg(val taxon: Taxon, val count: Int, val lastMs: Long, val bestConfidence: Float)
data class DaySummaryData(
    val date: String, val speciesCount: Int, val detectionCount: Int,
    val firstMs: Long?, val lastMs: Long?, val mostActive: Pair<Taxon, Int>?,
    val byHour: IntArray, val top: List<SpeciesAgg>
)

/**
 * SQLite persistence. Curation (`state`, `repeat_count`) is computed at query time from
 * [Settings] passed in on every read — nothing is reprocessed when a threshold moves
 * (API.md §1 "Field rules that matter"). Rows below `retentionFloor` are never written
 * at all; that is the one setting applied at WRITE time, and the asymmetry is by design.
 */
class Database(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "station.db", null, 7) {

    /**
     * The life list, its evidence, and the local-occurrence prior.
     *
     * A species enters the list by VERIFICATION, never by score. That is the whole design:
     * gating the list on a human decision is what turns verification from a chore into the
     * thing that earns the reward. Nothing is grandfathered — the list starts empty and
     * fills up only as the observer confirms.
     */
    private fun createLifeList(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE species_status (
                scientific_name     TEXT PRIMARY KEY,
                common_name         TEXT,
                status              TEXT NOT NULL,
                first_detected_at   TEXT,
                first_confirmed_at  TEXT,
                lifer_detection_id  INTEGER,
                best_detection_id   INTEGER,
                total_detections    INTEGER NOT NULL DEFAULT 0,
                station             TEXT NOT NULL
            )
        """.trimIndent())
        createVerifications(db)
        db.execSQL("""
            CREATE TABLE species_prior (
                scientific_name  TEXT NOT NULL,
                week             INTEGER NOT NULL,
                prior            REAL NOT NULL,
                PRIMARY KEY (scientific_name, week)
            )
        """.trimIndent())
    }

    /**
     * Detection ids that must survive everything: the clip that earned a lifer, and the
     * best clip held for a species. Losing either is unrecoverable — the audio behind a
     * lifer cannot be re-recorded.
     *
     * This is consulted by BOTH the storage cap and the "clear all" path, and any future
     * migration must consult it too. That is not hypothetical caution: onUpgrade has
     * issued an unconditional DELETE FROM detections in two of five schema versions, and
     * pruneToCapBytes deletes clip FILES while leaving rows behind. At the measured clip
     * rate the 8 GB cap turns over in weeks, so an unpinned lifer clip is not a distant
     * risk, it is a scheduled deletion.
     */
    fun pinnedDetectionIds(): Set<Long> {
        val out = HashSet<Long>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT lifer_detection_id FROM species_status WHERE lifer_detection_id IS NOT NULL " +
                "UNION SELECT best_detection_id FROM species_status WHERE best_detection_id IS NOT NULL", null
            ).use { while (it.moveToNext()) out.add(it.getLong(0)) }
        }
        return out
    }

    /** One definition, used by onCreate and by the v7 rebuild — two copies of a schema
     *  are two schemas waiting to disagree. */
    private fun createVerifications(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE verifications (
                detection_id  INTEGER NOT NULL,
                -- TWO QUESTIONS, TWO COLUMNS. "Is there really an animal here?" is
                -- answerable every time and is where nearly all the value is, because the
                -- false-positive mass is the actual problem; "is it THIS species?" often is
                -- not, and "something real, but I don't know what" is a true answer. One
                -- boolean recorded that as "no", which put a judgement nobody made into the
                -- table the life list is built from (§2d).
                --
                -- NOT `is_genuine`: taxon.group exists so non-birds are not a breaking change
                -- (API.md §1), and the station already carries Orthoptera. The question is
                -- "real animal or noise?" and the column is named for that.
                is_genuine    TEXT NOT NULL,          -- yes | no | unsure
                is_species    TEXT,                   -- yes | no | unsure, null if not asked
                verified_at   TEXT NOT NULL,
                note          TEXT
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_verif_det ON verifications(detection_id)")
    }

    private fun createDetections(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE detections (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                detected_at_ms INTEGER NOT NULL,
                local_date TEXT NOT NULL,
                window_ms INTEGER NOT NULL,
                taxon_key TEXT NOT NULL,
                taxon_scientific TEXT NOT NULL,
                taxon_common TEXT NOT NULL,
                taxon_group TEXT NOT NULL,
                taxon_rank TEXT NOT NULL,
                taxon_model_index INTEGER NOT NULL,
                taxon_regional INTEGER NOT NULL DEFAULT 1,
                taxon_in_season INTEGER NOT NULL DEFAULT 1,
                detector TEXT NOT NULL,
                detector_version TEXT NOT NULL,
                score_type TEXT NOT NULL,
                confidence REAL NOT NULL,
                clip_path TEXT,
                clip_seconds REAL NOT NULL,
                -- Wall-clock start of the clip's AUDIO, which is earlier than
                -- detected_at_ms by the pre-roll. Lets a player seek to where this
                -- detection actually sits inside a bout clip instead of starting at 0.
                -- Null for rows written before bout clips existed, and for rows whose
                -- clip was never finished.
                clip_start_ms INTEGER
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_det_taxon_time ON detections(taxon_key, detected_at_ms)")
        db.execSQL("CREATE INDEX idx_det_date ON detections(local_date)")
        db.execSQL("CREATE INDEX idx_det_time ON detections(detected_at_ms)")
    }

    /** Per-species confidence threshold overrides, set from the dashboard (API.md
     *  `POST /api/species/{key}/threshold`) — e.g. "this Ruddy Shelduck call was
     *  actually my daughter's voice, raise the bar for this species". An override
     *  REPLACES the global display_threshold entirely for that species (it does not
     *  stack with the region/season penalty below) — once a person has set a precise
     *  number for a species, that number is authoritative over the heuristic. */
    private fun createSpeciesSettings(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE species_settings (
                taxon_key TEXT PRIMARY KEY,
                threshold_override REAL NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
        """.trimIndent())
    }

    override fun onCreate(db: SQLiteDatabase) {
        createDetections(db)
        createSpeciesSettings(db)
        createLifeList(db)
        db.execSQL("""
            CREATE TABLE outbox_delivery (
                detection_id INTEGER NOT NULL,
                publisher TEXT NOT NULL,
                delivered_at_ms INTEGER NOT NULL,
                UNIQUE(detection_id, publisher)
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE detections ADD COLUMN taxon_regional INTEGER NOT NULL DEFAULT 1")
            db.execSQL("ALTER TABLE detections ADD COLUMN taxon_in_season INTEGER NOT NULL DEFAULT 1")
            createSpeciesSettings(db)
        }
        if (oldVersion < 3) {
            // First attempt at purging non_taxon rows, keyed on taxon_group. Superseded by
            // the v4 migration below: rows written before the species table itself was
            // corrected have taxon_group="bird" baked in permanently, so this condition
            // silently missed exactly the rows it was meant to catch. Left in place
            // (harmless no-op today) rather than rewritten, since a device already at v3
            // has already run it and v4 below is what actually cleans things up.
            val c = db.rawQuery("SELECT clip_path FROM detections WHERE taxon_group = 'non_taxon' AND clip_path IS NOT NULL", null)
            c.use { while (it.moveToNext()) runCatching { File(it.getString(0)).delete() } }
            db.execSQL("DELETE FROM detections WHERE taxon_group = 'non_taxon'")
        }
        if (oldVersion < 4) {
            // Match by taxon_key against BirdNET's 11 fixed acoustic-context classes
            // (build_station_species.py's NON_TAXON_LABELS) instead of trusting the stored
            // taxon_group column — a key never changes retroactively, unlike a group tag
            // that was wrong at the time a row was written.
            //
            // PINNING NOTE (added at v5): this and the v3 migration above predate the life
            // list and delete rows unconditionally. They are left as they are because a
            // device that already ran them cannot un-run them, and because no life list
            // existed to protect at the time. EVERY MIGRATION FROM v5 ONWARDS MUST EXCLUDE
            // pinnedDetectionIds() — see that function. This is the single most dangerous
            // habit in this file's history.
            val whereKey = "taxon_group = 'non_taxon' OR taxon_key IN ($NON_TAXON_KEYS_SQL)"
            val c = db.rawQuery("SELECT clip_path FROM detections WHERE ($whereKey) AND clip_path IS NOT NULL", null)
            c.use { while (it.moveToNext()) runCatching { File(it.getString(0)).delete() } }
            db.execSQL("DELETE FROM detections WHERE $whereKey")
        }
        if (oldVersion < 5) createLifeList(db)
        if (oldVersion < 6) {
            // Bout clips (BoutRecorder): a clip now starts before its trigger, so where the
            // detection sits inside it has to be stored rather than assumed to be zero.
            //
            // PURELY ADDITIVE, AND THAT IS THE POINT. Read the v4 note above: every
            // migration from v5 onward must preserve pinnedDetectionIds(), and the surest
            // way to preserve them is to delete nothing at all. This migration touches no
            // rows and no clip files. Existing rows keep clip_start_ms = NULL, which the
            // API reports as "unknown offset" — the honest answer for a 3 s window clip
            // written before any of this existed, rather than a fabricated zero.
            db.execSQL("ALTER TABLE detections ADD COLUMN clip_start_ms INTEGER")
        }
        if (oldVersion < 7) {
            // THE VERDICT SPLITS IN TWO — see the table definition for why.
            //
            // The table is REBUILT into the correct shape rather than having columns bolted
            // onto the old one, because there is no live station to upgrade: this project's
            // only deployment starts from an empty database at v7. Carrying a vestigial
            // `verdict` column forever to smooth a migration nobody will run would be the
            // wrong trade.
            //
            // Existing verdicts are still MIGRATED, not dropped. They are human decisions,
            // and the standing rule in this file is about not destroying those (see the v4
            // note on pinnedDetectionIds) — it holds whether or not any rows exist today.
            // The old single verdict maps conservatively: only "yes" was ever a species
            // identification, and "unsure" never asserted bird-ness, so it does not become
            // one here.
            db.execSQL("ALTER TABLE verifications RENAME TO verifications_v6")
            createVerifications(db)
            db.execSQL("""
                INSERT INTO verifications (detection_id, is_genuine, is_species, verified_at, note)
                SELECT detection_id,
                       CASE verdict WHEN 'yes' THEN 'yes' WHEN 'no' THEN 'no' ELSE 'unsure' END,
                       CASE verdict WHEN 'yes' THEN 'yes' WHEN 'no' THEN 'no' ELSE NULL END,
                       verified_at, note
                FROM verifications_v6
            """.trimIndent())
            db.execSQL("DROP TABLE verifications_v6")
        }
    }

    /**
     * Attach a finished bout clip to every row that triggered it.
     *
     * This is the second half of the deferred write BoutRecorder documents: the rows were
     * inserted with no clip because the audio was still being recorded. One file is shared
     * by every detection in the bout, including detections of different species heard in
     * the same passage — which is correct, they are one piece of audio — and is why the
     * storage paths below count and prune by DISTINCT clip_path rather than by row.
     */
    fun attachClip(ids: List<Long>, path: String, seconds: Double, startedAtMs: Long): Int {
        if (ids.isEmpty()) return 0
        val cv = ContentValues().apply {
            put("clip_path", path)
            put("clip_seconds", seconds)
            put("clip_start_ms", startedAtMs)
        }
        val placeholders = ids.joinToString(",") { "?" }
        return writableDatabase.update("detections", cv, "id IN ($placeholders)",
            ids.map { it.toString() }.toTypedArray())
    }

    fun insert(d: NewDetection): Long {
        val cv = ContentValues().apply {
            put("detected_at_ms", d.detectedAtMs)
            put("local_date", Detection.localDate(d.detectedAtMs))
            put("window_ms", d.windowMs)
            put("taxon_key", d.taxon.key)
            put("taxon_scientific", d.taxon.scientific)
            put("taxon_common", d.taxon.common)
            put("taxon_group", d.taxon.group)
            put("taxon_rank", d.taxon.rank)
            put("taxon_model_index", d.taxon.modelIndex)
            put("taxon_regional", if (d.taxonRegional) 1 else 0)
            put("taxon_in_season", if (d.taxonInSeason) 1 else 0)
            put("detector", d.detector)
            put("detector_version", d.detectorVersion)
            put("score_type", d.scoreType)
            put("confidence", d.confidence)
            put("clip_path", d.clipPath)
            put("clip_seconds", d.clipSeconds)
            put("clip_start_ms", d.clipStartMs)
        }
        return writableDatabase.insert("detections", null, cv)
    }

    // ------------------------------------------------------------------ per-species thresholds

    /** taxon_key -> threshold_override, for a single query up front rather than one
     *  row-per-species lookup during curation (the table is small: a handful of
     *  species a person has actually corrected, not all 6522 classes). */
    fun speciesThresholdOverrides(): Map<String, Float> {
        val map = HashMap<String, Float>()
        val c = readableDatabase.rawQuery("SELECT taxon_key, threshold_override FROM species_settings", null)
        c.use { while (it.moveToNext()) map[it.getString(0)] = it.getFloat(1) }
        return map
    }

    fun getSpeciesThreshold(taxonKey: String): Float? {
        val c = readableDatabase.rawQuery(
            "SELECT threshold_override FROM species_settings WHERE taxon_key = ?", arrayOf(taxonKey))
        return c.use { if (it.moveToFirst()) it.getFloat(0) else null }
    }

    /** threshold == null clears the override, reverting that species to the global
     *  default (plus any region/season penalty). */
    fun setSpeciesThreshold(taxonKey: String, threshold: Float?) {
        if (threshold == null) {
            writableDatabase.delete("species_settings", "taxon_key = ?", arrayOf(taxonKey))
            return
        }
        val cv = ContentValues().apply {
            put("taxon_key", taxonKey)
            put("threshold_override", threshold)
            put("updated_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("species_settings", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    /** The species-level baseline before any per-row region/season penalty — override
     *  if one is set, else the plain global default. This is what the dashboard's
     *  "+5%" button bumps from (API.md `POST /api/species/{key}/threshold`), so
     *  repeated bumps are monotonic and never silently re-add a penalty on top of a
     *  human's explicit choice. */
    fun speciesBaselineThreshold(taxonKey: String, settings: Settings): Float =
        getSpeciesThreshold(taxonKey) ?: settings.displayThreshold

    companion object {
        /** Additive penalty to the confirmation threshold for a species absent from
         *  the Danish checklist. StationService now excludes off-list species at the
         *  write path entirely (a raw-confidence false positive like Compact Weaver in
         *  Copenhagen never reaches this table), so this only still matters for rows
         *  written before that change. Kept rather than deleted so old data still scores
         *  consistently and doesn't need a migration. */
        const val REGIONAL_PENALTY = 0.20f
        /** Additive penalty for a taxon detected outside its plausible GBIF months. */
        const val SEASONAL_PENALTY = 0.15f
        /** Never let penalties push the bar to a literal 1.0 — "raise the threshold",
         *  not "make it impossible" (DESIGN §4's rule against ever silently dropping a
         *  class outright). */
        const val MAX_EFFECTIVE_THRESHOLD = 0.99f

        /** Below this local-occurrence prior a species is treated as implausible here and
         *  jumps the triage queue. Same provisional number the rarity flag uses — a guess
         *  until the distribution of priors at this station can actually be looked at. */
        const val RARITY_PRIOR = 0.02

        /** How close to its threshold a bout has to be to count as a boundary case. These
         *  are the ones that locate the decision boundary, which is why they outrank
         *  routine work — and why PR 4's learned thresholds will want them first. */
        const val BOUNDARY_BAND = 0.10f

        /** Confirmed bouts a species needs before bulk accept is offered at all. Two is
         *  not a statistical bar, it is "you have already listened to this bird here more
         *  than once", which is the condition under which agreeing in bulk is honest. */
        const val BULK_MIN_CONFIRMED = 2

        /** Verdicts needed on EACH side before a species counts as calibrated. Three is not
         *  a statistical bar; it is "there is evidence above and below the line, more than
         *  once". For a species heard twice a week that is still months away, which is
         *  exactly why the UI has to distinguish calibrated species from uncalibrated ones
         *  rather than letting an exemption look like a tuned threshold. */
        const val CALIBRATION_MIN = 3

        /** Fraction of otherwise-exempt bouts still put in front of a human, to catch drift
         *  as noise sources change and the microphone ages. Deterministic per bout (see
         *  auditSampled) so it cannot be re-rolled by refreshing. */
        const val AUDIT_EVERY = 10

        /** BirdNET's 11 acoustic-context classes — build_station_species.py's
         *  NON_TAXON_LABELS, by taxon_key (its slug() function). Every query over
         *  `detections` excludes these by key, not by the stored taxon_group column: a
         *  row's group reflects whatever station_species.json said AT INSERT TIME, and a
         *  row written before that table was corrected has the wrong group baked in
         *  forever. A taxon_key never changes retroactively. */
        val NON_TAXON_KEYS = setOf(
            "dog", "engine", "environmental", "fireworks", "gun",
            "human_non_vocal", "human_vocal", "human_whistle", "noise", "power_tools", "siren"
        )
        val NON_TAXON_KEYS_SQL = NON_TAXON_KEYS.joinToString(",") { "'$it'" }

        /**
         * Penalty scale for local implausibility, applied on a LOG scale because the
         * priors themselves span five orders of magnitude and a linear reading of them is
         * meaningless. Measured at Copenhagen, week 29: Wood Pigeon 0.99, Blackbird 0.72,
         * Magpie 0.53, Tawny Owl 0.038, Spotted Crake 0.0086, Dark-eyed Junco 0.00008.
         * Linear scaling would treat the last three as indistinguishably "about zero";
         * they are in fact a plausible bird, an implausible one, and an impossible one.
         *
         * Anchored so that prior >= PRIOR_FULL costs nothing, and each factor-of-ten below
         * it adds PRIOR_PENALTY_PER_DECADE to the bar.
         */
        const val PRIOR_FULL = 0.05f
        const val PRIOR_PENALTY_PER_DECADE = 0.12f

        /**
         * The threshold this exact row must clear to be "confirmed".
         *
         * An explicit per-species override replaces everything — a human's precise answer
         * always beats the heuristic. Otherwise the LOCAL OCCURRENCE PRIOR raises the bar,
         * having replaced the Danish-checklist boolean that used to do this job. That
         * boolean was set membership against a national vagrant list, so it rated
         * Dark-eyed Junco and Wood Pigeon identically; the prior separates them by four
         * orders of magnitude. A null prior costs nothing — the meta-model may not have
         * run, and unknown must never read as absent (DESIGN §2d).
         *
         * Note this DOWN-WEIGHTS and never drops: the bar is capped below 1.0 so a
         * sufficiently confident detection of an implausible species can still surface,
         * which is how a genuine vagrant gets reported instead of silently deleted.
         */
        fun effectiveThreshold(
            taxonKey: String, regional: Boolean, inSeason: Boolean,
            settings: Settings, overrides: Map<String, Float>, prior: Double? = null,
            learned: Map<String, Float> = emptyMap()
        ): Float {
            overrides[taxonKey]?.let { return it }
            // A threshold LEARNED from this station's own verdicts replaces the global
            // default outright, exactly as a manual override does, and for the same reason:
            // once there is a measured number for this species here, it is authoritative
            // over a heuristic built from a checklist and a national occurrence prior.
            // Ranked below a manual override — a human's explicit answer still wins.
            learned[taxonKey]?.let { return it.coerceIn(0f, MAX_EFFECTIVE_THRESHOLD) }
            var t = settings.displayThreshold
            if (settings.regionSeasonFilterEnabled) {
                if (prior != null) {
                    if (prior < PRIOR_FULL) {
                        val decades = kotlin.math.log10(PRIOR_FULL / prior.coerceAtLeast(1e-6)).toFloat()
                        t += decades * PRIOR_PENALTY_PER_DECADE
                    }
                } else {
                    // No prior available: fall back to the old booleans so a station whose
                    // meta-model was never pushed still gets the weaker filter it had before.
                    if (!regional) t += REGIONAL_PENALTY
                    if (!inSeason) t += SEASONAL_PENALTY
                }
            }
            return t.coerceIn(0f, MAX_EFFECTIVE_THRESHOLD)
        }
    }

    fun markDelivered(detectionId: Long, publisher: String) {
        val cv = ContentValues().apply {
            put("detection_id", detectionId); put("publisher", publisher)
            put("delivered_at_ms", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("outbox_delivery", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
    }

    fun pendingForPublisher(publisher: String, limit: Int = 200): List<DetectionRow> {
        val db = readableDatabase
        val c = db.rawQuery("""
            SELECT d.* FROM detections d
            LEFT JOIN outbox_delivery o ON o.detection_id = d.id AND o.publisher = ?
            WHERE o.detection_id IS NULL AND d.taxon_group != 'non_taxon' AND d.taxon_key NOT IN ($NON_TAXON_KEYS_SQL)
            ORDER BY d.detected_at_ms ASC LIMIT ?
        """.trimIndent(), arrayOf(publisher, limit.toString()))
        val out = c.use { readRows(it) }
        // repeat_count/state are irrelevant to a publisher — 0/"candidate" placeholders.
        return out
    }

    private fun rowFrom(c: android.database.Cursor): NewDetection {
        fun s(n: String) = c.getString(c.getColumnIndexOrThrow(n))
        fun i(n: String) = c.getInt(c.getColumnIndexOrThrow(n))
        fun l(n: String) = c.getLong(c.getColumnIndexOrThrow(n))
        fun f(n: String) = c.getFloat(c.getColumnIndexOrThrow(n))
        val taxon = Taxon(s("taxon_key"), s("taxon_scientific"), s("taxon_common"),
            s("taxon_group"), s("taxon_rank"), i("taxon_model_index"))
        val startIdx = c.getColumnIndex("clip_start_ms")
        return NewDetection(l("detected_at_ms"), i("window_ms"), taxon, s("detector"),
            s("detector_version"), s("score_type"), f("confidence"),
            c.getString(c.getColumnIndexOrThrow("clip_path")), c.getDouble(c.getColumnIndexOrThrow("clip_seconds")),
            clipStartMs = if (startIdx >= 0 && !c.isNull(startIdx)) c.getLong(startIdx) else null,
            taxonRegional = i("taxon_regional") != 0, taxonInSeason = i("taxon_in_season") != 0)
    }

    private fun readRows(c: android.database.Cursor): List<DetectionRow> {
        val out = ArrayList<DetectionRow>()
        while (c.moveToNext()) {
            val id = c.getLong(c.getColumnIndexOrThrow("id"))
            val d = rowFrom(c)
            out += DetectionRow(id, d.detectedAtMs, d.windowMs, d.taxon, d.detector, d.detectorVersion,
                d.scoreType, d.confidence, d.clipPath, d.clipSeconds, repeatCount = 0, state = "candidate",
                clipStartMs = d.clipStartMs,
                taxonRegional = d.taxonRegional, taxonInSeason = d.taxonInSeason)
        }
        return out
    }

    /** repeat_count = how many times this taxon was seen in the trailing
     *  `repeatWindowMin` minutes up to and including this row — computed per row with a
     *  correlated subquery on the (taxon_key, detected_at_ms) index. `state` is decided
     *  against [effectiveThreshold], not the bare global default — a species override or
     *  a region/season penalty can raise the bar this specific row has to clear. */
    private fun withCuration(rows: List<DetectionRow>, settings: Settings): List<DetectionRow> {
        if (rows.isEmpty()) return rows
        val db = readableDatabase
        val windowMs = settings.repeatWindowMin * 60_000L
        val gapMs = settings.boutGapSeconds * 1000L
        val overrides = speciesThresholdOverrides()
        val learned = learnedThresholds()
        val priorCache = HashMap<Int, Map<String, Double>>()
        return rows.map { r ->
            // Timestamps rather than COUNT(*): the count is what was wrong. Bouts have to be
            // derived from the SPACING of detections, which a COUNT cannot see.
            val c = db.rawQuery(
                "SELECT detected_at_ms FROM detections WHERE taxon_key = ? AND detected_at_ms <= ? " +
                "AND detected_at_ms > ? ORDER BY detected_at_ms ASC",
                arrayOf(r.taxon.key, r.detectedAtMs.toString(), (r.detectedAtMs - windowMs).toString()))
            val times = ArrayList<Long>()
            c.use { while (it.moveToNext()) times.add(it.getLong(0)) }
            val bouts = countBouts(times, gapMs)
            // Prior is looked up for the week the detection HAPPENED in, not the week the
            // dashboard is being read in — the same rule taxonInSeason follows, and for the
            // same reason: re-judging a March record against August's expectations is a lie
            // about what was plausible at the time.
            val prior = priorCache.getOrPut(weekOf(r.detectedAtMs)) { priorsForWeek(weekOf(r.detectedAtMs)) }[r.taxon.scientific]
            val threshold = effectiveThreshold(r.taxon.key, r.taxonRegional, r.taxonInSeason,
                settings, overrides, prior, learned)
            val confirmed = r.confidence >= threshold && bouts >= settings.repeatRequired
            // repeat_count now reports BOUTS, not raw rows. The API field keeps its name
            // because its MEANING is unchanged - "how many times did I hear this" - and it
            // is the old implementation that failed to answer that question.
            r.copy(repeatCount = bouts, state = if (confirmed) "confirmed" else "candidate")
        }
    }

    /**
     * How many separate bouts these detection times represent — a bout being a run of
     * detections with no gap longer than [gapMs] inside it.
     *
     * The whole correction lives here. Nineteen consecutive windows of one continuous
     * sound are ONE bout and therefore one piece of evidence, however many rows they
     * produced; two calls a minute apart are two, even though they are only two rows.
     *
     * [gapMs] of 0 restores the old count-every-row behaviour, which is what the stored
     * history was curated under.
     */
    private fun countBouts(times: List<Long>, gapMs: Long): Int {
        if (times.isEmpty()) return 0
        if (gapMs <= 0L) return times.size
        var bouts = 1
        for (i in 1 until times.size) if (times[i] - times[i - 1] > gapMs) bouts++
        return bouts
    }

    // ------------------------------------------------------------------ bouts

    /**
     * How long after a bout's last detection its clips may still be being written.
     *
     * Tied to BoutRecorder: a clip closes at most MAX_CLIP_S after it opens and POST_ROLL_S
     * after its last trigger, then has to be renamed and attached. Inside this window a
     * missing clip means "not finished", outside it means "not there" — and those are the
     * two answers §2d exists to keep apart.
     */
    private val PENDING_GRACE_MS =
        ((BoutRecorder.MAX_CLIP_S + BoutRecorder.POST_ROLL_S) * 1000).toLong() + 15_000L

    /**
     * Group already-read rows of ONE taxon into bouts, newest bout first.
     *
     * The gap rule is [countBouts]'s, deliberately — one definition of "the same event",
     * used by the count, the day list and the verification flow alike.
     */
    private fun boutsOf(rows: List<DetectionRow>, settings: Settings,
                        overrides: Map<String, Float>, prior: Double?, nowMs: Long,
                        learned: Map<String, Float> = emptyMap()): List<Bout> {
        if (rows.isEmpty()) return emptyList()
        val gapMs = settings.boutGapSeconds * 1000L
        val sorted = rows.sortedBy { it.detectedAtMs }
        val groups = ArrayList<MutableList<DetectionRow>>()
        for (r in sorted) {
            val last = groups.lastOrNull()?.lastOrNull()
            if (last == null || (gapMs > 0 && r.detectedAtMs - last.detectedAtMs > gapMs)) {
                groups += mutableListOf(r)
            } else groups.last() += r
        }
        return groups.map { g ->
            val first = g.first()
            val threshold = effectiveThreshold(
                first.taxon.key, first.taxonRegional, first.taxonInSeason, settings, overrides,
                prior, learned)
            val peak = g.maxOf { it.confidence }

            // One entry per distinct FILE, in playback order. Several rows share a clip, and
            // several clips can belong to one bout; the ordering falls back to the detection
            // time for rows written before clip_start_ms existed.
            val byPath = LinkedHashMap<String, BoutClip>()
            for (r in g.sortedBy { it.clipStartMs ?: it.detectedAtMs }) {
                val p = r.clipPath ?: continue
                if (p !in byPath) byPath[p] = BoutClip(r.id, r.clipStartMs, r.clipSeconds, p)
            }
            val clips = byPath.values.toList()

            // Which detections were ever MEANT to have audio. clipFloor is a write-time
            // decision and this re-reads it at query time, so moving the slider can change
            // the denominator for old rows; that is the same asymmetry retentionFloor has,
            // and the alternative is storing per-row intent nobody would ever read.
            val expected = g.count { it.confidence >= settings.clipFloor }
            val covered = g.count { it.clipPath != null }
            val recentEnoughToBePending = nowMs - g.maxOf { it.detectedAtMs } < PENDING_GRACE_MS
            val state = when {
                covered > 0 && covered >= expected -> "recorded"
                covered > 0 -> "partial"
                expected > 0 && recentEnoughToBePending -> "pending"
                expected > 0 -> "unavailable"
                else -> "none"
            }
            val ids = g.map { it.id }
            val verdicts = verdictsFor(ids)
            val judged = ids.count { it in verdicts }
            val distinct = verdicts.values.toSet()
            Bout(
                boutId = first.id, taxon = first.taxon,
                startMs = first.detectedAtMs, endMs = g.last().detectedAtMs,
                detectionCount = g.size, detectionIds = ids,
                peakConfidence = peak, threshold = threshold, aboveThreshold = peak >= threshold,
                clips = clips,
                audio = BoutAudio(state, clips.sumOf { it.seconds }, covered, expected),
                verifiedDetections = judged,
                verifyState = when { judged == 0 -> "none"; judged < ids.size -> "partial"; else -> "done" },
                verdictIsGenuine = distinct.singleOrNull()?.first,
                verdictIsSpecies = distinct.singleOrNull()?.second
            )
        }.sortedByDescending { it.startMs }
    }

    /** Every bout of one taxon on one local date, newest first. */
    fun boutsForDay(date: String, taxonKey: String, settings: Settings): List<Bout> {
        val rows = rowsForDay(date, settings).filter { it.taxon.key == taxonKey }
        if (rows.isEmpty()) return emptyList()
        val prior = priorsForWeek(weekOf(rows.first().detectedAtMs))[rows.first().taxon.scientific]
        return boutsOf(rows, settings, speciesThresholdOverrides(), prior,
            System.currentTimeMillis(), learnedThresholds())
    }

    /** Raw rows for a local date, retention floor and non-taxon exclusions applied. */
    private fun rowsForDay(date: String, settings: Settings): List<DetectionRow> {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM detections WHERE local_date = ? AND confidence >= ? " +
            "AND taxon_group != 'non_taxon' AND taxon_key NOT IN ($NON_TAXON_KEYS_SQL) " +
            "ORDER BY detected_at_ms ASC",
            arrayOf(date, settings.retentionFloor.toString()))
        return c.use { readRows(it) }
    }

    /**
     * The day list, rolled up by species — one row per species per day, which is also how
     * birders keep a day list, and a great deal more useful than five hundred magpie rows.
     *
     * The headline number is BOUTS, not detections. A detection count mostly measures how
     * long something sang near the microphone: one magpie on the railing for ten minutes
     * outscores five magpies passing through. Neither is a bird count and neither is
     * presented as one.
     */
    fun daySpecies(date: String, settings: Settings, lat: Double, lon: Double): List<DaySpecies> {
        val rows = rowsForDay(date, settings)
        if (rows.isEmpty()) return emptyList()
        val overrides = speciesThresholdOverrides()
        val learned = learnedThresholds()
        val rejected = rejectedSpecies()
        val confirmedSp = lifeList().filter { it.status == "confirmed" }.map { it.scientific }.toHashSet()
        val solar = Solar.forDate(date, lat, lon)
        val now = System.currentTimeMillis()
        val priorCache = HashMap<Int, Map<String, Double>>()

        return rows.groupBy { it.taxon.key }.values.map { g ->
            val taxon = g.first().taxon
            val prior = priorCache.getOrPut(weekOf(g.first().detectedAtMs)) {
                priorsForWeek(weekOf(g.first().detectedAtMs))
            }[taxon.scientific]
            val bouts = boutsOf(g, settings, overrides, prior, now, learned)
            val activity = IntArray(6)
            for (b in bouts) activity[Solar.binOf(solar, b.startMs)]++
            // "Best" means the loudest evidence that can actually be listened to. A bout
            // with a higher score and no audio is not a better thing to offer someone whose
            // next action is to press play.
            val best = bouts.filter { it.clips.isNotEmpty() }.maxByOrNull { it.peakConfidence }
            DaySpecies(
                taxon = taxon,
                bouts = bouts.size,
                boutsAboveThreshold = bouts.count { it.aboveThreshold },
                detections = g.size,
                firstMs = g.minOf { it.detectedAtMs },
                lastMs = g.maxOf { it.detectedAtMs },
                peakConfidence = g.maxOf { it.confidence },
                threshold = bouts.firstOrNull()?.threshold ?: settings.displayThreshold,
                bestBoutId = best?.boutId,
                bestClip = best?.clips?.firstOrNull(),
                activity = activity,
                status = when (taxon.scientific) {
                    in rejected -> "rejected"
                    in confirmedSp -> "confirmed"
                    else -> "candidate"
                }
            )
        }.sortedWith(compareByDescending<DaySpecies> { it.boutsAboveThreshold }.thenByDescending { it.bouts })
    }

    // ------------------------------------------------------------------ verification triage

    /** One species on the triage list: what it would cost, and what is at stake. */
    data class TriageSpecies(
        val taxon: Taxon, val tier: String,
        val boutsTotal: Int, val boutsDone: Int, val boutsPending: Int,
        /** Undecided bouts the station is NOT asking about, because this species has a
         *  learned threshold and they cleared it. Reported rather than silently dropped:
         *  an exemption a person cannot see is indistinguishable from a bug. */
        val boutsExempt: Int,
        val calibration: Calibration?,
        val peakConfidence: Float, val threshold: Float,
        val prior: Double?, val wouldBeLifer: Boolean,
        val stake: String, val stakeReason: String,
        val activity: IntArray, val bulkAllowed: Boolean,
        val lastMs: Long
    )

    /** Every bout of one species across all days, newest first. */
    fun boutsForSpecies(taxonKey: String, settings: Settings, limit: Int = 300,
                        learned: Map<String, Float>? = null): List<Bout> {
        val c = readableDatabase.rawQuery(
            "SELECT * FROM detections WHERE taxon_key = ? AND confidence >= ? " +
            "AND taxon_group != 'non_taxon' AND taxon_key NOT IN ($NON_TAXON_KEYS_SQL) " +
            "ORDER BY detected_at_ms DESC LIMIT ?",
            arrayOf(taxonKey, settings.retentionFloor.toString(), (limit * 20).toString()))
        val rows = c.use { readRows(it) }
        if (rows.isEmpty()) return emptyList()
        val prior = priorsForWeek(weekOf(rows.first().detectedAtMs))[rows.first().taxon.scientific]
        // The caller passes `learned` when it is iterating species, so the calibration pass
        // runs once for the whole triage list rather than once per species.
        return boutsOf(rows, settings, speciesThresholdOverrides(), prior,
            System.currentTimeMillis(), learned ?: learnedThresholds()).take(limit)
    }

    /**
     * The triage list: species with bouts still undecided, ordered by what is at stake.
     *
     * ORDERED BY STAKES, NOT CHRONOLOGY, and grouped by species. A wrong 38th magpie costs
     * nothing; a wrong new species permanently corrupts the life list. Doing one species at
     * a time is also one mental context — jumping between species forces re-orientation on
     * every item, which is what made the old one-at-a-time queue exhausting.
     */
    fun triageSpecies(settings: Settings, lat: Double, lon: Double, limit: Int = 60): List<TriageSpecies> {
        val overrides = speciesThresholdOverrides()
        // One calibration pass for the whole list; boutsForSpecies is handed the result so
        // it does not redo it per species.
        val cals = calibrations()
        val learned = learnedThresholds(cals)
        val statuses = lifeList().associateBy { it.scientific }
        val solarCache = HashMap<String, Solar.Day>()
        val priorCache = HashMap<Int, Map<String, Double>>()
        val now = System.currentTimeMillis()

        // Distinct taxa above the retention floor, most recent first — the candidate pool.
        val keys = ArrayList<String>()
        readableDatabase.rawQuery(
            "SELECT taxon_key, MAX(detected_at_ms) AS last_ms FROM detections " +
            "WHERE confidence >= ? AND taxon_group != 'non_taxon' AND taxon_key NOT IN ($NON_TAXON_KEYS_SQL) " +
            "GROUP BY taxon_key ORDER BY last_ms DESC LIMIT ?",
            arrayOf(settings.retentionFloor.toString(), (limit * 3).toString())
        ).use { while (it.moveToNext()) keys.add(it.getString(0)) }

        val out = ArrayList<TriageSpecies>()
        for (key in keys) {
            val bouts = boutsForSpecies(key, settings, learned = learned)
            if (bouts.isEmpty()) continue
            val taxon = bouts.first().taxon
            val status = statuses[taxon.scientific]
            val tier = lifeTier(status?.status)
            if (tier == "rejected") continue      // already refused; not asking again
            val prior = priorCache.getOrPut(weekOf(bouts.first().startMs)) {
                priorsForWeek(weekOf(bouts.first().startMs))
            }[taxon.scientific]
            val peak = bouts.maxOf { it.peakConfidence }
            val threshold = bouts.first().threshold
            val wouldBeLifer = status?.firstConfirmedAt == null

            // EXEMPTION. A bout above a learned threshold for a plausible, already-listed
            // species does not need a human — except for the audit sample. Anything still
            // required counts as pending, so the number on the row is the work remaining
            // rather than the number of undecided rows in the table.
            val pending = bouts.count { verifyNeed(it, learned[key], prior, wouldBeLifer, settings).required }
            val done = bouts.count { it.verifyState == "done" }
            val exempt = bouts.size - done - pending
            if (pending == 0) continue

            // The order the spec calls for, in the order it calls for it.
            // Implausible here and now: a local-occurrence prior far below what the
            // station normally hears, or a species off the Danish checklist entirely.
            val implausible = (prior != null && prior < RARITY_PRIOR) || !taxon.regional
            val boundary = bouts.any { kotlin.math.abs(it.peakConfidence - threshold) <= BOUNDARY_BAND }
            val (stake, reason) = when {
                wouldBeLifer -> "lifer" to "would be new on the life list"
                implausible -> "implausible" to (if (prior != null) "rare here this week" else "off the local checklist")
                boundary -> "boundary" to "sits near the threshold"
                else -> "routine" to "already on the life list"
            }

            val activity = IntArray(6)
            for (b in bouts) {
                val date = Detection.localDate(b.startMs)
                val day = solarCache.getOrPut(date) { Solar.forDate(date, lat, lon) }
                activity[Solar.binOf(day, b.startMs)]++
            }
            out += TriageSpecies(
                taxon = taxon, tier = tier,
                boutsTotal = bouts.size, boutsDone = done,
                boutsPending = pending, boutsExempt = exempt,
                calibration = cals[key],
                peakConfidence = peak, threshold = threshold,
                prior = prior, wouldBeLifer = wouldBeLifer,
                stake = stake, stakeReason = reason,
                activity = activity,
                // BULK ACCEPT IS NEVER OFFERED FOR A SPECIES NOT YET ON THE LIST. Deciding a
                // first record in a swipe is exactly the judgement that must not be made
                // that way; once a species is established, agreeing that six more bouts are
                // the same bird is ordinary work.
                bulkAllowed = status?.firstConfirmedAt != null && (bouts.size - pending) >= BULK_MIN_CONFIRMED,
                lastMs = bouts.maxOf { it.endMs }
            )
            if (out.size >= limit) break
        }
        val rank = mapOf("lifer" to 0, "implausible" to 1, "boundary" to 2, "routine" to 3)
        return out.sortedWith(
            compareBy<TriageSpecies> { rank[it.stake] ?: 9 }.thenByDescending { it.peakConfidence })
    }

    /**
     * A consistent byte-for-byte copy of station.db, for `GET /api/data/export`.
     *
     * WHY THIS EXISTS AT ALL: the API has had a DELETE that wipes everything since the
     * beginning and no way to get a copy off. The phone cannot be reached from a laptop —
     * it is behind NAT and there is no adb — so the only route out is a download the
     * dashboard can start. Nothing is at risk today because the database is empty; the
     * point is that it should exist *before* something is.
     *
     * CONSISTENCY, not just a file copy. SQLiteOpenHelper runs in WAL mode, so the bytes
     * on disk are only half the story and copying `station.db` alone can produce a
     * database missing its most recent writes — which would look like a successful export
     * and be a truncated one (§2d). So: checkpoint the WAL into the main file, and hold a
     * transaction across the copy so nothing in this process can write mid-read.
     */
    fun exportTo(dest: File): Long {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            File(db.path).inputStream().use { ins -> dest.outputStream().use { ins.copyTo(it) } }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return dest.length()
    }

    /**
     * What verification has taught us about one species at this site.
     *
     * [threshold] is null until there is evidence on BOTH sides. A boundary needs examples
     * above and below it; a species with ten confirmations and no rejections tells you
     * nothing about where the line is, only that the line is somewhere below the lowest
     * confirmation.
     */
    data class Calibration(
        val taxonKey: String, val scientific: String,
        val positives: Int, val negatives: Int,
        val threshold: Float?, val calibrated: Boolean,
        val lowestConfirmed: Float?, val highestRejected: Float?
    )

    /**
     * Learn a per-species threshold from verdicts.
     *
     * NO GLOBAL "TRUST ABOVE X", and this is why: the committed known-negative corpus
     * peaks at 0.98 and is entirely false positives. BirdNET scores are not calibrated
     * probabilities and vary by species, site and noise, so a number that means "certain"
     * for a Blackbird on this balcony can be routine noise for a Shelduck. The only
     * defensible threshold is one measured per species, here.
     *
     * REJECTIONS CARRY MORE INFORMATION THAN CONFIRMATIONS. A confirmation says "the line
     * is somewhere below this"; a rejection says "the line is somewhere above this", and
     * it is rejections that pin the boundary from underneath. So the rule below prefers
     * the most conservative line that admits no known false positive, and only falls back
     * to an error-minimising split when the two classes overlap.
     */
    /**
     * Every species' calibration, from ONE query.
     *
     * Per-species queries were the obvious shape and the wrong one: this is read on every
     * detections request, and the triage list asks about every species in turn, so a query
     * per species is O(N) per read and O(N²) across the list — on a phone already spending
     * ~190 ms of every 1.5 s hop inside BirdNET. The whole verdict set is small (it is
     * bounded by how much a human has listened to), so it is read once and grouped in
     * memory.
     */
    fun calibrations(): Map<String, Calibration> {
        val pos = HashMap<String, MutableList<Float>>()
        val neg = HashMap<String, MutableList<Float>>()
        val names = HashMap<String, String>()
        readableDatabase.rawQuery(
            "SELECT d.taxon_key, d.taxon_scientific, d.confidence, v.is_genuine, v.is_species " +
            "FROM verifications v JOIN detections d ON d.id = v.detection_id", null
        ).use {
            while (it.moveToNext()) {
                val key = it.getString(0)
                names[key] = it.getString(1)
                val conf = it.getFloat(2)
                val genuine = it.getString(3)
                val species = if (it.isNull(4)) null else it.getString(4)
                when {
                    // Only a species identification is a positive example OF THIS SPECIES.
                    genuine == "yes" && species == "yes" -> pos.getOrPut(key) { ArrayList() } += conf
                    // Not an animal, or a real animal that is not this species: either way
                    // this score was wrong for this label.
                    genuine == "no" || species == "no" -> neg.getOrPut(key) { ArrayList() } += conf
                    // "Something real, but I don't know which" teaches nothing about the
                    // species boundary and is counted on neither side.
                }
            }
        }
        val out = HashMap<String, Calibration>()
        for (key in names.keys) {
            val p = pos[key] ?: emptyList<Float>()
            val n = neg[key] ?: emptyList<Float>()
            if (p.isEmpty() && n.isEmpty()) continue
            val calibrated = p.size >= CALIBRATION_MIN && n.size >= CALIBRATION_MIN
            out[key] = Calibration(key, names[key] ?: "", p.size, n.size,
                if (calibrated) learnThreshold(p, n) else null, calibrated,
                p.minOrNull(), n.maxOrNull())
        }
        return out
    }

    fun calibrationFor(taxonKey: String): Calibration? = calibrations()[taxonKey]

    /** The boundary itself. Separated out so the rule is readable and testable. */
    private fun learnThreshold(pos: List<Float>, neg: List<Float>): Float {
        val highestRejected = neg.max()
        val lowestConfirmed = pos.min()
        // Clean separation: put the line just above the worst false positive, which admits
        // none of them and keeps every confirmation.
        if (highestRejected < lowestConfirmed) {
            return ((highestRejected + lowestConfirmed) / 2f).coerceIn(0f, MAX_EFFECTIVE_THRESHOLD)
        }
        // Overlapping. Scan every candidate cut and take the one with fewest mistakes,
        // breaking ties UPWARD — a false accept corrupts the record, a false reject only
        // costs another listen.
        val cuts = (pos + neg).distinct().sorted()
        var best = lowestConfirmed
        var bestErrors = Int.MAX_VALUE
        for (c in cuts) {
            val errors = neg.count { it >= c } + pos.count { it < c }
            if (errors <= bestErrors) { bestErrors = errors; best = c }
        }
        return best.coerceIn(0f, MAX_EFFECTIVE_THRESHOLD)
    }

    /** taxon_key -> learned threshold, for the species that have one. */
    fun learnedThresholds(cals: Map<String, Calibration> = calibrations()): Map<String, Float> =
        cals.values.mapNotNull { c -> c.threshold?.let { c.taxonKey to it } }.toMap()

    fun allCalibrations(): List<Calibration> =
        calibrations().values.sortedWith(
            compareByDescending<Calibration> { it.calibrated }.thenByDescending { it.positives + it.negatives })

    /**
     * Whether a bout still has to be looked at by a human, and why.
     *
     * EXEMPTION REQUIRES BOTH A LEARNED THRESHOLD AND PLAUSIBILITY. Either alone is not
     * enough: a calibrated threshold says "this score is normally right for this species
     * here", and plausibility says "this species being here at all is normal". A confident
     * score for an implausible species is precisely the case worth a human's attention.
     *
     * Four things are ALWAYS verified regardless of score:
     *  - anything that would be a new species on the life list. A wrong 38th magpie costs
     *    nothing; a wrong new species permanently corrupts the list.
     *  - anything implausible for here and now.
     *  - anything from a species with no learned threshold — an unverified species cannot
     *    exempt itself.
     *  - a small ongoing random sample of otherwise-exempt bouts, so drift in the noise
     *    environment or the microphone shows up instead of hiding behind an old boundary.
     */
    data class VerifyNeed(val required: Boolean, val reason: String)

    fun verifyNeed(bout: Bout, learned: Float?, prior: Double?, wouldBeLifer: Boolean,
                   settings: Settings): VerifyNeed {
        if (bout.verifyState == "done") return VerifyNeed(false, "already decided")
        if (wouldBeLifer) return VerifyNeed(true, "would be new on the life list")
        val implausible = (prior != null && prior < RARITY_PRIOR) || !bout.taxon.regional
        if (implausible) return VerifyNeed(true, "implausible here and now")
        if (learned == null) return VerifyNeed(true, "no learned threshold for this species yet")
        if (bout.peakConfidence < learned) return VerifyNeed(true, "below the learned threshold")
        if (auditSampled(bout.boutId)) return VerifyNeed(true, "random audit sample")
        return VerifyNeed(false, "above the learned threshold for a plausible species")
    }

    /** Deterministic on the bout's first detection id, so the same bout is always either in
     *  the sample or not — a random draw per request would re-roll on every refresh and
     *  make the sample meaningless. */
    fun auditSampled(boutId: Long): Boolean {
        var h = boutId * -0x61c8864680b583ebL
        h = h xor (h ushr 31)
        return (h % AUDIT_EVERY + AUDIT_EVERY) % AUDIT_EVERY == 0L
    }

    /** The taxon behind a key, from the most recent row that carries it. Reference audio
     *  needs the scientific name, and the species list asset is not addressable by key
     *  from here. Null when nothing has ever been detected under that key. */
    fun taxonForKey(taxonKey: String): Taxon? =
        readableDatabase.rawQuery(
            "SELECT taxon_key, taxon_scientific, taxon_common, taxon_group, taxon_rank, " +
            "taxon_model_index, taxon_regional FROM detections WHERE taxon_key = ? " +
            "ORDER BY detected_at_ms DESC LIMIT 1", arrayOf(taxonKey)
        ).use {
            if (!it.moveToFirst()) null
            else Taxon(it.getString(0), it.getString(1), it.getString(2), it.getString(3),
                it.getString(4), it.getInt(5), regional = it.getInt(6) != 0)
        }

    /** species_status.status -> the life-list tier the API reports. */
    fun lifeTier(status: String?): String = when (status) {
        "confirmed" -> "species"     // a human named it: the only tier that is a life tick
        "bird" -> "bird"             // a human confirmed a bird, not which one
        "rejected" -> "rejected"
        else -> "machine"            // proposed by the model, never looked at
    }

    /** Solar bin edges for a date, so the API can label the strip it just sent. */
    fun solarDay(date: String, lat: Double, lon: Double): Solar.Day = Solar.forDate(date, lat, lon)

    /** The general detections query behind GET /api/detections and the SSE catch-up. */
    fun listDetections(
        sinceMs: Long?, date: String?, group: String?, state: String, minConf: Float?,
        limit: Int, settings: Settings
    ): List<DetectionRow> {
        // Belt-and-suspenders (DESIGN.md): non_taxon classes like "Human vocal" are
        // rejected at write time in StationService, but this excludes them again here so
        // a row from before that check existed — or from any other write path — can
        // never surface on the dashboard either.
        val where = StringBuilder("confidence >= ? AND taxon_group != 'non_taxon' AND taxon_key NOT IN ($NON_TAXON_KEYS_SQL)")
        val args = arrayListOf(settings.retentionFloor.toString())
        sinceMs?.let { where.append(" AND detected_at_ms > ?"); args += it.toString() }
        date?.let { where.append(" AND local_date = ?"); args += it }
        group?.let { where.append(" AND taxon_group = ?"); args += it }
        // Over-fetch: state/min_conf filtering needs repeat_count, computed after the SQL read.
        val fetchLimit = (limit * 6).coerceAtLeast(200).coerceAtMost(5000)
        val c = readableDatabase.rawQuery(
            "SELECT * FROM detections WHERE $where ORDER BY detected_at_ms DESC LIMIT ?",
            (args + fetchLimit.toString()).toTypedArray())
        val rows = withCuration(c.use { readRows(it) }, settings)
        val filtered = rows.filter { r ->
            val confirmed = r.state == "confirmed"
            val passesState = when (state) {
                "confirmed" -> confirmed
                "candidate" -> !confirmed
                else -> true
            }
            val passesConf = minConf?.let { r.confidence >= it } ?: true
            passesState && passesConf
        }
        return filtered.take(limit)
    }

    fun byId(id: Long): DetectionRow? {
        val c = readableDatabase.rawQuery("SELECT * FROM detections WHERE id = ?", arrayOf(id.toString()))
        val row = c.use { readRows(it) }.firstOrNull() ?: return null
        // state/repeat_count don't matter for clip lookup; caller only needs clip_path.
        return row
    }

    private data class DaySummaryRow(val taxonKey: String, val confidence: Float, val regional: Boolean,
                                     val inSeason: Boolean, val atMs: Long)

    /**
     * Per-day counts, under the SAME rule the species and detection views use.
     *
     * This used to skip the repeat check and call itself a cheap approximation. It was not
     * cheap enough to be worth the lie: on a freshly cleared station a single 0.675 row
     * with no repeat made `/api/days` report "1 detection, 1 species" while `/api/species`
     * reported 0 confirmed, from the same table, in the same second. Two endpoints
     * describing one day and disagreeing is worse than a slower rollup — §2d, a partial
     * answer presented as a whole one.
     *
     * The bout grouping is done per (date, taxon) in memory over rows already read, so the
     * cost is a sort per species per day rather than a query per row.
     */
    fun daySummaries(settings: Settings): List<DaySummary> {
        val c = readableDatabase.rawQuery(
            "SELECT local_date, taxon_key, confidence, taxon_regional, taxon_in_season, detected_at_ms " +
            "FROM detections WHERE confidence >= ? AND taxon_group != 'non_taxon' " +
            "AND taxon_key NOT IN ($NON_TAXON_KEYS_SQL)",
            arrayOf(settings.retentionFloor.toString()))
        val byDate = HashMap<String, MutableList<DaySummaryRow>>()
        c.use {
            while (it.moveToNext()) {
                val date = it.getString(0)
                byDate.getOrPut(date) { ArrayList() } +=
                    DaySummaryRow(it.getString(1), it.getFloat(2), it.getInt(3) != 0,
                        it.getInt(4) != 0, it.getLong(5))
            }
        }
        val overrides = speciesThresholdOverrides()
        val learned = learnedThresholds()
        val gapMs = settings.boutGapSeconds * 1000L
        // A rejected species stops counting toward the day's totals for the same reason it
        // leaves the species list: the observer already answered the question these numbers
        // are asking.
        val rejectedKeys = HashSet<String>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT DISTINCT d.taxon_key FROM detections d JOIN species_status s " +
                "ON s.scientific_name = d.taxon_scientific WHERE s.status = 'rejected'", null
            ).use { while (it.moveToNext()) rejectedKeys.add(it.getString(0)) }
        }
        return byDate.map { (date, rows) ->
            val confirmed = rows.filterNot { it.taxonKey in rejectedKeys }
                .groupBy { it.taxonKey }.values.flatMap { forTaxon ->
                // A species qualifies for the day only if its detections form at least
                // repeatRequired bouts; then every above-threshold row of that species counts.
                val bouts = countBouts(forTaxon.map { it.atMs }.sorted(), gapMs)
                if (bouts < settings.repeatRequired) emptyList()
                else forTaxon.filter {
                    // Learned thresholds are passed here too. This function exists because
                    // /api/days and /api/species once disagreed about the same day, and
                    // curating one of them under a different bar would recreate exactly
                    // that — two endpoints describing one day and contradicting each other.
                    it.confidence >= effectiveThreshold(it.taxonKey, it.regional, it.inSeason,
                        settings, overrides, learned = learned)
                }
            }
            DaySummary(date, confirmed.size, confirmed.map { it.taxonKey }.distinct().size)
        }.sortedByDescending { it.date }
    }

    fun summaryForDate(date: String, settings: Settings): DaySummaryData {
        val rejected = rejectedSpecies()
        val rows = listDetections(sinceMs = null, date = date, group = null, state = "confirmed",
            minConf = null, limit = 100_000, settings = settings)
            .filter { it.taxon.scientific !in rejected }
        val byHour = IntArray(24)
        val cal = java.util.Calendar.getInstance()
        for (r in rows) { cal.timeInMillis = r.detectedAtMs; byHour[cal.get(java.util.Calendar.HOUR_OF_DAY)]++ }
        val byTaxon = rows.groupBy { it.taxon.key }
        val top = byTaxon.entries.sortedByDescending { it.value.size }.take(8).map { (_, v) ->
            SpeciesAgg(v[0].taxon, v.size, v.maxOf { it.detectedAtMs }, v.maxOf { it.confidence })
        }
        val mostActive = top.firstOrNull()?.let { it.taxon to it.count }
        return DaySummaryData(date, byTaxon.size, rows.size,
            rows.minOfOrNull { it.detectedAtMs }, rows.maxOfOrNull { it.detectedAtMs }, mostActive, byHour, top)
    }

    fun speciesSummary(settings: Settings): List<SpeciesAgg> {
        val rows = listDetections(sinceMs = null, date = null, group = null, state = "confirmed",
            minConf = null, limit = 200_000, settings = settings)
        val rejected = rejectedSpecies()
        return rows.filter { it.taxon.scientific !in rejected }
            .groupBy { it.taxon.key }.values.map { v ->
                SpeciesAgg(v[0].taxon, v.size, v.maxOf { it.detectedAtMs }, v.maxOf { it.confidence })
            }.sortedByDescending { it.count }
    }

    // DISTINCT, everywhere, since bout clips arrived. One clip file is shared by every
    // detection in the bout — a singing bird produces a dozen rows against one file, and
    // a mixed passage adds rows for other species against that same file. Counting rows
    // would report a dozen clips where there is one, and summing per row would multiply
    // that file's bytes by a dozen, driving the storage cap to prune audio that was never
    // over the cap in the first place.
    fun clipsBytesTotal(): Long {
        val c = readableDatabase.rawQuery(
            "SELECT DISTINCT clip_path FROM detections WHERE clip_path IS NOT NULL", null)
        var total = 0L
        c.use { while (it.moveToNext()) total += File(it.getString(0)).let { f -> if (f.exists()) f.length() else 0L } }
        return total
    }

    fun clipsCount(): Int {
        val c = readableDatabase.rawQuery(
            "SELECT COUNT(DISTINCT clip_path) FROM detections WHERE clip_path IS NOT NULL", null)
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /**
     * Deletes clip FILES oldest-first until under [capBytes], nulling clip_path on every
     * row that referenced the deleted file (never deletes the detection itself — API.md:
     * "clip may be null for a candidate whose clip was pruned by the storage cap").
     * Returns the number of FILES deleted.
     *
     * BY FILE, NOT BY ROW, since bout clips arrived. Deleting a file while nulling only
     * one of the rows that point at it would leave every other row in that bout referencing
     * a path that no longer exists — a clip the API still advertises and that 404s when
     * played, which is precisely the "confident, plausible-looking output from a partial
     * failure" §2d is about. A file is pinned if ANY of its rows is pinned, for the same
     * reason: the lifer is the audio, not the row that happens to name it.
     */
    /**
     * Every clip file that must survive: not just the file containing a pinned detection,
     * but every file of the BOUT that detection belongs to.
     *
     * A bout is one piece of evidence and can span several files (§11m). Pinning only the
     * file the pinned row happens to sit in would let the cap delete the rest of the same
     * bout, leaving the lifer's evidence starting halfway through a phrase — audible, and
     * wrong, which is worse than obviously missing. Pinning is per-bout because judgement
     * is per-bout.
     */
    fun pinnedClipPaths(settings: Settings): Set<String> {
        val pinned = pinnedDetectionIds()
        if (pinned.isEmpty()) return emptySet()
        val out = HashSet<String>()
        // The pinned set is two ids per confirmed species, so a query per pinned row is
        // cheap and runs on the ten-minute pruning tick.
        val days = HashSet<Pair<String, String>>()
        readableDatabase.rawQuery(
            "SELECT DISTINCT local_date, taxon_key FROM detections WHERE id IN (${pinned.joinToString(",")})", null
        ).use { while (it.moveToNext()) days.add(it.getString(0) to it.getString(1)) }
        for ((date, taxonKey) in days) {
            for (b in boutsForDay(date, taxonKey, settings)) {
                if (b.detectionIds.any { id -> id in pinned }) out += b.clips.map { c -> c.path }
            }
        }
        return out
    }

    fun pruneToCapBytes(capBytes: Long, settings: Settings): Int {
        var total = clipsBytesTotal()
        if (total <= capBytes) return 0
        // Pinned clips are exempt and are not counted as prunable. If the cap can only be
        // met by deleting pinned audio, the cap loses: a life list with missing evidence is
        // worth less than the disk space it would free.
        val pinnedPaths = pinnedClipPaths(settings)
        // Oldest FILE first, ordered by the earliest detection that references it.
        val c = readableDatabase.rawQuery(
            "SELECT clip_path, MIN(detected_at_ms) AS first_ms FROM detections " +
            "WHERE clip_path IS NOT NULL GROUP BY clip_path ORDER BY first_ms ASC", null)
        var pruned = 0
        c.use {
            while (it.moveToNext() && total > capBytes) {
                val path = it.getString(0)
                if (path in pinnedPaths) continue
                val f = File(path)
                val sz = if (f.exists()) f.length() else 0L
                if (f.exists()) f.delete()
                writableDatabase.update("detections", ContentValues().apply { putNull("clip_path") },
                    "clip_path = ?", arrayOf(path))
                total -= sz
                pruned++
            }
        }
        return pruned
    }

    /** Settings "Clear all" — wipes every detection, its clip file, and per-species
     *  threshold overrides, for a fresh start. Global settings (display_threshold etc.)
     *  and the static species reference table are untouched: this clears what was
     *  OBSERVED, not how the station is configured. */
    // ------------------------------------------------------------------ life list

    data class SpeciesStatus(
        val scientific: String, val common: String, val status: String,
        val firstDetectedAt: String?, val firstConfirmedAt: String?,
        val liferDetectionId: Long?, val bestDetectionId: Long?,
        val totalDetections: Int
    )

    /**
     * Record that a species was detected. Creates it as a CANDIDATE if unseen.
     *
     * Nothing here can ever produce a 'confirmed' — that transition happens only in
     * [recordVerification], from a human verdict. A classifier score, however high, is not
     * evidence of presence; it is an invitation to go and check. Keeping the two apart in
     * the write path is what makes the life list mean something.
     */
    fun noteDetection(detectionId: Long, taxon: Taxon, atMs: Long, confidence: Float, station: String) {
        val db = writableDatabase
        val iso = Detection.iso(atMs)
        val cur = db.rawQuery(
            "SELECT status, best_detection_id, total_detections FROM species_status WHERE scientific_name = ?",
            arrayOf(taxon.scientific))
        var status: String? = null; var bestId: Long? = null; var total = 0
        cur.use {
            if (it.moveToFirst()) {
                status = it.getString(0)
                bestId = if (it.isNull(1)) null else it.getLong(1)
                total = it.getInt(2)
            }
        }
        if (status == null) {
            db.insertWithOnConflict("species_status", null, ContentValues().apply {
                put("scientific_name", taxon.scientific); put("common_name", taxon.common)
                put("status", "candidate"); put("first_detected_at", iso)
                put("best_detection_id", detectionId); put("total_detections", 1)
                put("station", station)
            }, SQLiteDatabase.CONFLICT_IGNORE)
            return
        }
        // best_detection_id tracks the strongest evidence so far, and is PINNED, so it is
        // only moved when the new row genuinely beats the old one.
        val betterId = if (bestId == null) detectionId else {
            val prev = readableDatabase.rawQuery(
                "SELECT confidence FROM detections WHERE id = ?", arrayOf(bestId.toString())
            ).use { if (it.moveToFirst()) it.getFloat(0) else -1f }
            if (confidence > prev) detectionId else bestId
        }
        db.update("species_status", ContentValues().apply {
            put("total_detections", total + 1)
            put("best_detection_id", betterId)
        }, "scientific_name = ?", arrayOf(taxon.scientific))
    }

    /**
     * first_confirmed_at and lifer_detection_id are written ONCE and never moved: the
     * lifer moment is a fact about when it happened, not a running maximum.
     */
    /**
     * The two-part verdict, recorded against DETECTION IDS — never against a bout id.
     *
     * A bout is a read-time projection of `bout_gap_s`; move that setting and bouts merge
     * or split and their ids move with them. Persisting a judgement against one would
     * silently reattach it to different audio. Detection ids are stable, so the verdict for
     * a bout is written once per detection in it, which also gives the right behaviour when
     * the projection changes: a bout that later SPLITS leaves both halves verified, and two
     * that MERGE leave a partially-verified bout — which is displayed as exactly that
     * rather than rounded to done or not-done.
     *
     * THE TWO QUESTIONS ARE NOT THE SAME QUESTION.
     *  - [isGenuine] "is this a bird at all?" — answerable every time, and where nearly all
     *    the value is, because the false-positive mass is the actual problem.
     *  - [isSpecies] "is it *this* species?" — often unanswerable, and `"unsure"` is a real
     *    answer meaning "a bird, but I don't know what". That is NOT a rejection and must
     *    never be stored as one.
     *
     */
    fun recordBoutVerdict(detectionIds: List<Long>, isGenuine: String, isSpecies: String?,
                          note: String?): SpeciesStatus? {
        if (detectionIds.isEmpty()) return null
        val db = writableDatabase
        val scientific = readableDatabase.rawQuery(
            "SELECT taxon_scientific FROM detections WHERE id = ?",
            arrayOf(detectionIds.first().toString())
        ).use { if (it.moveToFirst()) it.getString(0) else null } ?: return null

        val at = Detection.iso(System.currentTimeMillis())
        db.beginTransaction()
        try {
            for (id in detectionIds) {
                db.insert("verifications", null, ContentValues().apply {
                    put("detection_id", id)
                    put("is_genuine", isGenuine)
                    put("is_species", isSpecies)
                    put("verified_at", at)
                    if (note != null) put("note", note)
                })
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }

        applySpeciesTier(scientific, isGenuine, isSpecies, detectionIds.first(), at)
        return speciesStatus(scientific)
    }

    /**
     * Move a species between life-list tiers after a verdict.
     *
     * TIERS EXIST SO THE LIST CANNOT OVERSTATE WHAT WAS SUPPLIED. If what a human actually
     * said was "that's a bird", the list must not claim a species confirmation — §2d
     * applied to the one artefact the whole station is for.
     *
     *   candidate  machine-proposed, no human has looked
     *   bird       a human confirmed a real bird here, but not which species
     *   confirmed  a human confirmed the species — the only tier that is a life tick
     *   rejected   a human said no
     *
     * Precedence is confirmed > rejected > bird > candidate, and confirmation is one-way:
     * one bad clip must not retract a lifer earned from a good one.
     */
    private fun applySpeciesTier(scientific: String, isGenuine: String, isSpecies: String?,
                                 detectionId: Long, at: String) {
        val db = writableDatabase
        val confirmedAlready = readableDatabase.rawQuery(
            "SELECT first_confirmed_at FROM species_status WHERE scientific_name = ?", arrayOf(scientific)
        ).use { if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null }

        if (isGenuine == "yes" && isSpecies == "yes") {
            if (confirmedAlready == null) {
                db.update("species_status", ContentValues().apply {
                    put("status", "confirmed")
                    put("first_confirmed_at", at)
                    put("lifer_detection_id", detectionId)
                }, "scientific_name = ?", arrayOf(scientific))
            }
            return
        }
        if (confirmedAlready != null) return    // already a life tick; nothing below downgrades it

        when {
            // Not a bird, or a bird that is not this species: the SPECIES claim is refused.
            isGenuine == "no" || isSpecies == "no" ->
                db.update("species_status", ContentValues().apply { put("status", "rejected") },
                    "scientific_name = ? AND first_confirmed_at IS NULL", arrayOf(scientific))
            // "A bird, but I don't know what." Real information, and explicitly not a
            // rejection — it promotes the species out of machine-proposed and no further.
            isGenuine == "yes" ->
                db.update("species_status", ContentValues().apply { put("status", "bird") },
                    "scientific_name = ? AND status = 'candidate'", arrayOf(scientific))
            // isGenuine == "unsure": the observer could not tell. Nothing is claimed either
            // way, and the species stays exactly where it was.
        }
    }

    /** detection id -> (is_genuine, is_species) for the newest verdict on each. */
    fun verdictsFor(ids: List<Long>): Map<Long, Pair<String, String?>> {
        if (ids.isEmpty()) return emptyMap()
        val out = HashMap<Long, Pair<String, String?>>()
        // The v7 migration normalises pre-existing rows into these columns, so there is
        // no legacy shape to interpret here — one representation, one reader.
        readableDatabase.rawQuery(
            "SELECT detection_id, is_genuine, is_species FROM verifications " +
            "WHERE detection_id IN (${ids.joinToString(",")}) ORDER BY verified_at ASC", null
        ).use {
            while (it.moveToNext()) {
                out[it.getLong(0)] = it.getString(1) to (if (it.isNull(2)) null else it.getString(2))
            }
        }
        return out
    }

    /**
     * The single-detection verdict behind the legacy `POST /api/verify/{id}`.
     *
     * Kept so an older dashboard still works against a new station, and expressed in terms
     * of the two-part verdict rather than duplicating the tier logic: `yes` was always a
     * species identification, `no` a rejection, and `unsure` never asserted bird-ness — so
     * it does not become one here.
     */
    fun recordVerification(detectionId: Long, verdict: String, note: String?): SpeciesStatus? =
        when (verdict) {
            "yes" -> recordBoutVerdict(listOf(detectionId), "yes", "yes", note)
            "no" -> recordBoutVerdict(listOf(detectionId), "no", null, note)
            else -> recordBoutVerdict(listOf(detectionId), "unsure", null, note)
        }

    /**
     * Species a human has explicitly rejected.
     *
     * A rejection is a STRONGER statement than any score: the observer listened and said
     * no. So rejected species are excluded from every "what was here" aggregate — the
     * species list, the day rollups, the top-species panel — even though their detection
     * rows stay in the database as evidence of what the model did. The rows are the record
     * of the classifier's behaviour; the species list is a claim about the world, and once
     * that claim has been checked and refused it must stop being made.
     *
     * Deliberately NOT filtered out of the raw feed: seeing a rejected row still appear
     * (rendered at 45% opacity per the design spec) is how you notice the model is still
     * producing it, which is exactly what a per-species threshold override is for.
     */
    fun rejectedSpecies(): Set<String> {
        val out = HashSet<String>()
        runCatching {
            readableDatabase.rawQuery(
                "SELECT scientific_name FROM species_status WHERE status = 'rejected'", null
            ).use { while (it.moveToNext()) out.add(it.getString(0)) }
        }
        return out
    }

    fun speciesStatus(scientific: String): SpeciesStatus? =
        readableDatabase.rawQuery(
            "SELECT scientific_name, common_name, status, first_detected_at, first_confirmed_at, " +
            "lifer_detection_id, best_detection_id, total_detections FROM species_status WHERE scientific_name = ?",
            arrayOf(scientific)
        ).use { if (!it.moveToFirst()) null else SpeciesStatus(
            it.getString(0), it.getString(1) ?: "", it.getString(2),
            if (it.isNull(3)) null else it.getString(3), if (it.isNull(4)) null else it.getString(4),
            if (it.isNull(5)) null else it.getLong(5), if (it.isNull(6)) null else it.getLong(6),
            it.getInt(7)) }

    fun lifeList(): List<SpeciesStatus> {
        val out = ArrayList<SpeciesStatus>()
        readableDatabase.rawQuery(
            "SELECT scientific_name, common_name, status, first_detected_at, first_confirmed_at, " +
            "lifer_detection_id, best_detection_id, total_detections FROM species_status " +
            "ORDER BY (status='confirmed') DESC, first_confirmed_at ASC, common_name ASC", null
        ).use { while (it.moveToNext()) out += SpeciesStatus(
            it.getString(0), it.getString(1) ?: "", it.getString(2),
            if (it.isNull(3)) null else it.getString(3), if (it.isNull(4)) null else it.getString(4),
            if (it.isNull(5)) null else it.getLong(5), if (it.isNull(6)) null else it.getLong(6),
            it.getInt(7)) }
        return out
    }

    /** BirdNET's 48-week convention: four weeks per month, the 4th absorbing the remainder.
     *  Must match how the priors were generated or every lookup is silently off by weeks. */
    fun weekOf(atMs: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
        val month = c.get(java.util.Calendar.MONTH) + 1
        val day = c.get(java.util.Calendar.DAY_OF_MONTH)
        return (month - 1) * 4 + minOf(3, (day - 1) / 7) + 1
    }

    /** All priors for one week, keyed by scientific name. Read once per query rather than
     *  once per row — 6522 rows is one cheap indexed scan, against thousands of lookups. */
    fun priorsForWeek(week: Int): Map<String, Double> {
        val m = HashMap<String, Double>(7000)
        runCatching {
            readableDatabase.rawQuery(
                "SELECT scientific_name, prior FROM species_prior WHERE week = ?",
                arrayOf(week.toString())
            ).use { while (it.moveToNext()) m[it.getString(0)] = it.getDouble(1) }
        }
        return m
    }

    /** Local occurrence prior for a species in a given BirdNET week (1-48), or null if the
     *  meta-model has not been run for this station yet. */
    fun prior(scientific: String, week: Int): Double? =
        readableDatabase.rawQuery(
            "SELECT prior FROM species_prior WHERE scientific_name = ? AND week = ?",
            arrayOf(scientific, week.toString())
        ).use { if (it.moveToFirst()) it.getDouble(0) else null }

    fun priorCount(): Int =
        readableDatabase.rawQuery("SELECT COUNT(*) FROM species_prior", null)
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    fun replacePriors(entries: List<Triple<String, Int, Double>>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("species_prior", null, null)
            for ((sci, week, p) in entries) db.insertWithOnConflict("species_prior", null,
                ContentValues().apply { put("scientific_name", sci); put("week", week); put("prior", p) },
                SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /**
     * The verification queue: unverified detections of species not yet decided.
     *
     * Order is rarity first, then score — the highest-stakes decisions while attention is
     * fresh. Only rows that still HAVE a clip are offered, since a detection cannot be
     * verified by ear without its audio, and the clip floor means not every row has one.
     */
    fun verifyQueue(settings: Settings, week: Int, limit: Int): List<Pair<DetectionRow, Double?>> {
        val rows = listDetections(sinceMs = null, date = null, group = null, state = "confirmed",
            minConf = null, limit = 2000, settings = settings)
        val decided = HashSet<String>()
        readableDatabase.rawQuery(
            "SELECT scientific_name FROM species_status WHERE status IN ('confirmed','rejected')", null
        ).use { while (it.moveToNext()) decided.add(it.getString(0)) }
        val verified = HashSet<Long>()
        readableDatabase.rawQuery("SELECT DISTINCT detection_id FROM verifications", null)
            .use { while (it.moveToNext()) verified.add(it.getLong(0)) }

        return rows.asSequence()
            .filter { it.clipPath != null && it.id !in verified && it.taxon.scientific !in decided }
            .distinctBy { it.taxon.scientific }          // one card per species, not per row
            .map { it to prior(it.taxon.scientific, week) }
            .sortedWith(compareBy({ it.second ?: 1.0 }, { -it.first.confidence }))
            .take(limit)
            .toList()
    }

    /** What a "clear all" would destroy, so the caller can say so before doing it. The old
     *  button reported nothing and left photos and orphaned clips behind, which is how it
     *  came to be described as "it didn't clear everything". */
    data class ClearPreview(val detections: Int, val clips: Int, val confirmedSpecies: Int, val pinnedClips: Int)

    fun clearPreview(): ClearPreview {
        fun count(sql: String): Int =
            readableDatabase.rawQuery(sql, null).use { if (it.moveToFirst()) it.getInt(0) else 0 }
        return ClearPreview(
            detections = count("SELECT COUNT(*) FROM detections"),
            clips = count("SELECT COUNT(DISTINCT clip_path) FROM detections WHERE clip_path IS NOT NULL"),
            confirmedSpecies = count("SELECT COUNT(*) FROM species_status WHERE status = 'confirmed'"),
            pinnedClips = pinnedDetectionIds().size
        )
    }

    /**
     * @param keepLifeList when true (the default), confirmed species, their verifications
     *        and their pinned clips survive — "clear what I observed" rather than "destroy
     *        the record of what I confirmed". A life list represents human decisions that
     *        no amount of re-recording can reproduce, so destroying it is a separate,
     *        explicit act rather than a side effect of tidying up the feed.
     */
    fun clearAll(settings: Settings, keepLifeList: Boolean = true) {
        val pinned = if (keepLifeList) pinnedDetectionIds() else emptySet()
        // Whole BOUTS are spared, not just the file the pinned row sits in — see
        // pinnedClipPaths. Half a bout is evidence that starts mid-phrase.
        val pinnedPaths = if (keepLifeList) pinnedClipPaths(settings) else emptySet()
        readableDatabase.rawQuery(
            "SELECT DISTINCT clip_path FROM detections WHERE clip_path IS NOT NULL", null
        ).use {
            while (it.moveToNext()) {
                val path = it.getString(0)
                if (path in pinnedPaths) continue
                runCatching { File(path).delete() }
            }
        }
        if (pinned.isEmpty()) {
            writableDatabase.delete("detections", null, null)
        } else {
            writableDatabase.delete("detections", "id NOT IN (${pinned.joinToString(",")})", null)
        }
        writableDatabase.delete("species_settings", null, null)
        writableDatabase.delete("outbox_delivery", null, null)
        if (!keepLifeList) {
            writableDatabase.delete("species_status", null, null)
            writableDatabase.delete("verifications", null, null)
        } else {
            // Candidates are observations and go; confirmed and rejected are decisions and stay.
            writableDatabase.delete("species_status", "status = 'candidate'", null)
        }
    }
}
