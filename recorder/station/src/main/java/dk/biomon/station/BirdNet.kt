package dk.biomon.station

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * The BirdNET GLOBAL_6K_V2.4 acoustic model, run directly with TFLite's Java API —
 * no birdnetlib, no Python, on-device only. Input/output shapes and the logit->score
 * conversion are read from `analysis/.venv-bn/…/birdnetlib/analyzer.py::predict()`
 * (the desktop pipeline this project already trusts), not guessed:
 *   - input:  float32 [1, 144000]  (3.0 s @ 48 kHz, [-1, 1])
 *   - output: float32 [1, 6522]    (raw logits)
 *   - score:  standard sigmoid, clipped to [-15, 15] before exponentiating
 *     (birdnetlib's `flat_sigmoid(x, sensitivity=-1)` == this, at its default sensitivity)
 *
 * NOT BUNDLED IN THE APK (build.gradle.kts noCompress note): the FP32 model is ~52 MB
 * and this project already has it on the desktop machine (it is what the BirdNET-Live
 * desktop pipeline uses) — `adb push`ing the SAME file over USB is simpler and more
 * honest than re-downloading it from somewhere, and needs no network dependency on
 * the station at all.
 */
class BirdNet private constructor(private val interpreter: Interpreter, val nClasses: Int) {

    companion object {
        const val MODEL_FILE = "BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite"
        const val N_CLASSES_EXPECTED = 6522

        fun modelFile(ctx: Context): File =
            File(ctx.getExternalFilesDir("models"), MODEL_FILE)

        fun isModelPresent(ctx: Context): Boolean =
            modelFile(ctx).let { it.exists() && it.length() > 10_000_000L }

        /** Throws with a clear message rather than a bare TFLite native crash if the
         *  model was never pushed — this is the single most likely first-run failure. */
        fun load(ctx: Context): BirdNet {
            val f = modelFile(ctx)
            if (!isModelPresent(ctx)) {
                throw IllegalStateException(
                    "BirdNET model missing at ${f.absolutePath} — " +
                    "adb push it into the app's external files/models dir before starting the service.")
            }
            val buffer: MappedByteBuffer = FileInputStream(f).use { fis ->
                fis.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
            }
            val opts = Interpreter.Options().apply { setNumThreads(4) }
            val interp = Interpreter(buffer, opts)
            interp.resizeInput(0, intArrayOf(1, AudioCapture.WINDOW_SAMPLES))
            interp.allocateTensors()
            val outShape = interp.getOutputTensor(0).shape()
            val classes = outShape.last()
            if (classes != N_CLASSES_EXPECTED) {
                interp.close()
                throw IllegalStateException(
                    "model output width is $classes, expected $N_CLASSES_EXPECTED — " +
                    "station_species.json's index map would be silently wrong (DESIGN §10f); refusing to run")
            }
            return BirdNet(interp, classes)
        }
    }

    /** samples.size MUST be AudioCapture.WINDOW_SAMPLES. Returns nClasses sigmoid scores. */
    @Synchronized
    fun predict(samples: FloatArray): FloatArray {
        require(samples.size == AudioCapture.WINDOW_SAMPLES) {
            "expected ${AudioCapture.WINDOW_SAMPLES} samples, got ${samples.size}"
        }
        val input = arrayOf(samples)
        val output = Array(1) { FloatArray(nClasses) }
        interpreter.run(input, output)
        val logits = output[0]
        val scores = FloatArray(nClasses)
        for (i in logits.indices) {
            val clipped = logits[i].coerceIn(-15f, 15f)
            scores[i] = (1f / (1f + Math.exp(-clipped.toDouble()))).toFloat()
        }
        return scores
    }

    fun close() = interpreter.close()
}
