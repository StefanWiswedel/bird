package dk.biomon.station

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * A known-good recording of each species, cached on the phone, to play beside the clip.
 *
 * WHY: the station's user can tell a bird from a bike brake and cannot name species by
 * ear. "Name this bird" is unanswerable for him; **"does this sound like that?"** is
 * answerable by anybody, and it teaches by repetition. Two play buttons turn identification
 * into comparison, which is the entire point of PR 4.
 *
 * Forked in shape from [PhotoCache], which solves nearly the same problem — fetch one
 * artefact per species from a public archive, cache it on disk, and never confuse a
 * transport failure with an absent artefact. The same two lessons apply here.
 *
 * FOUR STATES, ALL DISTINGUISHABLE (§2d). This is the part that matters:
 *  - `no_key`  — no xeno-canto key is configured. The station ships this way. It is not a
 *                failure and not an absence of recordings; nothing was even attempted.
 *  - `ready`   — a recording is cached and playable.
 *  - `none`    — the archive answered and has nothing matching for this species and region.
 *  - `failed`  — the lookup itself failed (no network, HTTP error, bad response). Says why.
 * Collapsing any of these into "no reference audio" would be the exact mistake §2d is
 * about, and here it would quietly look like the archive has no Blackbird recordings.
 */
object ReferenceAudio {

    private const val TAG = "ReferenceAudio"
    private const val TIMEOUT_MS = 15_000
    private const val UA = "biomon-station (+https://github.com/StefanWiswedel/bird)"
    private const val MAX_BYTES = 12 * 1024 * 1024      // a few minutes of mp3; not a stream

    /**
     * Where to look, in order. Dialects vary geographically and the reference should match
     * the population outside this window — a Danish Chaffinch and a Spanish one do not
     * sound the same, and a reference that does not match is worse than none because it
     * teaches the wrong comparison. Denmark first, then its acoustic neighbours.
     */
    private val COUNTRIES = listOf("Denmark", "Sweden", "Germany", "Norway", "Netherlands", "Poland")

    /** Quality grades accepted, best first. xeno-canto grades A (best) to E. */
    private val QUALITIES = listOf("A", "B")

    /** Re-ask the archive about a species with no recording only this often. Without it a
     *  species with genuinely nothing available would re-query on every screen open. */
    private const val NEGATIVE_TTL_MS = 7L * 24 * 3600 * 1000

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "reference_audio").apply { mkdirs() }
    private fun audioFile(ctx: Context, key: String) = File(dir(ctx), "$key.mp3")
    private fun metaFile(ctx: Context, key: String) = File(dir(ctx), "$key.json")

    fun cachedAudio(ctx: Context, key: String): File? =
        audioFile(ctx, key).takeIf { it.isFile && it.length() > 0 }

    private fun readMeta(ctx: Context, key: String): JSONObject? =
        runCatching { JSONObject(metaFile(ctx, key).readText()) }.getOrNull()

    /**
     * Status for one species, fetching once if nothing is cached yet.
     *
     * Called from the request thread of `GET /api/reference` — user-initiated, one species
     * at a time, with timeouts. Deliberately NOT a background sweep over the whole species
     * list: that would spend the phone's radio and the archive's rate limit on species
     * nobody is about to verify.
     */
    fun statusFor(ctx: Context, taxon: Taxon, apiKey: String, allowFetch: Boolean = true): JSONObject {
        val key = taxon.key
        val base = JSONObject().put("taxon_key", key).put("scientific", taxon.scientific)

        cachedAudio(ctx, key)?.let { f ->
            val meta = readMeta(ctx, key)
            return base.put("state", "ready")
                .put("url", "/api/reference/$key")
                .put("seconds", meta?.optDouble("seconds") ?: JSONObject.NULL)
                .put("recordist", meta?.optString("recordist") ?: "")
                .put("license", meta?.optString("license") ?: "")
                .put("country", meta?.optString("country") ?: "")
                .put("quality", meta?.optString("quality") ?: "")
                .put("source", meta?.optString("source") ?: "")
                .put("bytes", f.length())
        }

        // NO KEY IS NOT A FAILURE. The station ships without one; say so precisely, and
        // never imply the archive was asked and had nothing.
        if (apiKey.isBlank()) {
            return base.put("state", "no_key")
                // The "what to do about it" half is the client's link, not repeated here.
                .put("reason", "No xeno-canto API key is configured, so no reference recording " +
                     "has been looked up.")
        }

        val meta = readMeta(ctx, key)
        val lastMiss = meta?.optLong("no_match_at_ms", 0L) ?: 0L
        if (lastMiss > 0 && System.currentTimeMillis() - lastMiss < NEGATIVE_TTL_MS) {
            return base.put("state", "none")
                .put("reason", "xeno-canto has no ${QUALITIES.first()}/${QUALITIES.last()}-grade " +
                     "recording of this species from Denmark or its neighbours.")
                .put("checked_at_ms", lastMiss)
        }
        if (!allowFetch) {
            return base.put("state", "pending")
                .put("reason", "Not looked up yet.")
        }
        return fetch(ctx, taxon, apiKey, base)
    }

    /** Clears the cached recording for one species, so the next look-up starts fresh. */
    fun forget(ctx: Context, key: String) {
        runCatching { audioFile(ctx, key).delete() }
        runCatching { metaFile(ctx, key).delete() }
    }

    // ---------------------------------------------------------------- the fetch

    private fun fetch(ctx: Context, taxon: Taxon, apiKey: String, base: JSONObject): JSONObject {
        // Region and quality are part of the query, not a filter applied afterwards: the
        // archive can do the selection, and asking for less costs the phone less.
        for (country in COUNTRIES) {
            val q = buildString {
                append("sp:\"").append(taxon.scientific).append("\" ")
                append("cnt:\"").append(country).append("\" ")
                append("q:\"").append(QUALITIES.first()).append("\"")
            }
            val listed = try { query(q, apiKey) } catch (e: LookupFailure) {
                // A failed lookup stops the whole walk. Trying the next country would turn
                // one network error into six, and would then report "none" — which is a
                // different and untrue answer.
                return base.put("state", "failed").put("reason", e.message ?: "lookup failed")
            }
            val pick = listed.firstOrNull() ?: continue
            val bytes = try { download(pick.optString("file")) } catch (e: LookupFailure) {
                return base.put("state", "failed")
                    .put("reason", "Found a recording but could not download it: ${e.message}")
            }
            if (bytes == null || bytes.isEmpty()) {
                return base.put("state", "failed")
                    .put("reason", "xeno-canto returned an empty recording for ${taxon.scientific}.")
            }
            runCatching {
                audioFile(ctx, taxon.key).writeBytes(bytes)
                metaFile(ctx, taxon.key).writeText(JSONObject()
                    .put("recordist", pick.optString("rec"))
                    .put("license", pick.optString("lic"))
                    .put("country", pick.optString("cnt"))
                    .put("quality", pick.optString("q"))
                    .put("seconds", parseLength(pick.optString("length")))
                    .put("source", pick.optString("url"))
                    .put("fetched_at_ms", System.currentTimeMillis())
                    .toString())
            }.onFailure {
                return base.put("state", "failed")
                    .put("reason", "Downloaded a recording but could not store it: ${it.message}")
            }
            return statusFor(ctx, taxon, apiKey, allowFetch = false)
        }

        // Every country answered and none had anything. THIS is "none" — the archive was
        // asked and said no, which is a real answer and is cached so it is not re-asked
        // on every screen open.
        runCatching {
            metaFile(ctx, taxon.key).writeText(JSONObject()
                .put("no_match_at_ms", System.currentTimeMillis()).toString())
        }
        return base.put("state", "none")
            .put("reason", "xeno-canto has no ${QUALITIES.first()}-grade recording of " +
                 "${taxon.scientific} from Denmark or its neighbours.")
            .put("checked_at_ms", System.currentTimeMillis())
    }

    private class LookupFailure(message: String) : Exception(message)

    /** One xeno-canto API v3 search. Throws [LookupFailure] — never returns an empty list
     *  to mean "it went wrong", because the caller must tell those apart. */
    private fun query(queryText: String, apiKey: String): List<JSONObject> {
        val url = "https://xeno-canto.org/api/3/recordings" +
                  "?query=" + URLEncoder.encode(queryText, "UTF-8") +
                  "&key=" + URLEncoder.encode(apiKey, "UTF-8")
        var c: HttpURLConnection? = null
        try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "application/json")
            }
            val code = c.responseCode
            if (code == 401 || code == 403) {
                throw LookupFailure("xeno-canto rejected the API key (HTTP $code). Check it in Settings.")
            }
            if (code == 429) throw LookupFailure("xeno-canto is rate-limiting this key; try again later.")
            if (code != 200) throw LookupFailure("xeno-canto answered HTTP $code.")
            val body = c.inputStream.bufferedReader().use { it.readText() }
            val arr = runCatching { JSONObject(body).optJSONArray("recordings") }.getOrNull()
                ?: throw LookupFailure("xeno-canto returned a response this station could not read.")
            return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                .filter { it.optString("file").isNotBlank() }
        } catch (e: LookupFailure) {
            throw e
        } catch (e: java.net.UnknownHostException) {
            throw LookupFailure("Cannot resolve xeno-canto.org — the station has no DNS, so it is " +
                                "probably off the network.")
        } catch (e: Exception) {
            // The API key must never reach a log line, so the URL is not included here.
            Log.w(TAG, "reference lookup failed (${e.javaClass.simpleName})")
            throw LookupFailure("${e.javaClass.simpleName} talking to xeno-canto: ${e.message ?: "no detail"}")
        } finally { runCatching { c?.disconnect() } }
    }

    private fun download(fileUrl: String): ByteArray? {
        if (fileUrl.isBlank()) return null
        var c: HttpURLConnection? = null
        try {
            c = (URL(fileUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", UA)
            }
            if (c.responseCode != 200) throw LookupFailure("HTTP ${c.responseCode} downloading the recording")
            val acc = java.io.ByteArrayOutputStream(256 * 1024)
            val buf = ByteArray(16 * 1024)
            c.inputStream.use {
                while (true) {
                    val n = it.read(buf)
                    if (n < 0) break
                    if (acc.size() + n > MAX_BYTES) throw LookupFailure("recording exceeds ${MAX_BYTES / 1024 / 1024} MB")
                    acc.write(buf, 0, n)
                }
            }
            return acc.toByteArray()
        } catch (e: LookupFailure) {
            throw e
        } catch (e: Exception) {
            throw LookupFailure("${e.javaClass.simpleName}: ${e.message ?: "no detail"}")
        } finally { runCatching { c?.disconnect() } }
    }

    /** xeno-canto reports length as "m:ss". Null rather than 0 when it is unparseable. */
    private fun parseLength(s: String): Any {
        val parts = s.split(":")
        if (parts.size != 2) return JSONObject.NULL
        val m = parts[0].toIntOrNull() ?: return JSONObject.NULL
        val sec = parts[1].toIntOrNull() ?: return JSONObject.NULL
        return (m * 60 + sec).toDouble()
    }
}
