package dk.biomon.station

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * BirdNET's location/week meta-model — expected local occurrence per species, for a
 * lat/lon and one of 48 weeks. This is the replacement for the regional checklist gate.
 *
 * WHY IT REPLACES THAT GATE. `regional` was set membership against DOF's Danish list,
 * which is a NATIONAL checklist including every vagrant ever recorded — 455 of BirdNET's
 * 6522 classes. It answered "has this ever occurred in Denmark", when the question is
 * "how likely is this here, this week". Measured consequence: it deleted the obviously
 * absurd classes (a Compact Weaver in Copenhagen) while happily passing Dark-eyed Junco,
 * Sandhill Crane and White-throated Sparrow — i.e. it removed the tell that would have
 * told an observer the model was guessing, without removing the error. A probability
 * degrades gracefully where set membership cannot: a rarity gets a low prior and a raised
 * bar, not deletion, which is what DESIGN §4 requires ("never drop a class, down-weight it").
 *
 * INPUT/OUTPUT read from birdnetlib's own `species.py`, not guessed:
 *   - input:  float32 [1, 3] = [lat, lon, week_48]
 *   - output: float32 [1, 6522], RAW — birdnetlib applies no sigmoid here, unlike the
 *     acoustic path. Values are compared directly against a threshold (BirdNET's own
 *     default location filter is 0.03).
 *   - index i of the output is line i of the SAME label file the acoustic model uses, so
 *     `station_species.json`'s index map applies unchanged. That equality is the whole
 *     reason this is cheap to add.
 *
 * NOT BUNDLED: like the acoustic model, the 14.8 MB file is pushed to the app's external
 * files dir rather than shipped in the APK. It is optional — a station without it simply
 * has no priors, reports `priors_available: false`, and shows no rarity flags rather than
 * showing false ones.
 */
class MetaModel private constructor(private val interpreter: Interpreter, private val nClasses: Int) {

    companion object {
        const val MODEL_FILE = "BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite"
        const val WEEKS = 48

        fun modelFile(ctx: Context): File = File(ctx.getExternalFilesDir("models"), MODEL_FILE)

        fun isPresent(ctx: Context): Boolean =
            modelFile(ctx).let { it.exists() && it.length() > 1_000_000L }

        fun load(ctx: Context): MetaModel? {
            if (!isPresent(ctx)) return null
            return runCatching {
                val f = modelFile(ctx)
                val buf: MappedByteBuffer = FileInputStream(f).use {
                    it.channel.map(FileChannel.MapMode.READ_ONLY, 0, f.length())
                }
                val interp = Interpreter(buf, Interpreter.Options().apply { setNumThreads(1) })
                interp.allocateTensors()
                val n = interp.getOutputTensor(0).shape().last()
                // Same refusal as BirdNet.load: a width mismatch would silently attribute
                // every prior to the wrong species, which is DESIGN §10f's retracted bug.
                if (n != BirdNet.N_CLASSES_EXPECTED) { interp.close(); null }
                else MetaModel(interp, n)
            }.getOrNull()
        }
    }

    /** Occurrence for every class in one week. Index i matches the acoustic model's index i. */
    fun weekPriors(lat: Double, lon: Double, week: Int): FloatArray {
        val input = arrayOf(floatArrayOf(lat.toFloat(), lon.toFloat(), week.toFloat()))
        val output = Array(1) { FloatArray(nClasses) }
        interpreter.run(input, output)
        return output[0]
    }

    fun close() = interpreter.close()
}

/**
 * Runs the meta-model for all 48 weeks at the station's coordinates and fills
 * `species_prior`. One-time and cheap — 48 inferences of a tiny model, against the
 * acoustic model's 174 ms per 3-second window.
 *
 * Only non-zero priors are stored. A full 48 x 6522 table would be 313k rows to say
 * "essentially zero" about a Cape White-eye in Copenhagen 48 times over; absent means
 * "no local expectation", which is exactly what a missing row already means to
 * Database.prior().
 */
object PriorBuilder {

    fun buildIfNeeded(ctx: Context, db: Database, speciesTable: StationSpecies,
                      lat: Double, lon: Double): String {
        if (db.priorCount() > 0) return "priors already present (${db.priorCount()} rows)"
        val model = MetaModel.load(ctx)
            ?: return "meta-model absent — push ${MetaModel.MODEL_FILE} to " +
                      "${MetaModel.modelFile(ctx).parent} to enable rarity flags"
        return try {
            val entries = ArrayList<Triple<String, Int, Double>>(20_000)
            for (week in 1..MetaModel.WEEKS) {
                val p = model.weekPriors(lat, lon, week)
                for (i in p.indices) {
                    if (p[i] <= 0f) continue
                    val sci = speciesTable.byIndex[i]?.scientific ?: continue
                    entries += Triple(sci, week, p[i].toDouble())
                }
            }
            db.replacePriors(entries)
            "built ${entries.size} prior rows over ${MetaModel.WEEKS} weeks at $lat,$lon"
        } catch (e: Exception) {
            "meta-model failed: ${e.message}"
        } finally {
            model.close()
        }
    }
}
