package dk.biomon.station

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Forked from recorder/app's `SpeciesImageFetcher.kt` — same two lessons, unchanged:
 * BATCH (MediaWiki takes up to 50 titles per request, so ~11 requests covers this
 * project's whole species list instead of one per species) and NEVER report a
 * transport failure as "no photo" (a 429 and a genuinely photo-less species are not the
 * same fact, even though the read-side API only ever returns null for both — API.md
 * "photo may be null... not the same as no photo exists").
 *
 * Simplified for the station: no "not while recording" / wifi-only refusal, because the
 * station has no recording concept and is a wall-powered appliance rather than a phone
 * in someone's pocket on a data plan. It still refuses to run with no connectivity at
 * all, and still paces itself — Wikimedia's rate limit does not care which app is asking.
 */
object PhotoCache {
    private const val TAG = "PhotoCache"
    private const val UA = "biomon-station/0.1 (personal biodiversity monitoring)"
    private const val THUMB_PX = 480
    private const val TIMEOUT_MS = 20_000
    private const val BATCH = 50
    private const val PAUSE_MS = 350L
    private const val IMG_PAUSE_MS = 120L

    fun dir(ctx: Context): File = File(ctx.getExternalFilesDir(null), "species_photos").apply { mkdirs() }
    private fun imageFile(ctx: Context, key: String) = File(dir(ctx), "$key.jpg")
    private fun metaFile(ctx: Context, key: String) = File(dir(ctx), "$key.json")

    fun has(ctx: Context, key: String): Boolean =
        imageFile(ctx, key).let { it.exists() && it.length() > 0 }

    fun imageBytes(ctx: Context, key: String): ByteArray? {
        val f = imageFile(ctx, key)
        return if (f.exists() && f.length() > 0) f.readBytes() else null
    }

    /** {"attribution":…, "license":…, "source":…} for the API's `photo` object, or
     *  null if nothing is cached — the API only ever promises "not available right
     *  now", never distinguishes "we tried and failed" from "never tried". */
    fun photoMeta(ctx: Context, key: String): JSONObject? {
        val f = metaFile(ctx, key)
        if (!f.exists() || !has(ctx, key)) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()
    }

    private fun hasConnectivity(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** Synchronous — call from a background thread/coroutine, never the capture loop. */
    fun fetchMissing(ctx: Context, taxa: List<Taxon>): String {
        if (!hasConnectivity(ctx)) return "no connectivity"
        val todo = taxa.distinctBy { it.key }.filter { !has(ctx, it.key) }
        if (todo.isEmpty()) return "all cached"
        var ok = 0; var noPhoto = 0; var netErr = 0
        for (chunk in todo.chunked(BATCH)) {
            val titles = try { pageImageTitles(chunk.map { it.scientific }) } catch (e: Throwable) {
                Log.w(TAG, "pageimages batch failed", e); netErr += chunk.size
                Thread.sleep(PAUSE_MS * 4); continue
            }
            Thread.sleep(PAUSE_MS)
            val infos = try { commonsInfo(titles.values.distinct()) } catch (e: Throwable) {
                Log.w(TAG, "imageinfo batch failed", e); netErr += chunk.size
                Thread.sleep(PAUSE_MS * 4); continue
            }
            Thread.sleep(PAUSE_MS)
            for (sp in chunk) {
                val ft = titles[sp.scientific]
                val info = ft?.let { infos[it] }
                if (info == null) { noPhoto++; continue }
                val (url, attr) = info
                if (attr.optString("artist").isBlank()) { noPhoto++; continue }
                val bytes = try { download(url) } catch (e: Throwable) {
                    Log.w(TAG, "download failed for ${sp.scientific}", e); null
                }
                if (bytes == null) { netErr++; continue }
                imageFile(ctx, sp.key).writeBytes(bytes)
                metaFile(ctx, sp.key).writeText(JSONObject().apply {
                    put("attribution", attr.optString("artist"))
                    put("license", attr.optString("licence"))
                    put("source", attr.optString("description_url"))
                }.toString())
                ok++
                Thread.sleep(IMG_PAUSE_MS)
            }
        }
        return "$ok downloaded, $noPhoto no photo on Commons, $netErr network errors"
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun getJson(url: String, tries: Int = 4): JSONObject {
        var wait = 800L
        repeat(tries) {
            val c = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS
                setRequestProperty("User-Agent", UA); setRequestProperty("Accept", "application/json")
            }
            try {
                val code = c.responseCode
                if (code == 200) return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                if (code == 429 || code >= 500) {
                    val ra = c.getHeaderField("Retry-After")?.toLongOrNull()
                    Thread.sleep(((ra ?: 0L) * 1000L).coerceAtLeast(wait)); wait *= 2
                } else throw IllegalStateException("HTTP $code for $url")
            } finally { c.disconnect() }
        }
        throw IllegalStateException("gave up on $url after $tries attempts")
    }

    private fun pageImageTitles(names: List<String>): Map<String, String> {
        val u = "https://en.wikipedia.org/w/api.php?action=query&format=json" +
                "&prop=pageimages&piprop=name&redirects=1&titles=" + names.joinToString("%7C") { enc(it) }
        val q = getJson(u).optJSONObject("query") ?: return emptyMap()
        val forward = HashMap<String, String>()
        for (key in listOf("normalized", "redirects")) {
            val arr = q.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                forward[o.optString("from")] = o.optString("to")
            }
        }
        fun resolve(n: String): String { var cur = n; repeat(4) { cur = forward[cur] ?: return cur }; return cur }
        val byTitle = HashMap<String, String>()
        q.optJSONObject("pages")?.let { pages ->
            val it = pages.keys()
            while (it.hasNext()) {
                val p = pages.optJSONObject(it.next()) ?: continue
                val img = p.optString("pageimage", "")
                if (img.isNotBlank()) byTitle[p.optString("title")] = "File:$img"
            }
        }
        val out = HashMap<String, String>()
        for (n in names) byTitle[resolve(n)]?.let { out[n] = it }
        return out
    }

    private fun commonsInfo(files: List<String>): Map<String, Pair<String, JSONObject>> {
        if (files.isEmpty()) return emptyMap()
        val out = HashMap<String, Pair<String, JSONObject>>()
        for (group in files.chunked(BATCH)) {
            val u = "https://commons.wikimedia.org/w/api.php?action=query&format=json" +
                    "&prop=imageinfo&iiprop=url%7Cextmetadata&iiurlwidth=$THUMB_PX&titles=" +
                    group.joinToString("%7C") { enc(it) }
            val q = getJson(u).optJSONObject("query") ?: continue
            // MediaWiki normalizes "_" <-> " " between what we queried (from pageimages,
            // underscored) and what it reports back — without unwinding that, every
            // lookup below misses and every photo looks "not on Commons" when it is
            // (found live: Pica pica -> File:Eurasian_magpie_…, 0/1 downloaded).
            val queriedFor = HashMap<String, String>()   // reported title -> title we asked for
            q.optJSONArray("normalized")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    queriedFor[o.optString("to")] = o.optString("from")
                }
            }
            val pages = q.optJSONObject("pages") ?: continue
            val k = pages.keys()
            while (k.hasNext()) {
                val page = pages.optJSONObject(k.next()) ?: continue
                val reportedTitle = page.optString("title")
                val title = queriedFor[reportedTitle] ?: reportedTitle
                val ii = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: continue
                val thumb = ii.optString("thumburl", "").ifBlank { ii.optString("url", "") }
                if (thumb.isBlank()) continue
                val em = ii.optJSONObject("extmetadata")
                fun meta(key: String) = em?.optJSONObject(key)?.optString("value", "") ?: ""
                out[title] = thumb to JSONObject().apply {
                    put("artist", stripHtml(meta("Artist")))
                    put("licence", stripHtml(meta("LicenseShortName")))
                    put("description_url", ii.optString("descriptionurl", ""))
                }
            }
            if (files.size > BATCH) Thread.sleep(PAUSE_MS)
        }
        return out
    }

    private fun download(url: String): ByteArray? {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS; readTimeout = TIMEOUT_MS; setRequestProperty("User-Agent", UA)
        }
        return try { if (c.responseCode != 200) null else c.inputStream.use { it.readBytes() } }
        finally { c.disconnect() }
    }

    private fun stripHtml(s: String): String =
        s.replace(Regex("<[^>]*>"), "").replace("&amp;", "&").replace("&quot;", "\"")
            .replace("&#039;", "'").replace("&nbsp;", " ").replace(Regex("\\s+"), " ").trim()
}
