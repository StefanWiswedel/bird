package dk.biomon.station

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The dashboard's files, on the device rather than inside the APK.
 *
 * WHY THIS EXISTS. The station phone is bolted outside and is meant to be touched as
 * rarely as possible; there is no adb, no USB and no inbound route to it from anywhere.
 * Serving `www/index.html` straight out of `assets` meant every one-line UI change cost a
 * rebuild and a physical reinstall of a 20 MB APK — the dashboard's iteration speed was
 * pinned to the APK's. Files under `getExternalFilesDir("dashboard")` (the same pattern as
 * `models`, `clips` and `species_photos`) can instead be replaced in place by [update],
 * which the station PULLS over HTTPS. Pull, never push: the phone is behind NAT on a home
 * LAN, so nothing outside can reach it, but it can reach GitHub perfectly well.
 *
 * THE ASSETS STAY IN THE APK, as the first-run seed and as the recovery path. [read]
 * falls back to the bundled asset whenever the stored file is missing or unreadable, so
 * the worst a botched update can do is serve the dashboard that shipped with the build —
 * never no dashboard at all, which on a phone with no adb would be unrecoverable.
 */
object DashboardStore {

    private const val TAG = "DashboardStore"
    private const val TIMEOUT_MS = 15_000
    private const val UA = "biomon-station (+https://github.com/StefanWiswedel/bird)"

    /** A dashboard file is text; anything this large is not the dashboard, and reading it
     *  into memory on a 2017 phone that is also running BirdNET is not worth the risk. */
    private const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * @param marker a string the real file always contains. It is the difference between
     *   "we downloaded the dashboard" and "we downloaded something that was 200 OK" — a
     *   proxy sign-in page, a CDN error body, a `git-lfs` pointer. `id="panel-live"` is the
     *   live panel's root element and `:root` is where every token is defined; neither can
     *   go missing from a working file.
     */
    private class Asset(val name: String, val assetPath: String,
                        val contentType: String, val marker: String)

    private val FILES = listOf(
        Asset("index.html", "www/index.html", "text/html; charset=utf-8", "id=\"panel-live\""),
        Asset("tokens.css", "www/tokens.css", "text/css; charset=utf-8", ":root"),
    )

    /** Null when external storage is not mounted. Every caller treats that as "serve the
     *  bundled asset", not as an error — a station that cannot write is still a station. */
    fun dir(ctx: Context): File? = ctx.getExternalFilesDir("dashboard")?.apply { mkdirs() }

    fun contentType(name: String): String? = FILES.firstOrNull { it.name == name }?.contentType

    /**
     * Copy any missing file out of the APK. Called once at service start, so a fresh
     * install serves a dashboard on its first request without needing the internet, and so
     * a file deleted by hand (or by an update that only half-landed on some future device)
     * comes back on the next restart.
     */
    fun seed(ctx: Context) {
        val dir = dir(ctx) ?: return
        for (a in FILES) {
            val f = File(dir, a.name)
            if (f.isFile && f.length() > 0) continue
            runCatching {
                ctx.assets.open(a.assetPath).use { ins -> f.outputStream().use { ins.copyTo(it) } }
            }.onFailure { Log.w(TAG, "could not seed ${a.name} from assets", it) }
        }
    }

    /** Stored file if it is readable and non-empty, else the bundled asset, else null. */
    fun read(ctx: Context, name: String): ByteArray? {
        val a = FILES.firstOrNull { it.name == name } ?: return null
        val dir = dir(ctx)
        if (dir != null) {
            val f = File(dir, a.name)
            if (f.isFile && f.length() > 0) {
                val bytes = runCatching { f.readBytes() }
                    .onFailure { Log.w(TAG, "stored ${a.name} unreadable, falling back to asset", it) }
                    .getOrNull()
                if (bytes != null && bytes.isNotEmpty()) return bytes
            }
        }
        return runCatching { ctx.assets.open(a.assetPath).use { it.readBytes() } }.getOrNull()
    }

    /**
     * When the served dashboard was last written, or null if nothing is stored and the
     * bundled assets are what is being served.
     *
     * The build stamp alone stopped identifying the UI the moment the dashboard could
     * update independently of the APK — §11k's "is the phone running what I just built?"
     * now has two answers, and the header shows both.
     */
    fun updatedAtMs(ctx: Context): Long? {
        val dir = dir(ctx) ?: return null
        val stamps = FILES.mapNotNull { File(dir, it.name).takeIf { f -> f.isFile }?.lastModified() }
        return if (stamps.size == FILES.size) stamps.max() else null
    }

    /** The `dashboard` block of /api/health. */
    fun healthJson(ctx: Context): JSONObject {
        val at = updatedAtMs(ctx)
        return JSONObject()
            .put("stored", at != null)
            .put("updated_at_ms", at ?: JSONObject.NULL)
            .put("source", BuildConfig.DASHBOARD_SOURCE_URL)
    }

    // ------------------------------------------------------------------ self-update

    private class Fetch(val name: String, val url: String, val bytes: ByteArray?,
                        val error: String?, val reason: String?) {
        fun json(written: Boolean): JSONObject = JSONObject()
            .put("name", name)
            .put("ok", bytes != null)
            .put("bytes", bytes?.size ?: 0)
            .put("written", written)
            .put("error", error ?: JSONObject.NULL)
            .put("reason", reason ?: JSONObject.NULL)
    }

    /**
     * Fetch both files from the pinned source and, ONLY IF BOTH ARRIVE INTACT, write them.
     *
     * The order matters and is the whole point. Both files are downloaded fully into
     * memory, validated, staged as `.part` files, and only then renamed over the live
     * ones. Streaming straight to `index.html` would mean a dropped wifi association
     * halfway through leaves the station serving half a document, which is exactly the
     * failure this endpoint is supposed to make safe. A failure anywhere before the rename
     * writes nothing and the existing dashboard keeps serving.
     *
     * DESIGN.md §2d: every outcome below is named. "The station is offline", "GitHub
     * answered 404", and "what came back was not the dashboard" are three different
     * sentences, reported as three different `error` values, because they need three
     * different responses from the person holding the phone.
     */
    fun update(ctx: Context): JSONObject {
        val results = FILES.map { fetchOne(it) }
        val out = JSONObject()
            .put("schema", 1)
            .put("source", BuildConfig.DASHBOARD_SOURCE_URL)
            .put("checked_at_ms", System.currentTimeMillis())

        val failed = results.firstOrNull { it.bytes == null }
        if (failed != null) {
            return out.put("updated", false)
                .put("error", failed.error ?: "network").put("reason", failed.reason ?: "no detail")
                .put("files", jsonArray(results.map { it.json(written = false) }))
        }

        val dir = dir(ctx)
        if (dir == null) {
            return out.put("updated", false).put("error", "storage")
                .put("reason", "external storage is not mounted, so there is nowhere to write the dashboard")
                .put("files", jsonArray(results.map { it.json(written = false) }))
        }

        // Stage everything first, rename afterwards: renaming the first file and then
        // failing on the second would leave an index.html that its own tokens.css no
        // longer matches, which is a worse state than not having updated at all.
        val staged = ArrayList<Pair<File, File>>(results.size)
        try {
            for (r in results) {
                val part = File(dir, r.name + ".part")
                part.writeBytes(r.bytes!!)
                staged.add(part to File(dir, r.name))
            }
            for ((part, target) in staged) {
                // rename(2) replaces the target atomically. If the filesystem refuses,
                // fall back to deleting first — still a complete-or-throw write, since the
                // bytes are already on disk in `part`.
                if (!part.renameTo(target)) {
                    target.delete()
                    if (!part.renameTo(target)) throw java.io.IOException("could not replace ${target.name}")
                }
            }
        } catch (e: Exception) {
            staged.forEach { runCatching { it.first.delete() } }
            Log.w(TAG, "dashboard update failed while writing", e)
            return out.put("updated", false).put("error", "write")
                .put("reason", "downloaded ${results.sumOf { it.bytes!!.size }} bytes but could not " +
                     "write them: ${e.message ?: e.javaClass.simpleName}")
                .put("files", jsonArray(results.map { it.json(written = false) }))
        }

        return out.put("updated", true)
            .put("updated_at_ms", updatedAtMs(ctx) ?: System.currentTimeMillis())
            .put("files", jsonArray(results.map { it.json(written = true) }))
    }

    private fun jsonArray(items: List<JSONObject>) = JSONArray().also { arr -> items.forEach { arr.put(it) } }

    private fun fetchOne(a: Asset): Fetch {
        // The URL is BuildConfig.DASHBOARD_SOURCE_URL + the file name and nothing else.
        // See the endpoint's comment in HttpServer: it is compile-time pinned on purpose.
        val url = BuildConfig.DASHBOARD_SOURCE_URL + a.name
        val host = runCatching { URL(url).host }.getOrDefault("the update host")
        var c: HttpURLConnection? = null
        try {
            c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA)
                // raw.githubusercontent.com is CDN-fronted and will happily serve a stale
                // copy of a file committed a minute ago otherwise.
                setRequestProperty("Cache-Control", "no-cache")
            }
            val code = c.responseCode
            if (code != 200) {
                // The most likely 404 by far is a renamed path or a branch that does not
                // exist — say which URL, or the reader has to guess what was asked for.
                return Fetch(a.name, url, null, "http", "HTTP $code from $host for ${a.name}")
            }
            // -1 when the length is not knowable: chunked, or — the usual case here —
            // gzipped, since the HTTP stack decompresses transparently and drops the
            // header rather than reporting a length that no longer matches the body. A
            // truncated gzip stream still fails, as an IOException out of the read below;
            // this check catches the other kind, a clean stop short of a declared length.
            val declared = c.contentLengthLong
            val bytes = readCapped(c.inputStream)
                ?: return Fetch(a.name, url, null, "too_large",
                    "${a.name} exceeded ${MAX_BYTES / 1024} kB; that is not the dashboard")
            if (declared >= 0 && bytes.size.toLong() != declared) {
                // A short read that is not an exception: the connection dropped mid-body.
                // Silently accepting it is how you overwrite the dashboard with its first
                // half and discover it from a blank screen.
                return Fetch(a.name, url, null, "truncated",
                    "${a.name} stopped after ${bytes.size} of $declared bytes")
            }
            if (bytes.isEmpty()) {
                return Fetch(a.name, url, null, "validation", "${a.name} came back empty")
            }
            val text = String(bytes, Charsets.UTF_8)
            if (!text.contains(a.marker)) {
                return Fetch(a.name, url, null, "validation",
                    "${a.name} does not look like the dashboard (no ${a.marker} in ${bytes.size} bytes) " +
                    "— an error page, not the file")
            }
            return Fetch(a.name, url, bytes, null, null)
        } catch (e: java.net.UnknownHostException) {
            return Fetch(a.name, url, null, "network",
                "cannot resolve $host — no DNS answer, so the station is probably off the network")
        } catch (e: java.net.NoRouteToHostException) {
            return Fetch(a.name, url, null, "network", "no route to $host from the station")
        } catch (e: java.net.ConnectException) {
            return Fetch(a.name, url, null, "network",
                "cannot connect to $host: ${e.message ?: "connection refused"}")
        } catch (e: java.net.SocketTimeoutException) {
            return Fetch(a.name, url, null, "network",
                "$host did not answer within ${TIMEOUT_MS / 1000} s")
        } catch (e: javax.net.ssl.SSLException) {
            return Fetch(a.name, url, null, "tls",
                "TLS failure talking to $host: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: Exception) {
            Log.w(TAG, "fetch of ${a.name} failed", e)
            return Fetch(a.name, url, null, "network",
                "${e.javaClass.simpleName} fetching ${a.name}: ${e.message ?: "no detail"}")
        } finally {
            runCatching { c?.disconnect() }
        }
    }

    /** Null if the stream is longer than [MAX_BYTES] — checked while reading, not after. */
    private fun readCapped(ins: java.io.InputStream): ByteArray? = ins.use {
        val buf = ByteArray(16 * 1024)
        val acc = ByteArrayOutputStream(128 * 1024)
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            if (acc.size() + n > MAX_BYTES) return null
            acc.write(buf, 0, n)
        }
        acc.toByteArray()
    }
}
