package dk.biomon.station

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.StatFs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * The always-on foreground service. Owns the whole capture -> inference -> curation ->
 * storage -> serve pipeline; every other class in this module is a component it wires
 * together. See API.md and DESIGN.md for the contract this implements.
 *
 * SURVIVING OXYGENOS: three independent layers, because any one of them is known to be
 * insufficient on its own on this OS —
 *   1. foreground service with type="microphone" (required for background mic access
 *      on API 29+) + a partial wake lock held for the service's whole lifetime.
 *   2. REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, prompted from MainActivity — OxygenOS's
 *      own "Deep Optimization" / battery manager can still kill an unwhitelisted
 *      foreground service; this is the step a human has to approve once (final report
 *      lists the exact settings screen).
 *   3. Watchdog: a periodic AlarmManager check independent of this process, so if
 *      OxygenOS kills the service anyway, something outside it notices and restarts it.
 */
class StationService : Service(), HealthProvider {

    companion object {
        const val CHANNEL_ID = "station"
        const val NOTIF_ID = 1
        /**
         * Clip storage cap. Raised from 8 GB when clips became bout-scoped.
         *
         * Modelled against the committed 19 h corpus (`data/station_backup_20260803`), the
         * only measured clip-rate evidence there is. 266 windows cleared the 0.50 clip
         * floor in 18.7 h. Under the old rule that was one 3 s file each: ~96 MB/day, and
         * 8 GB held ~85 days. Under bout clips those windows group into 202 clips averaging
         * ~12 s with pre- and post-roll: ~306 MB/day, and 8 GB would hold only ~27 days.
         *
         * Two assumptions, both stated because they move the number:
         * - That corpus is INDOOR and known-negative, so its triggers are isolated. It is
         *   therefore the WORST case for bout merging — real song produces many adjacent
         *   windows that collapse into one clip, so a deployed station should land below
         *   306 MB/day, not above.
         * - The phone reports >100 GB free, so 16 GB is a cap rather than a reservation;
         *   nothing is pre-allocated and pruning still runs every ten minutes.
         *
         * 16 GB at the modelled worst case is ~54 days of audio, which comfortably covers a
         * verification backlog of a few weeks — the actual requirement.
         */
        const val CLIP_CAP_BYTES = 16L * 1024 * 1024 * 1024
        const val HEARTBEAT_KEY = "heartbeat_ms"
        const val PREFS = "station_runtime"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var db: Database
    private lateinit var settingsStore: SettingsStore
    private lateinit var speciesTable: StationSpecies
    private lateinit var httpServer: HttpServer
    private lateinit var publisher: LocalPublisher
    private lateinit var audioCapture: AudioCapture
    private lateinit var boutRecorder: BoutRecorder
    private lateinit var clipsDir: File
    private var birdNet: BirdNet? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile private var spectrogram: Spectrogram? = null
    @Volatile private var priorStatus: String = "not started"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val serviceStartedAtMs = System.currentTimeMillis()
    private val windowsTotal = AtomicLong(0)
    private val windowsSkippedThermal = AtomicLong(0)
    private val lastInferenceMs = AtomicLong(0)
    private val meanInferenceMsBits = AtomicLong(java.lang.Double.doubleToLongBits(0.0))
    // Latest captured window as WAV, kept in memory only, for the dashboard's live
    // spectrogram (/api/live-audio) — updated every hop regardless of detection state,
    // unlike clipPath below which only exists for windows that cleared the retention floor.
    @Volatile private var lastWindowWav: ByteArray? = null
    @Volatile private var lastWindowAtMs: Long = 0L
    @Volatile private var listening = false
    @Volatile private var modelLoaded = false
    @Volatile private var modelError: String? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putInt("starts", prefs.getInt("starts", 0) + 1).apply()
        startForegroundNow()

        db = Database(this)
        settingsStore = SettingsStore(this)
        speciesTable = StationSpecies.load(this)
        publisher = LocalPublisher(db)
        clipsDir = File(getExternalFilesDir(null), "clips").apply { mkdirs() }
        boutRecorder = BoutRecorder(clipsDir)
        // Before any recording starts. A `.part` left by a kill is referenced by no row —
        // the row is only updated after the rename — so removing it strands nothing, and
        // leaving it would slowly fill the disk with audio nothing can ever play.
        boutRecorder.cleanupOrphans()
        audioCapture = AudioCapture(this)

        // Before the server can answer a single request: the dashboard is served from disk
        // now, and a fresh install has an empty dashboard directory until the bundled
        // assets are copied into it. Cheap (two small text files, skipped once present).
        DashboardStore.seed(this)

        val stationInfo = Station(id = "balcony-5t", name = "Balcony",
            lat = 55.6478, lon = 12.5875)
        httpServer = HttpServer(8848, this, db, settingsStore, stationInfo, this)
        httpServer.start()

        acquireWakeLock()
        loadModelAndStartCapture()
        // Off the capture thread and after it: priors are a nicety, audio is not.
        scope.launch {
            priorStatus = PriorBuilder.buildIfNeeded(
                this@StationService, db, speciesTable, stationInfo.lat ?: 0.0, stationInfo.lon ?: 0.0)
            android.util.Log.i("StationService", "priors: $priorStatus")
        }
        schedulePruning()
        schedulePhotoFetch()
        Watchdog.schedule(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        listening = false
        runCatching { audioCapture.stop() }
        // AFTER capture has stopped, so nothing is still appending, and BEFORE the database
        // closes, so the rows can still be updated. A bout in progress is finished short
        // rather than discarded — a clip that is four seconds less than ideal is worth more
        // than no clip, and the alternative is a `.part` the next start would delete.
        runCatching { boutRecorder.flush()?.let(::onClipFinished) }
        runCatching { httpServer.stop() }
        runCatching { birdNet?.close() }
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    // ------------------------------------------------------------------ startup

    private fun startForegroundNow() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "Station", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Biomon Station is listening"
            }
            nm.createNotificationChannel(ch)
        }
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Biomon Station")
            .setContentText("Listening…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "biomon:station").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun loadModelAndStartCapture() {
        try {
            birdNet = BirdNet.load(this)
            modelLoaded = true
        } catch (e: Exception) {
            modelError = e.message
            modelLoaded = false
            updateNotification("Model missing — see /api/health")
            return   // no point capturing audio with nothing to run it through
        }
        if (!audioCapture.hasPermission()) {
            modelError = "RECORD_AUDIO permission not granted"
            updateNotification("Microphone permission needed")
            return
        }
        audioCapture.start(onWindow = ::onWindow, onError = ::onCaptureError, onPcm = ::onPcm)
        listening = true
        updateNotification("Listening…")
    }

    /**
     * The continuous capture tap — every sample exactly once, before windowing. Feeds the
     * live spectrogram only; nothing here touches inference or the database.
     *
     * The Spectrogram is built HERE rather than in onCreate because its every frequency
     * mapping depends on the capture rate, and that is not known until AudioCapture has
     * negotiated it with the hardware (48 kHz is requested, not guaranteed). Rebuilding on
     * a rate change also covers a capture restart that lands on a different rate, which
     * would otherwise silently mislabel every axis on the display.
     */
    private fun onPcm(buf: ShortArray, n: Int, captureRate: Int) {
        var s = spectrogram
        if (s == null || s.sampleRate != captureRate) {
            s = Spectrogram(captureRate) { atSeconds, magnitudes ->
                httpServer.broadcastSpectrogramColumn(atSeconds, magnitudes)
            }
            spectrogram = s
        }
        s.accept(buf, n, System.currentTimeMillis())

        // The same continuous tap also feeds clip recording: the pre-roll ring, and the
        // body of any bout currently being written. A clip finishes here rather than in
        // onWindow because its end is defined by audio arriving with no new trigger.
        boutRecorder.accept(buf, n, captureRate)?.let(::onClipFinished)
    }

    /**
     * A bout clip finished. This is the second half of the deferred write: the rows went in
     * without a clip while the audio was still being recorded, and now they get one.
     *
     * Ordering matters — the file is already renamed into place by the time this is called,
     * so there is no window in which a row names a clip that does not exist.
     */
    private fun onClipFinished(clip: BoutRecorder.Finished) {
        if (clip.detectionIds.isEmpty()) {
            // Nothing to attach it to: every row from this bout was dropped (non-taxon, or
            // off-checklist). Keeping the file would be an orphan the cap eventually
            // deletes; deleting it now is the same outcome, sooner and traceably.
            runCatching { File(clip.path).delete() }
            return
        }
        runCatching { db.attachClip(clip.detectionIds, clip.path, clip.seconds, clip.startedAtMs) }
            .onFailure {
                android.util.Log.w("StationService", "could not attach clip ${clip.path}", it)
                runCatching { File(clip.path).delete() }
            }
    }

    private fun onCaptureError(e: Throwable) {
        listening = false
        android.util.Log.e("StationService", "capture failed, retrying in 5s", e)
        updateNotification("Capture error — retrying")
        scope.launch {
            kotlinx.coroutines.delay(5000)
            if (audioCapture.hasPermission()) audioCapture.start(::onWindow, ::onCaptureError, ::onPcm).also { listening = true }
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Biomon Station").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setOngoing(true).build()
        nm.notify(NOTIF_ID, n)
    }

    // ------------------------------------------------------------------ the pipeline

    private fun onWindow(w: AudioCapture.Window) {
        prefs.edit().putLong(HEARTBEAT_KEY, System.currentTimeMillis()).apply()
        // Ambient audio, independent of inference/thermal throttling below — the live
        // spectrogram shows what the mic heard, not just windows the model got to score.
        lastWindowWav = encodeWav(w.pcm16, AudioCapture.MODEL_RATE)
        lastWindowAtMs = w.startAtMs

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        val throttled = thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE
        if (throttled && windowsTotal.get() % 2L == 0L) {
            windowsSkippedThermal.incrementAndGet()
            return   // real throttling: skip every other window once the OS flags heat
        }

        val model = birdNet ?: return
        val t0 = System.nanoTime()
        val scores = try { model.predict(w.samples) } catch (e: Exception) {
            android.util.Log.e("StationService", "inference failed", e); return
        }
        val ms = (System.nanoTime() - t0) / 1_000_000.0
        lastInferenceMs.set(ms.toLong())
        windowsTotal.incrementAndGet()
        synchronized(meanInferenceMsBits) {
            val prevMean = java.lang.Double.longBitsToDouble(meanInferenceMsBits.get())
            val n = windowsTotal.get().coerceAtLeast(1)
            val newMean = prevMean + (ms - prevMean) / n
            meanInferenceMsBits.set(java.lang.Double.doubleToLongBits(newMean))
        }

        val cfg = settingsStore.get()
        val floor = cfg.retentionFloor
        val hits = ArrayList<Pair<Int, Float>>()
        for (i in scores.indices) if (scores[i] >= floor) hits += i to scores[i]
        if (hits.isEmpty()) return

        // Whether this window's AUDIO is worth keeping is a different question from whether
        // its rows are (Settings.clipFloor). Decided once for the whole window rather than
        // per hit, because there is only one clip: if anything in this window is worth
        // listening to, the window is worth keeping.
        //
        // The clip is no longer written here. A bout clip runs on past its trigger, so it
        // cannot exist yet; trigger() opens or extends the recording and the rows below go
        // in with no clip, to be updated by onClipFinished when the audio is complete.
        val keepClip = hits.any { it.second >= cfg.clipFloor }
        if (keepClip) boutRecorder.trigger(w.startAtMs)

        for ((idx, conf) in hits) {
            val sp = speciesTable.byIndex[idx] ?: continue
            if (sp.group == "non_taxon") continue
            // Off the Danish checklist => not a real detection here, full stop (a Compact
            // Weaver or Flammulated Owl in Copenhagen is never real). Excluded at the write
            // path so it never reaches the database, unlike the region/season *penalty*
            // below which still stores and shows in-list-but-implausible detections as
            // low-confidence candidates.
            if (!sp.regional) continue
            val taxon = sp.copy(modelIndex = idx)
            // Region/season plausibility is judged once, here, against the calendar month
            // the bird was actually heard in — not re-evaluated against "now" every time
            // the dashboard is opened (Database.kt's NewDetection doc explains why).
            val calendarMonth = java.util.Calendar.getInstance().apply { timeInMillis = w.startAtMs }
                .get(java.util.Calendar.MONTH) + 1
            val inSeason = seasonallyPlausible(taxon.monthFractions, taxon.monthsTotal, calendarMonth)
            val nd = NewDetection(
                detectedAtMs = w.startAtMs, windowMs = (AudioCapture.WINDOW_S * 1000).toInt(),
                taxon = taxon, detector = "birdnet", detectorVersion = "GLOBAL_6K_V2.4",
                scoreType = "birdnet_confidence", confidence = conf,
                // No clip yet, by construction — see keepClip above. clipSeconds becomes
                // the clip's REAL duration when onClipFinished attaches it, rather than the
                // model's window length, which was only ever true by coincidence.
                clipPath = null, clipSeconds = 0.0, clipStartMs = null,
                taxonRegional = taxon.regional, taxonInSeason = inSeason
            )
            val id = db.insert(nd)
            if (keepClip) boutRecorder.attach(id)
            // Candidates accumulate from new detections only — nothing is grandfathered into
            // the life list, and nothing here can ever reach 'confirmed'. That transition is
            // reserved for a human verdict (Database.recordVerification).
            db.noteDetection(id, taxon, w.startAtMs, conf, "balcony-5t")
            scope.launch { publisher.publish(listOf(id)) }

            val curated = db.listDetections(sinceMs = w.startAtMs - 1, date = null, group = null,
                state = "all", minConf = null, limit = 5, settings = settingsStore.get())
                .firstOrNull { it.id == id }
            if (curated != null) {
                val photo = PhotoCache.photoMeta(this, taxon.key)?.let {
                    JSONObject().put("url", "/api/photo/${taxon.key}")
                        .put("attribution", it.optString("attribution"))
                        .put("license", it.optString("license")).put("source", it.optString("source"))
                }
                httpServer.broadcastDetection(curated.toDetection(), photo)
            }
        }
    }

    private fun encodeWav(pcm16: ShortArray, sampleRate: Int): ByteArray {
        val dataLen = pcm16.size * 2
        val bb = java.nio.ByteBuffer.allocate(44 + dataLen).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val byteRate = sampleRate * 2
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1)
        bb.putShort(1); bb.putInt(sampleRate); bb.putInt(byteRate)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        for (s in pcm16) bb.putShort(s)
        return bb.array()
    }

    // ------------------------------------------------------------------ maintenance

    private fun schedulePruning() {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(10 * 60_000L)
                runCatching { db.pruneToCapBytes(CLIP_CAP_BYTES) }
            }
        }
    }

    private fun schedulePhotoFetch() {
        scope.launch {
            kotlinx.coroutines.delay(30_000L)   // let the first detections land first
            while (true) {
                val taxa = runCatching { db.speciesSummary(settingsStore.get()).map { it.taxon } }.getOrDefault(emptyList())
                android.util.Log.i("StationService", "photo fetch tick: ${taxa.size} confirmed taxa")
                if (taxa.isNotEmpty()) {
                    val result = runCatching { PhotoCache.fetchMissing(this@StationService, taxa) }
                    android.util.Log.i("StationService", "photo fetch result: ${result.getOrNull() ?: result.exceptionOrNull()}")
                }
                kotlinx.coroutines.delay(10 * 60_000L)
            }
        }
    }

    // ------------------------------------------------------------------ HealthProvider

    override fun latestAudioWav(): Pair<ByteArray, Long>? = lastWindowWav?.let { it to lastWindowAtMs }

    override fun health(): JSONObject {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val battTempTenths = runCatching {
            registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        }.getOrDefault(-1)
        val thermalStatus = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) pm.currentThermalStatus else 0
        val thermalName = arrayOf("NONE", "LIGHT", "MODERATE", "SEVERE", "CRITICAL", "EMERGENCY", "SHUTDOWN")
            .getOrElse(thermalStatus) { "UNKNOWN" }

        val statFs = StatFs(filesDir.path)
        val freeBytes = statFs.availableBytes
        val clipsBytes = runCatching { db.clipsBytesTotal() }.getOrDefault(0L)
        val clipsCount = runCatching { db.clipsCount() }.getOrDefault(0)
        val audioStatus = audioCapture.status

        return JSONObject()
            .put("schema", 1)
            .put("station", JSONObject().put("id", "balcony-5t").put("name", "Balcony")
                .put("lat", 55.6478).put("lon", 12.5875).put("tz", java.util.TimeZone.getDefault().id))
            // The build actually running, not the one you think is running. GIT_SHA carries
            // a trailing "+" when the tree was dirty at build time, because "which commit"
            // is a lie if uncommitted edits went into the APK.
            .put("app", JSONObject()
                .put("version", BuildConfig.VERSION_NAME)
                .put("commit", BuildConfig.GIT_SHA)
                .put("built_at", BuildConfig.BUILT_AT))
            // The dashboard updates independently of the APK now, so the build stamp alone
            // no longer identifies the UI on screen. This is the other half of the answer.
            .put("dashboard", DashboardStore.healthJson(this))
            .put("server_ms", System.currentTimeMillis())
            .put("listening", listening)
            .put("uptime_ms", System.currentTimeMillis() - serviceStartedAtMs)
            .put("service_started_at_ms", serviceStartedAtMs)
            .put("restarts", (prefs.getInt("starts", 1) - 1).coerceAtLeast(0))
            .put("model", JSONObject().put("name", "birdnet").put("version", "GLOBAL_6K_V2.4")
                .put("loaded", modelLoaded).put("classes", if (modelLoaded) BirdNet.N_CLASSES_EXPECTED else 0)
                .put("error", modelError ?: JSONObject.NULL))
            .put("audio", JSONObject()
                .put("sample_rate", audioStatus?.sampleRate ?: AudioCapture.MODEL_RATE)
                .put("source", audioStatus?.sourceName ?: "unknown")
                .put("window_s", AudioCapture.WINDOW_S).put("hop_s", AudioCapture.HOP_S))
            .put("inference", JSONObject()
                .put("last_ms", lastInferenceMs.get())
                .put("mean_ms", Math.round(java.lang.Double.longBitsToDouble(meanInferenceMsBits.get())))
                .put("duty_pct", Math.round(lastInferenceMs.get() / (AudioCapture.HOP_S * 1000) * 1000) / 10.0)
                .put("windows_total", windowsTotal.get())
                .put("windows_skipped_thermal", windowsSkippedThermal.get()))
            .put("priors", JSONObject()
                .put("rows", runCatching { db.priorCount() }.getOrDefault(0))
                .put("meta_model_present", MetaModel.isPresent(this))
                .put("status", priorStatus))
            .put("spectrogram", spectrogram.let { s ->
                JSONObject()
                    .put("running", s != null)
                    .put("sample_rate", s?.sampleRate ?: JSONObject.NULL)
                    .put("bins", Spectrogram.BINS)
                    .put("target_cps", Spectrogram.COLUMNS_PER_SECOND)
                    // Measured against the wall clock, so it drops if the capture thread
                    // stalls — the whole point of reporting it rather than the target.
                    .put("measured_cps", s?.columnsPerSecond ?: 0.0)
                    .put("frames_emitted", s?.framesEmitted?.get() ?: 0L)
                    .put("clients", httpServer.spectrogramClients())
                    .put("frames_dropped", httpServer.spectrogramDropped())
            })
            .put("thermal", JSONObject().put("battery_c", battTempTenths / 10.0)
                .put("status", thermalName).put("throttled", thermalStatus >= PowerManager.THERMAL_STATUS_MODERATE))
            // `clips` counts FILES, not rows — one bout clip serves every detection in the
            // bout. `clips_failed` is separate on purpose (§2d): a station that has quietly
            // stopped being able to write audio must not look like a quiet station.
            .put("storage", JSONObject().put("clips_bytes", clipsBytes).put("cap_bytes", CLIP_CAP_BYTES)
                .put("free_bytes", freeBytes).put("clips", clipsCount).put("pruned_total", 0)
                .put("clips_written", boutRecorder.clipsWritten)
                .put("clips_failed", boutRecorder.failedClips)
                .put("recording", boutRecorder.isRecording()))
            .put("queue", JSONObject().put("pending", 0).put("publishers", org.json.JSONArray(listOf("local"))))
            .put("settings", settingsStore.get().json())
    }
}
