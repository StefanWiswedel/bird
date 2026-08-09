package dk.biomon.station

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes ONE clip per bout, with audio from before and after the trigger.
 *
 * WHY THIS EXISTS. The old `writeClip` wrote exactly the 3.0 s model window. Three seconds
 * is what BirdNET needs; it is not what a person needs. Species identification by ear
 * depends on rhythm, repetition and phrase structure, and a single chirp cut out of the
 * middle of a phrase is ambiguous even to an expert — which makes it useless to the person
 * this station is actually for. A continuously singing bird also produced a fresh 3 s
 * fragment every 1.5 s hop: dozens of files, all of one event, none of them the event.
 *
 * Three things follow, and each one dictates a piece of the design below.
 *
 * PRE-ROLL — detections fire mid-phrase and the opening notes are often the diagnostic
 * part, so the clip has to start BEFORE the trigger. A ring buffer fed from
 * `AudioCapture.onPcm` (the continuous tap, every sample exactly once, already there for
 * the spectrogram) holds the recent past so it can be written retroactively.
 *
 * POST-ROLL — you cannot record the future. The clip therefore cannot be written when the
 * detection happens; it is written some seconds later, and the rows are updated with the
 * path afterwards. That deferral is the whole reason this class is stateful and is where
 * the consistency hazards live (see "CONSISTENCY" below).
 *
 * ONE CLIP PER BOUT — the recording stays open while detections keep arriving, so a bird
 * that sings for twenty seconds produces one twenty-second clip rather than fourteen
 * overlapping fragments. Fewer files AND better ones.
 *
 * CONSISTENCY — a row must never point at a clip that was never finished. Audio is
 * streamed to `<name>.wav.part` and the file is renamed into place only once its header is
 * final; only then are the rows updated. Every abnormal exit therefore leaves either a
 * finished clip with rows pointing at it, or an orphaned `.part` that no row references
 * and that [cleanupOrphans] removes at the next start. Nothing in between is reachable:
 * - service shutdown / watchdog restart: [flush] finalises what it can; anything it cannot
 *   stays a `.part`.
 * - storage full: the write throws, [abort] deletes the `.part`, the rows keep
 *   `clip_path = NULL`, which the API already documents as a legitimate state.
 *
 * ONE THREAD. [accept], [trigger] and [attach] are all called from AudioCapture's capture
 * thread. [flush] is not, so the mutating methods are synchronised; the lock is
 * uncontended in the normal case and the work under it is a memcpy and a file append.
 */
class BoutRecorder(private val clipsDir: File) {

    companion object {
        private const val TAG = "BoutRecorder"

        /** Audio kept before the trigger. The model window itself is 3 s and is already
         *  history by the time we are told about it, so the ring must hold the window PLUS
         *  the lead-in we actually want. 5 s of lead-in covers the run-up to a phrase
         *  without turning every clip into mostly silence. */
        const val PRE_ROLL_S = 5.0

        /** Audio kept after the last trigger, and also the gap that ends a bout: if nothing
         *  new fires for this long, the recording closes.
         *
         *  DELIBERATELY NOT `settings.boutGapSeconds` (60 s), and the difference is worth
         *  stating. That setting answers "are these the same piece of EVIDENCE?", which is
         *  a curation question and stays exactly as it is in `countBouts`. This answers "is
         *  it worth recording the silence between these to keep one continuous file?",
         *  which is a listening question. Using 60 s here was measured against the
         *  committed corpus and produced 35% more bytes than a 4 s gap, all of it silence,
         *  for clips up to a minute long that a person then has to sit through. Adjacent
         *  windows of one continuous song are 1.5 s apart — far inside this — so the case
         *  that motivated bout clips is fully covered either way. */
        const val POST_ROLL_S = 4.0

        /** Ring length. Exactly the pre-roll plus the model window, because the whole ring
         *  is dumped when a clip opens and that is precisely the audio wanted: the window
         *  that triggered, and the lead-in before it. ~8 s of 48 kHz mono 16-bit is 768 kB. */
        const val RING_S = PRE_ROLL_S + AudioCapture.WINDOW_S

        /** Hard ceiling on one clip. A bird can sing for ten minutes; a ten-minute WAV is
         *  57 MB and nobody listens to it. At the cap the clip is finalised and attached,
         *  and continuing detections open the next one — so a long song becomes a series of
         *  minute-long clips rather than one unusable file or one unbounded allocation. */
        const val MAX_CLIP_S = 60.0

        /** Not a clip. Guards against a runaway that would otherwise be caught only by the
         *  storage cap ten minutes later. */
        private const val WRITE_CHUNK_SHORTS = 4096
    }

    /** A finished clip, ready to be attached to the rows that triggered it. */
    data class Finished(val path: String, val seconds: Double, val startedAtMs: Long,
                        val detectionIds: List<Long>)

    // ---- ring buffer (pre-roll), capture-rate samples, newest-last -------------------

    private var ring = ShortArray(0)
    private var ringRate = 0
    private var ringWrite = 0          // next write index
    private var ringFilled = 0         // how much of the ring holds real audio

    // ---- open clip ------------------------------------------------------------------

    private var raf: RandomAccessFile? = null
    private var partFile: File? = null
    private var finalFile: File? = null
    private var clipRate = 0
    private var clipStartedAtMs = 0L
    private var samplesWritten = 0L
    private var samplesSinceTrigger = 0L
    private val pendingIds = ArrayList<Long>()
    private var byteBuf = ByteArray(WRITE_CHUNK_SHORTS * 2)

    /** Clips abandoned because the write failed — surfaced in /api/health so a station
     *  that has silently stopped keeping audio says so, rather than just looking quiet. */
    @Volatile var failedClips = 0L; private set
    @Volatile var clipsWritten = 0L; private set

    /**
     * Delete `.part` files left behind by a kill. Call once at service start, BEFORE any
     * recording begins. An orphan is by construction referenced by no row (the row is
     * updated only after the rename), so deleting it can never strand a reference.
     */
    fun cleanupOrphans(): Int {
        val stale = clipsDir.listFiles { f -> f.isFile && f.name.endsWith(".part") } ?: return 0
        var n = 0
        for (f in stale) if (runCatching { f.delete() }.getOrDefault(false)) n++
        if (n > 0) Log.i(TAG, "removed $n unfinished clip(s) left by a previous run")
        return n
    }

    /**
     * Continuous PCM from the capture thread. Feeds the ring always, and the open clip if
     * there is one. Returns a finished clip when this block ended the bout, otherwise null
     * — the caller attaches it to its rows.
     */
    @Synchronized
    fun accept(buf: ShortArray, n: Int, rate: Int): Finished? {
        if (n <= 0) return null
        // A capture restart can land on a different sample rate (AudioCapture negotiates
        // it with the hardware). Splicing two rates into one WAV would play back at the
        // wrong pitch, so the open clip is closed at the old rate first.
        var done: Finished? = null
        if (rate != ringRate) {
            if (raf != null) done = finalizeClip()
            ringRate = rate
            ring = ShortArray((RING_S * rate).toInt().coerceAtLeast(1))
            ringWrite = 0; ringFilled = 0
        }
        appendToRing(buf, n)

        if (raf != null) {
            if (!appendToClip(buf, n)) { abort(); return done }
            samplesWritten += n
            samplesSinceTrigger += n
            val postRollSamples = (POST_ROLL_S * clipRate).toLong()
            val maxSamples = (MAX_CLIP_S * clipRate).toLong()
            // Sample counting, not wall clock: the post-roll is a length of AUDIO, and
            // deriving it from the samples actually written cannot drift when the capture
            // thread stalls behind a slow inference pass.
            if (samplesSinceTrigger >= postRollSamples || samplesWritten >= maxSamples) {
                done = finalizeClip()
            }
        }
        return done
    }

    /**
     * A window worth keeping just fired. Opens a clip if none is open (dumping the ring as
     * pre-roll), and extends the current one otherwise. [atMs] is the window's start time,
     * used only to name the file and to record when the clip's audio begins.
     */
    @Synchronized
    fun trigger(atMs: Long) {
        if (raf == null) open(atMs)
        samplesSinceTrigger = 0
    }

    /** Register a detection row to receive this clip's path when it finalises. Called
     *  after [trigger] for the same window, on the same thread. */
    @Synchronized
    fun attach(id: Long) {
        if (raf != null) pendingIds.add(id)
    }

    /** True while a clip is open — the caller uses this to decide whether a row it is
     *  about to insert can expect a clip at all. */
    @Synchronized
    fun isRecording(): Boolean = raf != null

    /** Finalise anything open, e.g. at service shutdown. Safe to call when idle. */
    @Synchronized
    fun flush(): Finished? = if (raf == null) null else finalizeClip()

    // ---- internals -------------------------------------------------------------------

    private fun appendToRing(buf: ShortArray, n: Int) {
        if (ring.isEmpty()) return
        if (n >= ring.size) {
            // A single read longer than the whole ring: keep only its tail.
            System.arraycopy(buf, n - ring.size, ring, 0, ring.size)
            ringWrite = 0; ringFilled = ring.size
            return
        }
        val first = minOf(n, ring.size - ringWrite)
        System.arraycopy(buf, 0, ring, ringWrite, first)
        if (first < n) System.arraycopy(buf, first, ring, 0, n - first)
        ringWrite = (ringWrite + n) % ring.size
        ringFilled = minOf(ring.size, ringFilled + n)
    }

    private fun open(atMs: Long) {
        val part = File(clipsDir, "$atMs.wav.part")
        val fin = File(clipsDir, "$atMs.wav")
        try {
            val r = RandomAccessFile(part, "rw")
            r.setLength(0)
            r.write(ByteArray(44))          // header placeholder; rewritten on finalise
            raf = r
            partFile = part
            finalFile = fin
            clipRate = ringRate
            samplesWritten = 0
            samplesSinceTrigger = 0
            pendingIds.clear()
            // The ring is dumped first, so the clip starts PRE_ROLL_S before the window
            // that triggered it rather than at the trigger.
            val preRoll = dumpRing()
            clipStartedAtMs = atMs + (AudioCapture.WINDOW_S * 1000).toLong() -
                              (preRoll.toDouble() / clipRate * 1000).toLong()
        } catch (e: Exception) {
            Log.w(TAG, "could not open clip for $atMs", e)
            failedClips++
            runCatching { raf?.close() }
            raf = null; partFile = null; finalFile = null
            runCatching { part.delete() }
        }
    }

    /** Writes the ring oldest-first. Returns how many samples went out. */
    private fun dumpRing(): Int {
        if (ringFilled == 0) return 0
        val start = (ringWrite - ringFilled + ring.size) % ring.size
        var written = 0
        var i = 0
        while (i < ringFilled) {
            val idx = (start + i) % ring.size
            val run = minOf(ringFilled - i, ring.size - idx, WRITE_CHUNK_SHORTS)
            if (!writeShorts(ring, idx, run)) return written
            written += run
            i += run
        }
        samplesWritten += written
        return written
    }

    private fun appendToClip(buf: ShortArray, n: Int): Boolean {
        var off = 0
        while (off < n) {
            val run = minOf(WRITE_CHUNK_SHORTS, n - off)
            if (!writeShorts(buf, off, run)) return false
            off += run
        }
        return true
    }

    private fun writeShorts(src: ShortArray, offset: Int, count: Int): Boolean {
        val r = raf ?: return false
        if (byteBuf.size < count * 2) byteBuf = ByteArray(count * 2)
        var b = 0
        for (i in 0 until count) {
            val v = src[offset + i].toInt()
            byteBuf[b++] = (v and 0xFF).toByte()
            byteBuf[b++] = ((v shr 8) and 0xFF).toByte()
        }
        return try {
            r.write(byteBuf, 0, count * 2); true
        } catch (e: Exception) {
            // Storage full is the expected case. Log once per clip, not per block.
            Log.w(TAG, "clip write failed, abandoning this clip", e)
            false
        }
    }

    /** Rewrite the header with the real length, close, rename into place. Only after the
     *  rename does a caller learn the path, so a row can never reference a `.part`. */
    private fun finalizeClip(): Finished? {
        val r = raf ?: return null
        val part = partFile
        val fin = finalFile
        val ids = ArrayList(pendingIds)
        val rate = clipRate
        val samples = samplesWritten
        val startedAt = clipStartedAtMs
        raf = null; partFile = null; finalFile = null; pendingIds.clear()
        samplesWritten = 0; samplesSinceTrigger = 0

        if (part == null || fin == null) { runCatching { r.close() }; return null }
        try {
            r.seek(0)
            r.write(wavHeader(samples.toInt(), rate))
            r.close()
            if (!part.renameTo(fin)) {
                fin.delete()
                if (!part.renameTo(fin)) throw java.io.IOException("could not rename ${part.name}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "could not finalise ${part.name}", e)
            failedClips++
            runCatching { r.close() }
            runCatching { part.delete() }
            return null
        }
        clipsWritten++
        return Finished(fin.absolutePath, samples.toDouble() / rate, startedAt, ids)
    }

    /** Throw the open clip away — used when a write fails mid-bout. The rows stay with a
     *  null clip_path, which is a documented state, rather than pointing at a truncated
     *  file that would play as a fragment and look like a successful recording (§2d). */
    private fun abort() {
        runCatching { raf?.close() }
        partFile?.let { runCatching { it.delete() } }
        raf = null; partFile = null; finalFile = null
        pendingIds.clear()
        samplesWritten = 0; samplesSinceTrigger = 0
        failedClips++
    }

    private fun wavHeader(sampleCount: Int, sampleRate: Int): ByteArray {
        val dataLen = sampleCount * 2
        val bb = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray()); bb.putInt(36 + dataLen); bb.put("WAVE".toByteArray())
        bb.put("fmt ".toByteArray()); bb.putInt(16); bb.putShort(1)
        bb.putShort(1); bb.putInt(sampleRate); bb.putInt(sampleRate * 2)
        bb.putShort(2); bb.putShort(16)
        bb.put("data".toByteArray()); bb.putInt(dataLen)
        return bb.array()
    }
}
