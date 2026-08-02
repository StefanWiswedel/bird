package dk.biomon.station

import android.content.Context
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

interface HealthProvider {
    fun health(): JSONObject
    /** The most recently captured 3 s window as WAV bytes, paired with its start-of-window
     *  timestamp — updated every hop (1.5 s) regardless of whether anything scored above
     *  the retention floor. This is raw ambient audio, not a stored detection clip; it
     *  exists only in memory and is never written to disk. Null until the first window
     *  lands after capture starts. */
    fun latestAudioWav(): Pair<ByteArray, Long>?
}

/**
 * The whole HTTP surface from station/API.md, on a bare ServerSocket — no NanoHTTPD, no
 * Ktor: build.gradle.kts's "no new dependencies" rule exists so this module builds
 * offline from the Gradle cache :app already populated, and an HTTP server is small
 * enough to hand-write once against a contract that is already fully specified.
 *
 * One thread per connection. This is a LAN appliance serving at most a handful of
 * concurrent viewers (API.md: "whatever device is to hand") plus long-lived SSE
 * subscribers — a thread pool sized for a real internet-facing server would be over-
 * engineering here.
 */
class HttpServer(
    private val port: Int,
    private val ctx: Context,
    private val db: Database,
    private val settings: SettingsStore,
    private val station: Station,
    private val health: HealthProvider
) {
    private var serverSocket: ServerSocket? = null
    private val running = AtomicBoolean(false)
    private val pool = Executors.newCachedThreadPool()
    private val sseClients = CopyOnWriteArrayList<SseClient>()

    private class SseClient(val out: OutputStream) {
        val queue = ConcurrentLinkedQueue<String>()
        @Volatile var alive = true
    }

    fun start() {
        if (running.getAndSet(true)) return
        serverSocket = ServerSocket()
        serverSocket!!.reuseAddress = true
        serverSocket!!.bind(InetSocketAddress(port))
        pool.execute {
            while (running.get()) {
                val sock = try { serverSocket!!.accept() } catch (e: Exception) { if (running.get()) continue else break }
                pool.execute { runCatching { handle(sock) } }
            }
        }
        pool.execute { statusTicker() }
    }

    fun stop() {
        running.set(false)
        runCatching { serverSocket?.close() }
        sseClients.forEach { it.alive = false }
        pool.shutdownNow()
    }

    /** Called by StationService right after a detection is stored. */
    fun broadcastDetection(det: Detection, photo: JSONObject?) {
        broadcast("detection", det.json(station.json(), photo))
    }

    private fun statusTicker() {
        while (running.get()) {
            Thread.sleep(15_000)
            if (sseClients.isNotEmpty()) broadcast("status", health.health())
        }
    }

    private fun broadcast(event: String, payload: JSONObject) {
        val msg = "event: $event\ndata: $payload\n\n"
        for (c in sseClients) c.queue.add(msg)
    }

    // ------------------------------------------------------------------ request handling

    private data class Req(val method: String, val path: String, val query: Map<String, String>,
                           val headers: Map<String, String>, val body: ByteArray)

    private fun handle(sock: Socket) {
        sock.soTimeout = 20_000
        val input = sock.getInputStream()
        val req = try { readRequest(input) } catch (e: Exception) { sock.close(); return }
        if (req == null) { sock.close(); return }

        if (req.path == "/api/events") { handleSse(sock); return }

        val out = BufferedOutputStream(sock.getOutputStream())
        try {
            route(req, out, sock)
        } catch (e: Exception) {
            runCatching { writeJson(out, 500, JSONObject().put("error", e.message ?: "internal error")) }
        } finally {
            runCatching { out.flush() }
            runCatching { sock.close() }
        }
    }

    private fun readRequest(input: java.io.InputStream): Req? {
        val line = readLine(input) ?: return null
        val parts = line.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val full = parts[1]
        val qIdx = full.indexOf('?')
        val path = URLDecoder.decode(if (qIdx >= 0) full.substring(0, qIdx) else full, "UTF-8")
        val query = HashMap<String, String>()
        if (qIdx >= 0) {
            for (kv in full.substring(qIdx + 1).split("&")) {
                if (kv.isBlank()) continue
                val eq = kv.indexOf('=')
                val k = URLDecoder.decode(if (eq >= 0) kv.substring(0, eq) else kv, "UTF-8")
                val v = if (eq >= 0) URLDecoder.decode(kv.substring(eq + 1), "UTF-8") else ""
                query[k] = v
            }
        }
        val headers = HashMap<String, String>()
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            val i = h.indexOf(':')
            if (i > 0) headers[h.substring(0, i).trim().lowercase()] = h.substring(i + 1).trim()
        }
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(len)
        var read = 0
        while (read < len) {
            val n = input.read(body, read, len - read)
            if (n < 0) break
            read += n
        }
        return Req(method, path, query, headers, body)
    }

    private fun readLine(input: java.io.InputStream): String? {
        val sb = StringBuilder()
        var prevCr = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return if (prevCr) sb.dropLast(1).toString() else sb.toString()
            prevCr = b == '\r'.code
            sb.append(b.toInt().toChar())
        }
    }

    private fun route(req: Req, out: OutputStream, sock: Socket) {
        val p = req.path
        when {
            req.method == "OPTIONS" -> { writeStatus(out, 204); writeCors(out); out.write("Content-Length: 0\r\n\r\n".toByteArray()) }
            p == "/" || p == "/index.html" -> serveAsset(out, "www/index.html", "text/html; charset=utf-8")
            p == "/api/health" -> writeJson(out, 200, health.health())
            p == "/api/settings" && req.method == "GET" -> writeJson(out, 200, settings.get().json())
            p == "/api/settings" && req.method == "POST" -> {
                val patch = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrElse { JSONObject() }
                writeJson(out, 200, settings.update(patch).json())
            }
            p == "/api/days" -> {
                val days = db.daySummaries(settings.get())
                val today = Detection.localDate(System.currentTimeMillis())
                val arr = jsonArrayOf(days.map {
                    JSONObject().put("date", it.date).put("detection_count", it.detectionCount)
                        .put("species_count", it.speciesCount)
                })
                writeJson(out, 200, JSONObject().put("schema", 1).put("today", today).put("days", arr))
            }
            p == "/api/species" -> {
                val sp = db.speciesSummary(settings.get())
                val arr = jsonArrayOf(sp.map {
                    JSONObject().put("taxon", it.taxon.json()).put("count", it.count)
                        .put("last_ms", it.lastMs).put("best_confidence", round3(it.bestConfidence))
                        .put("photo", photoJson(it.taxon.key))
                })
                writeJson(out, 200, JSONObject().put("schema", 1).put("server_ms", System.currentTimeMillis())
                    .put("count", sp.size).put("species", arr))
            }
            p == "/api/summary" -> {
                val date = req.query["date"] ?: Detection.localDate(System.currentTimeMillis())
                val s = db.summaryForDate(date, settings.get())
                val topArr = jsonArrayOf(s.top.map {
                    JSONObject().put("taxon", it.taxon.json()).put("count", it.count)
                        .put("best_confidence", round3(it.bestConfidence)).put("last_ms", it.lastMs)
                })
                val byHour = org.json.JSONArray(); s.byHour.forEach { byHour.put(it) }
                val body = JSONObject().put("schema", 1).put("date", s.date)
                    .put("species_count", s.speciesCount).put("detection_count", s.detectionCount)
                    .put("first_ms", s.firstMs ?: JSONObject.NULL).put("last_ms", s.lastMs ?: JSONObject.NULL)
                    .put("most_active", s.mostActive?.let { JSONObject().put("taxon", it.first.json()).put("count", it.second) } ?: JSONObject.NULL)
                    .put("by_hour", byHour).put("top", topArr).put("station", station.json())
                    .put("weather", JSONObject.NULL) // no weather source wired into the station yet — see final report
                writeJson(out, 200, body)
            }
            p == "/api/detections" -> {
                val st = settings.get()
                val rows = db.listDetections(
                    sinceMs = req.query["since_ms"]?.toLongOrNull(),
                    date = req.query["date"],
                    group = req.query["group"],
                    state = req.query["state"] ?: "confirmed",
                    minConf = req.query["min_conf"]?.toFloatOrNull(),
                    limit = (req.query["limit"]?.toIntOrNull() ?: 100).coerceIn(1, 500),
                    settings = st
                )
                val arr = jsonArrayOf(rows.map { it.toDetection().json(station.json(), photoJson(it.taxon.key)) })
                writeJson(out, 200, JSONObject().put("schema", 1).put("server_ms", System.currentTimeMillis())
                    .put("count", rows.size).put("detections", arr))
            }
            p == "/api/live-audio" -> {
                val live = health.latestAudioWav()
                if (live == null) { writeJson(out, 404, JSONObject().put("error", "no audio captured yet")); return }
                val (bytes, atMs) = live
                writeStatus(out, 200); writeCors(out)
                out.write(("Content-Type: audio/wav\r\nCache-Control: no-store\r\nX-Window-Ms: $atMs\r\n" +
                          "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
                out.write(bytes)
            }
            p == "/api/data" && req.method == "DELETE" -> {
                db.clearAll()
                writeJson(out, 200, JSONObject().put("cleared", true))
            }
            p.startsWith("/api/clip/") -> {
                val id = p.removePrefix("/api/clip/").toLongOrNull()
                val row = id?.let { db.byId(it) }
                val path = row?.clipPath
                if (path == null || !File(path).exists()) { writeJson(out, 404, JSONObject().put("error", "clip not available")); return }
                serveFileWithRange(out, File(path), "audio/wav", req.headers["range"])
            }
            p.startsWith("/api/species/") && p.endsWith("/threshold") && req.method == "POST" -> {
                val key = p.removePrefix("/api/species/").removeSuffix("/threshold").trim('/')
                if (key.isEmpty()) { writeJson(out, 404, JSONObject().put("error", "missing taxon key")); return }
                val body = runCatching { JSONObject(String(req.body, Charsets.UTF_8)) }.getOrElse { JSONObject() }
                val st = settings.get()
                when {
                    body.has("threshold") && body.isNull("threshold") -> db.setSpeciesThreshold(key, null)
                    body.has("threshold") -> db.setSpeciesThreshold(
                        key, body.getDouble("threshold").toFloat().coerceIn(0f, 1f))
                    body.has("delta") -> {
                        val current = db.speciesBaselineThreshold(key, st)
                        val bumped = (current + body.getDouble("delta").toFloat()).coerceIn(0f, 1f)
                        db.setSpeciesThreshold(key, bumped)
                    }
                    else -> {
                        writeJson(out, 400, JSONObject().put("error", "expected 'delta' or 'threshold' in body")); return
                    }
                }
                val override = db.getSpeciesThreshold(key)
                writeJson(out, 200, JSONObject()
                    .put("taxon_key", key)
                    .put("threshold_override", override ?: JSONObject.NULL)
                    .put("display_threshold", st.displayThreshold))
            }
            p.startsWith("/api/photo/") -> {
                val key = p.removePrefix("/api/photo/")
                val bytes = PhotoCache.imageBytes(ctx, key)
                if (bytes == null) { writeJson(out, 404, JSONObject().put("error", "no cached photo")); return }
                writeStatus(out, 200); writeCors(out)
                out.write("Content-Type: image/jpeg\r\nCache-Control: max-age=600\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                out.write(bytes)
            }
            else -> writeJson(out, 404, JSONObject().put("error", "not found").put("path", p))
        }
    }

    private fun photoJson(taxonKey: String): JSONObject? {
        val meta = PhotoCache.photoMeta(ctx, taxonKey) ?: return null
        return JSONObject().put("url", "/api/photo/$taxonKey")
            .put("attribution", meta.optString("attribution"))
            .put("license", meta.optString("license"))
            .put("source", meta.optString("source"))
    }

    private fun round3(f: Float): Double = Math.round(f * 1000.0) / 1000.0

    // ------------------------------------------------------------------ SSE

    private fun handleSse(sock: Socket) {
        sock.soTimeout = 0
        val out = sock.getOutputStream()
        writeStatus(out, 200); writeCors(out)
        out.write(("Content-Type: text/event-stream; charset=utf-8\r\nCache-Control: no-store\r\n" +
                  "Connection: keep-alive\r\nX-Accel-Buffering: no\r\n\r\n").toByteArray())
        val client = SseClient(out)
        sseClients.add(client)
        try {
            out.write("event: hello\ndata: ${JSONObject().put("server_ms", System.currentTimeMillis()).put("schema", 1)}\n\n".toByteArray())
            out.write("event: status\ndata: ${health.health()}\n\n".toByteArray())
            out.flush()
            var lastKeepalive = System.currentTimeMillis()
            while (running.get() && client.alive) {
                val msg = client.queue.poll()
                if (msg != null) {
                    if (msg.isNotEmpty()) { out.write(msg.toByteArray()); out.flush() }
                } else {
                    Thread.sleep(200)
                }
                if (System.currentTimeMillis() - lastKeepalive > 15_000) {
                    out.write(": keepalive\n\n".toByteArray()); out.flush()
                    lastKeepalive = System.currentTimeMillis()
                }
            }
        } catch (e: Exception) {
            // client disconnected — normal
        } finally {
            client.alive = false
            sseClients.remove(client)
            runCatching { sock.close() }
        }
    }

    // ------------------------------------------------------------------ response writers

    private fun writeStatus(out: OutputStream, code: Int) {
        val msg = when (code) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 404 -> "Not Found"; 400 -> "Bad Request"; else -> "Error" }
        out.write("HTTP/1.1 $code $msg\r\n".toByteArray())
    }

    private fun writeCors(out: OutputStream) {
        out.write(("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: content-type\r\n" +
                  "Access-Control-Allow-Methods: GET,POST,OPTIONS\r\n").toByteArray())
    }

    private fun writeJson(out: OutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        writeStatus(out, code); writeCors(out)
        out.write(("Content-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\n" +
                  "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
        out.write(bytes)
    }

    private fun serveAsset(out: OutputStream, assetPath: String, contentType: String) {
        val bytes = try { ctx.assets.open(assetPath).use { it.readBytes() } } catch (e: Exception) {
            writeJson(out, 404, JSONObject().put("error", "asset not found: $assetPath")); return
        }
        writeStatus(out, 200); writeCors(out)
        out.write(("Content-Type: $contentType\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\n\r\n").toByteArray())
        out.write(bytes)
    }

    private fun serveFileWithRange(out: OutputStream, f: File, contentType: String, range: String?) {
        val len = f.length()
        if (range != null) {
            val m = Regex("bytes=(\\d*)-(\\d*)").find(range)
            if (m != null) {
                val a = m.groupValues[1].toLongOrNull() ?: 0L
                val b = (m.groupValues[2].toLongOrNull() ?: (len - 1)).coerceAtMost(len - 1)
                if (a <= b) {
                    writeStatus(out, 206); writeCors(out)
                    out.write(("Content-Type: $contentType\r\nAccept-Ranges: bytes\r\n" +
                              "Content-Range: bytes $a-$b/$len\r\nContent-Length: ${b - a + 1}\r\n\r\n").toByteArray())
                    f.inputStream().use { ins ->
                        ins.skip(a)
                        val buf = ByteArray(8192); var remaining = b - a + 1
                        while (remaining > 0) {
                            val n = ins.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                            if (n < 0) break
                            out.write(buf, 0, n); remaining -= n
                        }
                    }
                    return
                }
            }
        }
        writeStatus(out, 200); writeCors(out)
        out.write(("Content-Type: $contentType\r\nAccept-Ranges: bytes\r\nContent-Length: $len\r\n\r\n").toByteArray())
        f.inputStream().use { it.copyTo(out) }
    }
}
