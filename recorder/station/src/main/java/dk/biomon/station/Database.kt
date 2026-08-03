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
    val clipPath: String?,
    val clipSeconds: Double,
    val taxonRegional: Boolean = true,
    val taxonInSeason: Boolean = true
)

/** A stored row plus the read-time curation fields computed for it. */
data class DetectionRow(
    val id: Long, val detectedAtMs: Long, val windowMs: Int, val taxon: Taxon,
    val detector: String, val detectorVersion: String, val scoreType: String,
    val confidence: Float, val clipPath: String?, val clipSeconds: Double,
    val repeatCount: Int, val state: String,
    val taxonRegional: Boolean = true, val taxonInSeason: Boolean = true
) {
    fun toDetection(): Detection = Detection(
        id = id, detectedAtMs = detectedAtMs, windowStartMs = detectedAtMs, windowMs = windowMs,
        taxon = taxon, detector = detector, detectorVersion = detectorVersion, scoreType = scoreType,
        confidence = confidence, clipPath = clipPath, clipSeconds = clipSeconds,
        state = state, repeatCount = repeatCount, regional = taxonRegional, inSeason = taxonInSeason
    )
}

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
class Database(ctx: Context) : SQLiteOpenHelper(ctx.applicationContext, "station.db", null, 4) {

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
                clip_seconds REAL NOT NULL
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
            val whereKey = "taxon_group = 'non_taxon' OR taxon_key IN ($NON_TAXON_KEYS_SQL)"
            val c = db.rawQuery("SELECT clip_path FROM detections WHERE ($whereKey) AND clip_path IS NOT NULL", null)
            c.use { while (it.moveToNext()) runCatching { File(it.getString(0)).delete() } }
            db.execSQL("DELETE FROM detections WHERE $whereKey")
        }
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

        /** The threshold this exact row must clear to be "confirmed". An explicit
         *  per-species override replaces the global default outright; otherwise the
         *  region/season penalty (if enabled) stacks additively on top of it. */
        fun effectiveThreshold(
            taxonKey: String, regional: Boolean, inSeason: Boolean,
            settings: Settings, overrides: Map<String, Float>
        ): Float {
            overrides[taxonKey]?.let { return it }
            var t = settings.displayThreshold
            if (settings.regionSeasonFilterEnabled) {
                if (!regional) t += REGIONAL_PENALTY
                if (!inSeason) t += SEASONAL_PENALTY
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
        return NewDetection(l("detected_at_ms"), i("window_ms"), taxon, s("detector"),
            s("detector_version"), s("score_type"), f("confidence"),
            c.getString(c.getColumnIndexOrThrow("clip_path")), c.getDouble(c.getColumnIndexOrThrow("clip_seconds")),
            taxonRegional = i("taxon_regional") != 0, taxonInSeason = i("taxon_in_season") != 0)
    }

    private fun readRows(c: android.database.Cursor): List<DetectionRow> {
        val out = ArrayList<DetectionRow>()
        while (c.moveToNext()) {
            val id = c.getLong(c.getColumnIndexOrThrow("id"))
            val d = rowFrom(c)
            out += DetectionRow(id, d.detectedAtMs, d.windowMs, d.taxon, d.detector, d.detectorVersion,
                d.scoreType, d.confidence, d.clipPath, d.clipSeconds, repeatCount = 0, state = "candidate",
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
            val threshold = effectiveThreshold(r.taxon.key, r.taxonRegional, r.taxonInSeason, settings, overrides)
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
        val gapMs = settings.boutGapSeconds * 1000L
        return byDate.map { (date, rows) ->
            val confirmed = rows.groupBy { it.taxonKey }.values.flatMap { forTaxon ->
                // A species qualifies for the day only if its detections form at least
                // repeatRequired bouts; then every above-threshold row of that species counts.
                val bouts = countBouts(forTaxon.map { it.atMs }.sorted(), gapMs)
                if (bouts < settings.repeatRequired) emptyList()
                else forTaxon.filter {
                    it.confidence >= effectiveThreshold(it.taxonKey, it.regional, it.inSeason, settings, overrides)
                }
            }
            DaySummary(date, confirmed.size, confirmed.map { it.taxonKey }.distinct().size)
        }.sortedByDescending { it.date }
    }

    fun summaryForDate(date: String, settings: Settings): DaySummaryData {
        val rows = listDetections(sinceMs = null, date = date, group = null, state = "confirmed",
            minConf = null, limit = 100_000, settings = settings)
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
        return rows.groupBy { it.taxon.key }.values.map { v ->
            SpeciesAgg(v[0].taxon, v.size, v.maxOf { it.detectedAtMs }, v.maxOf { it.confidence })
        }.sortedByDescending { it.count }
    }

    fun clipsBytesTotal(): Long {
        val c = readableDatabase.rawQuery("SELECT clip_path FROM detections WHERE clip_path IS NOT NULL", null)
        var total = 0L
        c.use { while (it.moveToNext()) total += File(it.getString(0)).let { f -> if (f.exists()) f.length() else 0L } }
        return total
    }

    fun clipsCount(): Int {
        val c = readableDatabase.rawQuery("SELECT COUNT(*) FROM detections WHERE clip_path IS NOT NULL", null)
        return c.use { if (it.moveToFirst()) it.getInt(0) else 0 }
    }

    /** Deletes clip FILES oldest-first until under [capBytes], nulling clip_path in the
     *  row (never deletes the detection itself — API.md: "clip may be null for a
     *  candidate whose clip was pruned by the storage cap"). Returns rows pruned. */
    fun pruneToCapBytes(capBytes: Long): Int {
        var total = clipsBytesTotal()
        if (total <= capBytes) return 0
        val c = readableDatabase.rawQuery(
            "SELECT id, clip_path FROM detections WHERE clip_path IS NOT NULL ORDER BY detected_at_ms ASC", null)
        var pruned = 0
        c.use {
            while (it.moveToNext() && total > capBytes) {
                val id = it.getLong(0); val path = it.getString(1)
                val f = File(path)
                val sz = if (f.exists()) f.length() else 0L
                if (f.exists()) f.delete()
                writableDatabase.update("detections", ContentValues().apply { putNull("clip_path") },
                    "id = ?", arrayOf(id.toString()))
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
    fun clearAll() {
        val c = readableDatabase.rawQuery("SELECT clip_path FROM detections WHERE clip_path IS NOT NULL", null)
        c.use { while (it.moveToNext()) runCatching { File(it.getString(0)).delete() } }
        writableDatabase.delete("detections", null, null)
        writableDatabase.delete("species_settings", null, null)
        writableDatabase.delete("outbox_delivery", null, null)
    }
}
