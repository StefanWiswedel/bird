package dk.biomon.station

import java.util.concurrent.atomic.AtomicLong
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin

/**
 * PCM in, display-ready spectrogram columns out. Feeds the /api/spectrogram WebSocket.
 *
 * WHY THIS EXISTS AT ALL: the dashboard used to build its own spectrogram in JS from
 * /api/live-audio — a 3 s WAV polled every hop. That has three faults this class fixes,
 * and each one is the reason for a specific decision below.
 *
 *  1. Consecutive inference windows OVERLAP by 50% (WINDOW_S 3.0, HOP_S 1.5). Drawing
 *     every window drew half the audio twice: the display tore and jittered. So this
 *     class is fed by AudioCapture's *continuous* tap — every sample exactly once, in
 *     order — and never by Window objects.
 *  2. The old view ran linear to 22 kHz, so half the height showed 11–22 kHz, where a
 *     Copenhagen balcony has essentially nothing. 256 log-spaced bins over 200 Hz–12 kHz
 *     put the resolution where birds actually are.
 *  3. It normalised each frame against that frame's own min/max. Per-frame normalisation
 *     means a quiet frame gets its noise floor stretched to full brightness — which is
 *     exactly why the strip was a solid purple wash of traffic rumble. We do NOT do that.
 *     Instead we hold a rolling 30 s history per bin and subtract that bin's 20th
 *     percentile: a steady sound (traffic, wind, the fridge two floors down) IS its own
 *     20th percentile and subtracts to zero. Only what is louder than the recent past
 *     survives, which is the definition of the thing we want to look at.
 *
 * COST: the whole chain runs on the capture thread (see StationService.onPcm). One
 * 2048-point FFT per column, 30 columns/s. See PERCENTILE_REFRESH_FRAMES for the one
 * place where a naive implementation would have been too expensive for a OnePlus 5T.
 *
 * @param sampleRate the *capture* rate, not necessarily 48 kHz. AudioCapture falls back
 *        through 44.1/32/22.05/16 kHz if the hardware refuses 48, and resamples only the
 *        inference path. The continuous tap is raw, so every frequency mapping here is
 *        derived from this rate rather than assuming 48 kHz.
 */
class Spectrogram(
    val sampleRate: Int,
    private val onColumn: (atSeconds: Double, magnitudes: ByteArray) -> Unit
) {
    companion object {
        const val FFT_SIZE = 2048

        /** Columns per second. THIS IS THE TUNING KNOB. If the spectrogram ever costs too
         *  much on the station, lower this — never BINS. A scroll at 20 fps still reads as
         *  smooth; losing frequency resolution loses the ability to tell two calls apart,
         *  which is the entire point of showing a spectrogram rather than a VU meter. */
        const val COLUMNS_PER_SECOND = 30

        /** Exactly 256 bins, 200 Hz to 12 kHz, log-spaced. Below 200 Hz is traffic and
         *  wind; above 12 kHz nothing here sings. Log spacing because bird song is
         *  perceived and structured multiplicatively — a linear axis spends most of its
         *  pixels on the top octave, which carries the least. */
        const val BINS = 256
        const val F_MIN_HZ = 200.0
        const val F_MAX_HZ = 12000.0

        /** 30 s of history per bin for the noise-floor estimate. Long enough that a bird
         *  singing continuously for several seconds does not raise its own floor and erase
         *  itself; short enough that the display adapts when a car pulls up. */
        const val HISTORY_SECONDS = 30
        const val NOISE_PERCENTILE = 0.20

        /** Dynamic range above the noise floor, in dB, mapped onto 0–255. Fixed, not
         *  per-frame — a fixed window is what makes "brighter" mean "louder" across the
         *  whole strip instead of only within one column. */
        const val DB_WINDOW = 35.0

        // Histogram domain for the percentile estimator. Magnitudes are normalised to
        // full-scale = 0 dB, so real content sits roughly in [-120, 0]; the tails exist
        // only so nothing ever falls outside the array.
        private const val DB_FLOOR = -140
        private const val DB_CEIL = 10
        private const val HIST_BUCKETS = DB_CEIL - DB_FLOOR      // 1 dB buckets, 150 of them

        /** THE COST DECISION. A true 20th percentile of 900 frames x 256 bins, recomputed
         *  every frame by sorting, is ~230k elements sorted 30x/second — nowhere near
         *  affordable on a 2017 Snapdragon 835 that is already spending 168 ms per 1.5 s
         *  on BirdNET. Two things make it cheap instead:
         *
         *    a) a per-bin 1 dB histogram maintained INCREMENTALLY — each new frame
         *       decrements the bucket of the frame falling out of the 30 s window and
         *       increments the bucket of the frame coming in. That is 2 array writes per
         *       bin per frame, i.e. ~15k writes/second total, regardless of history depth.
         *    b) the percentile itself (a cumulative walk over 150 buckets x 256 bins) is
         *       only recomputed every 15 frames — twice a second. A noise floor that moved
         *       meaningfully in 33 ms would not be a noise floor.
         *
         *  Between recomputes the applied floor glides toward the new target (see
         *  floorGlide) rather than stepping to it. A step every 15 frames would draw a
         *  visible vertical seam twice a second across the whole strip; the eye finds that
         *  instantly, which is worse than the small lag the glide introduces. */
        const val PERCENTILE_REFRESH_FRAMES = 15
    }

    /** Hop between columns, in samples at the *capture* rate. 1600 at 48 kHz => 30 cols/s. */
    private val hop = (sampleRate / COLUMNS_PER_SECOND).coerceAtLeast(1)
    private val historyFrames = HISTORY_SECONDS * COLUMNS_PER_SECOND      // 900

    // ---------------------------------------------------------------- public counters
    /** Columns handed to [onColumn] since start. Reported by /api/health. */
    val framesEmitted = AtomicLong(0)

    /** Columns per second measured against the WALL clock, not against the sample counter
     *  — a rate derived from samples would read a perfect 30.0 even while the capture
     *  thread was stalling, which is precisely the failure this number exists to catch. */
    @Volatile var columnsPerSecond: Double = 0.0; private set
    private var rateWindowStartMs = 0L
    private var rateWindowCount = 0

    // ---------------------------------------------------------------- FFT tables
    private val hann = FloatArray(FFT_SIZE) {
        (0.5 - 0.5 * cos(2.0 * Math.PI * it / (FFT_SIZE - 1))).toFloat()
    }
    private val bitRev = IntArray(FFT_SIZE).also { table ->
        val bits = Integer.numberOfTrailingZeros(FFT_SIZE)
        for (i in 0 until FFT_SIZE) {
            var v = i; var r = 0
            for (b in 0 until bits) { r = (r shl 1) or (v and 1); v = v shr 1 }
            table[i] = r
        }
    }
    private val cosTab = FloatArray(FFT_SIZE / 2) { cos(-2.0 * Math.PI * it / FFT_SIZE).toFloat() }
    private val sinTab = FloatArray(FFT_SIZE / 2) { sin(-2.0 * Math.PI * it / FFT_SIZE).toFloat() }
    private val re = FloatArray(FFT_SIZE)
    private val im = FloatArray(FFT_SIZE)

    // Log-spaced bin edges, as inclusive FFT-bin index ranges. At 48 kHz one FFT bin is
    // 23.4 Hz wide, so the bottom display bins are NARROWER than one FFT bin and several
    // of them necessarily share an index — that is honest (the resolution genuinely is not
    // there) and still better than a linear axis, which would give the low end fewer
    // pixels rather than merely redundant ones. Within a range we take the MAX, not the
    // mean: a bird call is a narrow peak, and averaging it against the silence beside it
    // is a low-pass filter applied to exactly the thing being looked for.
    private val binLo = IntArray(BINS)
    private val binHi = IntArray(BINS)

    // ---------------------------------------------------------------- rolling state
    private val ring = FloatArray(FFT_SIZE)   // last FFT_SIZE samples, circular
    private var ringPos = 0
    private var ringFilled = 0
    private var sinceLastColumn = 0

    private val hist = ShortArray(BINS * HIST_BUCKETS)          // counts, max 900 fits a Short
    private val histRing = ByteArray(historyFrames * BINS)      // bucket index per frame per bin
    private var histPos = 0
    private var histCount = 0
    private var framesSinceRefresh = PERCENTILE_REFRESH_FRAMES  // force a refresh on frame 1

    private val floorTargetDb = FloatArray(BINS) { DB_FLOOR.toFloat() }
    private val floorDb = FloatArray(BINS) { DB_FLOOR.toFloat() }
    private var floorInitialised = false

    /** Fraction of the remaining gap closed per frame, sized so the applied floor covers
     *  most of the distance to a new target within one refresh interval. */
    private val floorGlide = 1f / PERCENTILE_REFRESH_FRAMES

    private val column = ByteArray(BINS)

    // Wall-clock time of sample index 0 of the stream. Column timestamps are derived from
    // the SAMPLE counter against this anchor, not from the clock at emit time: BirdNET
    // runs synchronously on the same thread, so reads arrive in bursts after each ~170 ms
    // inference and clock-at-emit timestamps would show that stall as jitter in a display
    // whose whole job is to look continuous. The anchor is only re-set if it drifts
    // grossly (below), which covers a genuine capture gap without chasing normal jitter.
    private var anchorMs = 0L
    private var totalSamples = 0L

    init {
        val edges = DoubleArray(BINS + 1) {
            F_MIN_HZ * (F_MAX_HZ / F_MIN_HZ).pow(it.toDouble() / BINS)
        }
        val maxIdx = FFT_SIZE / 2 - 1
        for (k in 0 until BINS) {
            val lo = (edges[k] * FFT_SIZE / sampleRate).toInt().coerceIn(1, maxIdx)
            val hi = (edges[k + 1] * FFT_SIZE / sampleRate).toInt().coerceIn(lo, maxIdx)
            binLo[k] = lo
            binHi[k] = hi
        }
    }

    /**
     * Consume a chunk of the continuous capture stream. [n] samples from [buf], which the
     * caller is free to reuse the moment this returns — everything needed is copied into
     * the internal ring here. [capturedAtMs] is the wall clock at the end of this chunk.
     *
     * Called on the capture thread. Must not block: it shares that thread with inference.
     */
    fun accept(buf: ShortArray, n: Int, capturedAtMs: Long) {
        if (n <= 0) return
        if (anchorMs == 0L) {
            anchorMs = capturedAtMs - (n * 1000L / sampleRate)
            rateWindowStartMs = capturedAtMs
        } else {
            // Re-anchor only on gross drift (>1 s), which means real lost audio — a device
            // sleep, a capture restart. Correcting small drift every chunk would re-import
            // the very jitter the sample clock exists to remove.
            val predicted = anchorMs + (totalSamples + n) * 1000L / sampleRate
            val drift = capturedAtMs - predicted
            if (drift > 1000L || drift < -1000L) anchorMs += drift
        }

        for (i in 0 until n) {
            ring[ringPos] = buf[i] / 32768f
            ringPos++; if (ringPos == FFT_SIZE) ringPos = 0
            if (ringFilled < FFT_SIZE) ringFilled++
            totalSamples++
            sinceLastColumn++
            if (ringFilled == FFT_SIZE && sinceLastColumn >= hop) {
                sinceLastColumn = 0
                emitColumn(capturedAtMs)
            }
        }
    }

    private fun emitColumn(capturedAtMs: Long) {
        var idx = ringPos   // ringPos is the oldest sample once the ring is full
        for (i in 0 until FFT_SIZE) {
            re[i] = ring[idx] * hann[i]
            im[i] = 0f
            idx++; if (idx == FFT_SIZE) idx = 0
        }
        fft()

        val base = histPos * BINS
        val dropping = histCount == historyFrames
        val scale = 2f / FFT_SIZE   // full-scale sine -> magnitude 1.0 -> 0 dB

        for (k in 0 until BINS) {
            var peak = 0f
            for (j in binLo[k]..binHi[k]) {
                val m = re[j] * re[j] + im[j] * im[j]
                if (m > peak) peak = m
            }
            // 20*log10(|X|) == 10*log10(|X|^2); squaring stays inside the loop above and the
            // sqrt never happens. Same number, one transcendental call per bin instead of two.
            val db = (10.0 * log10((peak.toDouble() * scale * scale) + 1e-14))
                .coerceIn(DB_FLOOR.toDouble(), (DB_CEIL - 1).toDouble())

            val bucket = (db - DB_FLOOR).toInt().coerceIn(0, HIST_BUCKETS - 1)
            val hBase = k * HIST_BUCKETS
            if (dropping) {
                val old = histRing[base + k].toInt() and 0xFF
                hist[hBase + old]--
            }
            histRing[base + k] = bucket.toByte()
            hist[hBase + bucket]++

            val f = floorDb[k]
            val above = (db - f) / DB_WINDOW
            column[k] = when {
                above <= 0.0 -> 0
                above >= 1.0 -> 255.toByte()
                else -> (above * 255.0).toInt().toByte()
            }
        }

        histPos++; if (histPos == historyFrames) histPos = 0
        if (histCount < historyFrames) histCount++

        framesSinceRefresh++
        if (framesSinceRefresh >= PERCENTILE_REFRESH_FRAMES) {
            framesSinceRefresh = 0
            refreshNoiseFloor()
        }
        if (floorInitialised) {
            for (k in 0 until BINS) floorDb[k] += (floorTargetDb[k] - floorDb[k]) * floorGlide
        }

        val atSeconds = anchorMs / 1000.0 + totalSamples.toDouble() / sampleRate
        framesEmitted.incrementAndGet()
        rateWindowCount++
        val elapsed = capturedAtMs - rateWindowStartMs
        if (elapsed >= 5000L) {
            columnsPerSecond = Math.round(rateWindowCount * 1000.0 / elapsed * 10.0) / 10.0
            rateWindowStartMs = capturedAtMs
            rateWindowCount = 0
        }
        onColumn(atSeconds, column.copyOf())
    }

    /** Cumulative walk over each bin's histogram to find the [NOISE_PERCENTILE] bucket.
     *  150 buckets x 256 bins, twice a second — see PERCENTILE_REFRESH_FRAMES. */
    private fun refreshNoiseFloor() {
        val target = (histCount * NOISE_PERCENTILE).toInt().coerceAtLeast(1)
        for (k in 0 until BINS) {
            val hBase = k * HIST_BUCKETS
            var cum = 0
            var bucket = 0
            for (b in 0 until HIST_BUCKETS) {
                cum += hist[hBase + b].toInt()
                if (cum >= target) { bucket = b; break }
            }
            floorTargetDb[k] = (DB_FLOOR + bucket + 0.5).toFloat()
        }
        if (!floorInitialised) {
            // First estimate lands after ~0.5 s. Jump straight to it rather than gliding up
            // from -140 dB, which would render the opening second as a solid white block.
            System.arraycopy(floorTargetDb, 0, floorDb, 0, BINS)
            floorInitialised = true
        }
    }

    /** In-place iterative radix-2 Cooley–Tukey over [re]/[im]. Hand-written because the
     *  module takes no new dependencies (build.gradle.kts) and a 2048-point FFT is forty
     *  lines. Tables (bit reversal, twiddles) are precomputed once in the constructor;
     *  per column this is ~11k butterflies, well under a millisecond on this hardware. */
    private fun fft() {
        for (i in 0 until FFT_SIZE) {
            val j = bitRev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= FFT_SIZE) {
            val half = len shr 1
            val step = FFT_SIZE / len
            var i = 0
            while (i < FFT_SIZE) {
                var k = 0
                for (j in i until i + half) {
                    val c = cosTab[k]; val s = sinTab[k]
                    val pr = re[j + half]; val pi = im[j + half]
                    val vr = pr * c - pi * s
                    val vi = pr * s + pi * c
                    val ur = re[j]; val ui = im[j]
                    re[j] = ur + vr; im[j] = ui + vi
                    re[j + half] = ur - vr; im[j + half] = ui - vi
                    k += step
                }
                i += len
            }
            len = len shl 1
        }
    }
}
