package dk.biomon.station

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Forked from recorder/app's `AudioEngine.kt` (dk.biomon.recorder.AudioEngine) — same
 * UNPROCESSED-source / disabled-AGC-NS-AEC capture chain, forked rather than shared
 * because :station and :app are deliberately separate modules (see build.gradle.kts).
 *
 * BirdNET's analyzer was trained on 48 kHz mono float32 in [-1, 1] (birdnetlib
 * `main.py`: `SAMPLE_RATE = 48000`), 3.0 s windows. This class asks for exactly that
 * rate; if the hardware refuses it (rare, but AudioRecord.getMinBufferSize can fail
 * for a requested rate), it falls back through the same preference list the recorder
 * app uses and linearly resamples to 48 kHz before it ever reaches the model — BirdNET
 * has no fallback for "wrong sample rate", it just quietly mis-scores everything.
 */
class AudioCapture(private val ctx: Context) {

    companion object {
        const val MODEL_RATE = 48000
        const val WINDOW_S = 3.0
        const val HOP_S = 1.5
        const val WINDOW_SAMPLES = (MODEL_RATE * WINDOW_S).toInt()   // 144_000
        const val HOP_SAMPLES = (MODEL_RATE * HOP_S).toInt()          // 72_000
        private val PREFERRED_RATES = intArrayOf(48000, 44100, 32000, 22050, 16000)
    }

    data class Window(val startAtMs: Long, val pcm16: ShortArray, val samples: FloatArray)
    data class Status(val sampleRate: Int, val sourceName: String, val unprocessedSupported: Boolean,
                      val effectsDisabled: List<String>)

    @Volatile var status: Status? = null; private set
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null

    private fun unprocessedSupported(): Boolean {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    }

    private fun bestSampleRate(): Int {
        for (rate in PREFERRED_RATES) {
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min > 0) return rate
        }
        return 44100
    }

    private fun disableEffects(sessionId: Int): List<String> {
        val off = mutableListOf<String>()
        runCatching {
            if (AutomaticGainControl.isAvailable())
                AutomaticGainControl.create(sessionId)?.let { it.setEnabled(false); off += "AGC" }
        }
        runCatching {
            if (NoiseSuppressor.isAvailable())
                NoiseSuppressor.create(sessionId)?.let { it.setEnabled(false); off += "NS" }
        }
        runCatching {
            if (AcousticEchoCanceler.isAvailable())
                AcousticEchoCanceler.create(sessionId)?.let { it.setEnabled(false); off += "AEC" }
        }
        return off
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Starts a dedicated capture thread. [onWindow] is called on that thread — the
     *  caller (StationService) does inference synchronously there; BirdNET's ~100-200 ms
     *  per window is well inside the 1.5 s hop budget, so a second thread would only
     *  add complexity for no latency win. */
    fun start(onWindow: (Window) -> Unit, onError: (Throwable) -> Unit) {
        if (running.getAndSet(true)) return
        thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            try { captureLoop(onWindow) } catch (e: Throwable) { running.set(false); onError(e) }
        }, "biomon-audio-capture").apply { start() }
    }

    fun stop() {
        running.set(false)
        thread?.join(2000)
        thread = null
    }

    private fun captureLoop(onWindow: (Window) -> Unit) {
        val supported = unprocessedSupported()
        val source = if (supported) MediaRecorder.AudioSource.UNPROCESSED
                     else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val captureRate = bestSampleRate()
        val minBuf = AudioRecord.getMinBufferSize(captureRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            .let { if (it > 0) it else 4096 }
        // A few hundred ms of headroom so a scheduling hiccup does not overrun the ring.
        val bufBytes = minBuf * 4

        @Suppress("MissingPermission")
        val rec = AudioRecord(source, captureRate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, bufBytes)
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("AudioRecord failed to initialize (source=$source rate=$captureRate)")
        }
        val effectsOff = disableEffects(rec.audioSessionId)
        status = Status(captureRate, if (supported) "UNPROCESSED" else "VOICE_RECOGNITION", supported, effectsOff)

        val needsResample = captureRate != MODEL_RATE
        // Ring holds raw captured samples at captureRate; a window is cut, resampled if
        // needed, and handed off every HOP worth of *model-rate* time.
        val hopAtCaptureRate = (HOP_SAMPLES.toLong() * captureRate / MODEL_RATE).toInt()
        val windowAtCaptureRate = (WINDOW_SAMPLES.toLong() * captureRate / MODEL_RATE).toInt()
        val ring = ShortArray(windowAtCaptureRate * 2)
        var ringFill = 0
        var sinceLastWindow = 0
        val readBuf = ShortArray(minBuf / 2)

        rec.startRecording()
        try {
            while (running.get()) {
                val n = rec.read(readBuf, 0, readBuf.size)
                if (n <= 0) continue
                // shift-append into the ring (simple, adequate at this data rate: ~96 KB/s)
                if (ringFill + n > ring.size) {
                    val drop = ringFill + n - ring.size
                    System.arraycopy(ring, drop, ring, 0, ringFill - drop)
                    ringFill -= drop
                }
                System.arraycopy(readBuf, 0, ring, ringFill, n)
                ringFill += n
                sinceLastWindow += n

                if (ringFill >= windowAtCaptureRate && sinceLastWindow >= hopAtCaptureRate) {
                    sinceLastWindow = 0
                    val startOffset = ringFill - windowAtCaptureRate
                    val raw = ring.copyOfRange(startOffset, startOffset + windowAtCaptureRate)
                    val pcm16 = if (needsResample) resample(raw, captureRate, MODEL_RATE) else raw
                    val samples = FloatArray(pcm16.size) { pcm16[it] / 32768f }
                    onWindow(Window(System.currentTimeMillis() - (WINDOW_S * 1000).toLong(), pcm16, samples))
                }
            }
        } finally {
            runCatching { rec.stop() }
            rec.release()
        }
    }

    /** Linear resample — adequate for a detector, not claimed to be broadcast quality.
     *  Only exercised on hardware that refuses 48 kHz UNPROCESSED capture. */
    private fun resample(src: ShortArray, fromRate: Int, toRate: Int): ShortArray {
        val outLen = (src.size.toLong() * toRate / fromRate).toInt()
        val out = ShortArray(outLen)
        val ratio = fromRate.toDouble() / toRate
        for (i in out.indices) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceIn(0, src.size - 1)
            val i1 = (i0 + 1).coerceAtMost(src.size - 1)
            val frac = pos - i0
            out[i] = (src[i0] * (1 - frac) + src[i1] * frac).toInt().toShort()
        }
        return out
    }
}
