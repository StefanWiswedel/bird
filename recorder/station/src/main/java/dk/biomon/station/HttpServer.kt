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

    /**
     * One live spectrogram subscriber.
     *
     * THE QUEUE IS DELIBERATELY TINY. Columns are produced on the capture thread at 30/s
     * and are only interesting while they are current — a column that arrives late is not
     * late data, it is wrong data, because the client draws it at the right-hand edge as
     * though it were now. So the queue holds a fifth of a second and [drop] discards the
     * OLDEST frame when it overflows: a viewer on a weak signal sees a jump rather than a
     * progressively growing lag, and the capture thread never blocks on a socket write.
     */
    private class WsClient(val sock: Socket, val out: OutputStream) {
        val queue = java.util.concurrent.ArrayBlockingQueue<ByteArray>(6)
        @Volatile var alive = true
        var dropped = 0L
        fun offer(frame: ByteArray) {
            if (!queue.offer(frame)) { queue.poll(); dropped++; queue.offer(frame) }
        }
    }

    private val wsClients = CopyOnWriteArrayList<WsClient>()

    /** Live subscriber count and total frames dropped to backpressure, for /api/health. */
    fun spectrogramClients(): Int = wsClients.size
    fun spectrogramDropped(): Long = wsClients.sumOf { it.dropped }

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
        wsClients.forEach { it.alive = false; runCatching { it.sock.close() } }
        pool.shutdownNow()
    }

    /** Called by StationService right after a detection is stored. */
    fun broadcastDetection(det: Detection, photo: JSONObject?) {
        broadcast("detection", det.json(station.json(), photo))
    }

    /**
     * Push one spectrogram column to every live subscriber. Called on the CAPTURE THREAD,
     * 30x/second, so this method must never block or allocate heavily — it builds the frame
     * once, hands the same immutable array to every client, and returns. Actual socket
     * writes happen on each client's own writer thread.
     *
     * Wire format (station/API.md), 265-byte payload:
     *   byte 0      uint8    message type, 0x01 = spectrogram column
     *   bytes 1-8   float64  timestamp, epoch SECONDS, little-endian
     *   bytes 9-264 uint8[256] magnitudes, low frequency -> high
     */
    fun broadcastSpectrogramColumn(atSeconds: Double, magnitudes: ByteArray) {
        if (wsClients.isEmpty()) return
        val payload = ByteArray(1 + 8 + magnitudes.size)
        payload[0] = 0x01
        val bits = java.lang.Double.doubleToLongBits(atSeconds)
        for (i in 0 until 8) payload[1 + i] = ((bits shr (8 * i)) and 0xFF).toByte()   // little-endian
        System.arraycopy(magnitudes, 0, payload, 9, magnitudes.size)
        val frame = wsBinaryFrame(payload)
        for (c in wsClients) if (c.alive) c.offer(frame)
    }

    /** FIN + binary opcode, never masked (RFC 6455: servers must not mask). 265 bytes needs
     *  the 126 + 16-bit extended-length form; the 7-bit form stops at 125. */
    private fun wsBinaryFrame(payload: ByteArray): ByteArray {
        val n = payload.size
        return if (n <= 125) {
            ByteArray(2 + n).also { it[0] = 0x82.toByte(); it[1] = n.toByte()
                System.arraycopy(payload, 0, it, 2, n) }
        } else {
            ByteArray(4 + n).also { it[0] = 0x82.toByte(); it[1] = 126
                it[2] = ((n shr 8) and 0xFF).toByte(); it[3] = (n and 0xFF).toByte()
                System.arraycopy(payload, 0, it, 4, n) }
        }
    }

    /**
     * RFC 6455 upgrade, then a dedicated writer loop for this subscriber.
     *
     * The reader runs on its own thread purely so a client CLOSE is noticed promptly — the
     * writer would otherwise only discover a dead socket on its next write, which on a live
     * view with a stalled producer could be never, leaking a thread per reconnect.
     */
    private fun handleWebSocket(sock: Socket, req: Req) {
        val key = req.headers["sec-websocket-key"] ?: run { sock.close(); return }
        val accept = java.security.MessageDigest.getInstance("SHA-1")
            .digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.US_ASCII))
            .let { android.util.Base64.encodeToString(it, android.util.Base64.NO_WRAP) }

        val out = sock.getOutputStream()
        out.write(("HTTP/1.1 101 Switching Protocols\r\n" +
                   "Upgrade: websocket\r\nConnection: Upgrade\r\n" +
                   "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray())
        out.flush()

        // No read timeout: a live view is idle in the client->server direction by design,
        // and the 20 s request timeout would otherwise kill every subscriber.
        sock.soTimeout = 0
        sock.tcpNoDelay = true          // 265-byte frames at 30/s; Nagle would batch and lag them
        val client = WsClient(sock, out)
        wsClients.add(client)

        pool.execute { runCatching { wsReadLoop(sock, client) }; client.alive = false }
        try {
            while (running.get() && client.alive) {
                val frame = client.queue.poll(1, java.util.concurrent.TimeUnit.SECONDS) ?: continue
                out.write(frame); out.flush()
            }
        } catch (e: Exception) {
            // Broken pipe on a LAN viewer that closed a tab. Not an error worth logging.
        } finally {
            client.alive = false
            wsClients.remove(client)
            runCatching { sock.close() }
        }
    }

    /** Drains client->server frames. Handles close and ping; ignores everything else, since
     *  this endpoint takes no client input. Client frames are always masked. */
    private fun wsReadLoop(sock: Socket, client: WsClient) {
        val input = sock.getInputStream()
        while (client.alive) {
            val b0 = input.read(); if (b0 < 0) break
            val b1 = input.read(); if (b1 < 0) break
            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) {
                len = ((input.read() shl 8) or input.read()).toLong()
            } else if (len == 127L) {
                len = 0; for (i in 0 until 8) len = (len shl 8) or input.read().toLong()
            }
            val mask = ByteArray(4)
            if (masked) { var r = 0; while (r < 4) { val n = input.read(mask, r, 4 - r); if (n < 0) return; r += n } }
            val payload = ByteArray(len.toInt().coerceAtMost(1 shl 16))
            var read = 0
            while (read < payload.size) { val n = input.read(payload, read, payload.size - read); if (n < 0) return; read += n }
            if (masked) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (opcode) {
                0x8 -> { runCatching { client.out.write(byteArrayOf(0x88.toByte(), 0)); client.out.flush() }; return }
                0x9 -> client.offer(ByteArray(2 + payload.size).also {          // ping -> pong
                    it[0] = 0x8A.toByte(); it[1] = payload.size.toByte()
                    System.arraycopy(payload, 0, it, 2, payload.size) })
            }
        }
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
        // Checked before the normal route table because a WebSocket upgrade takes the socket
        // over entirely — after the 101 there is no more HTTP on this connection.
        if (req.path == "/api/spectrogram" &&
            req.headers["upgrade"]?.lowercase() == "websocket") { handleWebSocket(sock, req); return }

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
            // Served from getExternalFilesDir("dashboard"), not from the APK, so the UI can
            // be updated without a reinstall (POST /api/dashboard/update below). The bundled
            // asset remains the seed and the fallback — see DashboardStore.
            p == "/" || p == "/index.html" -> serveDashboard(out, "index.html")
            p == "/tokens.css" -> serveDashboard(out, "tokens.css")
            // NO AUTH TOKEN, DELIBERATELY — do not "fix" this by adding one.
            //
            // Two reasons. First, the API's threat model already assumes a trusted LAN:
            // /api/data accepts an unauthenticated DELETE that wipes every detection and
            // clip on the station. A token guarding the dashboard's stylesheet, beside an
            // open endpoint that destroys the data, would be theatre.
            // Second, the source URL is a compile-time constant (BuildConfig.
            // DASHBOARD_SOURCE_URL) and is NOT client-supplied, which IS the security
            // model here: the worst a hostile device on the LAN can make this station do
            // is re-download the station owner's own dashboard from the owner's own repo.
            // Introducing a URL parameter "for flexibility" is what would actually need a
            // token, so don't do that either.
            p == "/api/dashboard/update" && req.method == "POST" -> {
                val r = DashboardStore.update(ctx)
                // §2d: a failure must not be reported through the success channel. The body
                // says which file failed and why; the status code says "this did not work"
                // to anything that only looks at status codes.
                val code = when {
                    r.optBoolean("updated") -> 200
                    r.optString("error") in setOf("write", "storage") -> 500
                    else -> 502     // the fetch from GitHub failed or returned junk
                }
                writeJson(out, code, r)
            }
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
                // Life-list status travels with each row so the feed can grey out a species
                // the observer has already rejected instead of silently dropping it — seeing
                // the model still produce it is the signal that a threshold override is due.
                val rejected = db.rejectedSpecies()
                val confirmedSp = db.lifeList().filter { it.status == "confirmed" }
                    .map { it.scientific }.toHashSet()
                val arr = jsonArrayOf(rows.map { r ->
                    r.toDetection().json(station.json(), photoJson(r.taxon.key)).put("species_status",
                        when (r.taxon.scientific) {
                            in rejected -> "rejected"
                            in confirmedSp -> "confirmed"
                            else -> "candidate"
                        })
                })
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
            p == "/api/life-list" -> {
                val arr = org.json.JSONArray()
                for (s in db.lifeList()) arr.put(JSONObject()
                    .put("scientific", s.scientific).put("common", s.common)
                    .put("status", s.status)
                    .put("first_detected_at", s.firstDetectedAt ?: JSONObject.NULL)
                    .put("first_confirmed_at", s.firstConfirmedAt ?: JSONObject.NULL)
                    .put("lifer_detection_id", s.liferDetectionId ?: JSONObject.NULL)
                    .put("best_detection_id", s.bestDetectionId ?: JSONObject.NULL)
                    .put("total_detections", s.totalDetections)
                    .put("photo", photoJson(slugOf(s.scientific)) ?: JSONObject.NULL))
                val confirmed = db.lifeList().count { it.status == "confirmed" }
                writeJson(out, 200, JSONObject().put("schema", 1)
                    .put("confirmed_count", confirmed).put("count", arr.length())
                    .put("species", arr).put("station", station.json()))
            }
            p == "/api/verify/queue" -> {
                val limit = (req.query["limit"]?.toIntOrNull() ?: 20).coerceIn(1, 100)
                val week = birdnetWeek(System.currentTimeMillis())
                val q = db.verifyQueue(settings.get(), week, limit)
                val arr = org.json.JSONArray()
                for ((row, prior) in q) {
                    val det = row.toDetection().json(station.json(), photoJson(row.taxon.key))
                    val status = db.speciesStatus(row.taxon.scientific)
                    arr.put(det
                        .put("prior", prior ?: JSONObject.NULL)
                        // A low local prior means BOTH "exciting" and "check this carefully".
                        // One flag, both meanings — see the design spec's --signal rule.
                        .put("rarity_flag", prior != null && prior < RARITY_PRIOR)
                        .put("would_be_lifer", status?.firstConfirmedAt == null))
                }
                writeJson(out, 200, JSONObject().put("schema", 1).put("week", week)
                    .put("priors_available", db.priorCount() > 0)
                    .put("count", arr.length()).put("queue", arr)
                    .put("life_list_size", db.lifeList().count { it.status == "confirmed" }))
            }
            p.startsWith("/api/verify/") && req.method == "POST" -> {
                val id = p.removePrefix("/api/verify/").toLongOrNull()
                if (id == null) { writeJson(out, 400, JSONObject().put("error", "bad id")); return }
                val body = runCatching { JSONObject(String(req.body)) }.getOrNull() ?: JSONObject()
                val verdict = body.optString("verdict")
                if (verdict !in setOf("yes", "no", "unsure")) {
                    writeJson(out, 400, JSONObject().put("error", "verdict must be yes|no|unsure")); return
                }
                val st = db.recordVerification(id, verdict, body.optString("note").ifBlank { null })
                if (st == null) { writeJson(out, 404, JSONObject().put("error", "no such detection")); return }
                writeJson(out, 200, JSONObject().put("schema", 1)
                    .put("scientific", st.scientific).put("common", st.common)
                    .put("status", st.status)
                    .put("first_confirmed_at", st.firstConfirmedAt ?: JSONObject.NULL)
                    .put("is_lifer", verdict == "yes" && st.liferDetectionId == id)
                    .put("life_list_size", db.lifeList().count { it.status == "confirmed" }))
            }
            // GET previews what would be destroyed; DELETE does it. The old button reported
            // only "Cleared." even when it had left photos and orphaned clips behind, which
            // is how it earned the description "it didn't clear everything".
            p == "/api/data" && req.method == "GET" -> {
                val pv = db.clearPreview()
                writeJson(out, 200, JSONObject().put("detections", pv.detections)
                    .put("clips", pv.clips).put("confirmed_species", pv.confirmedSpecies)
                    .put("pinned_clips", pv.pinnedClips))
            }
            p == "/api/data" && req.method == "DELETE" -> {
                // Destroying a life list is a separate, explicit act — it records human
                // decisions that re-recording cannot reproduce. Default keeps it.
                val alsoLifeList = req.query["life_list"] == "true"
                val before = db.clearPreview()
                db.clearAll(keepLifeList = !alsoLifeList)
                val after = db.clearPreview()
                writeJson(out, 200, JSONObject().put("cleared", true)
                    .put("detections_deleted", before.detections - after.detections)
                    .put("clips_deleted", before.clips - after.clips)
                    .put("life_list_kept", !alsoLifeList)
                    .put("confirmed_species_remaining", after.confirmedSpecies))
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

    private companion object {
        /** Below this local-occurrence prior a detection is flagged rare. Provisional: it is
         *  a guess until the meta-model has run for this station and the distribution of
         *  priors here can actually be looked at. Named rather than inlined for that reason. */
        const val RARITY_PRIOR = 0.02
    }

    /** BirdNET's 48-week convention: four weeks per month, the 4th absorbing the remainder.
     *  Must match how the priors were generated or every lookup is silently off. */
    private fun birdnetWeek(atMs: Long): Int {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = atMs }
        val month = c.get(java.util.Calendar.MONTH) + 1
        val day = c.get(java.util.Calendar.DAY_OF_MONTH)
        return (month - 1) * 4 + minOf(3, (day - 1) / 7) + 1
    }

    /** Same slug rule as build_station_species.py, so photo files line up. */
    private fun slugOf(scientific: String): String =
        scientific.lowercase().replace(Regex("[^a-z0-9]"), "_").replace(Regex("_+"), "_").trim('_')

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
        val msg = when (code) { 200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 400 -> "Bad Request"
                                404 -> "Not Found"; 500 -> "Internal Server Error"; 502 -> "Bad Gateway"; else -> "Error" }
        out.write("HTTP/1.1 $code $msg\r\n".toByteArray())
    }

    /** DELETE is in the list because /api/data is DELETE-only: without it a cross-origin
     *  "Clear all" dies at the preflight, which looks exactly like a station that is not
     *  responding rather than like a header that is missing one verb. */
    private fun writeCors(out: OutputStream) {
        out.write(("Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: content-type\r\n" +
                  "Access-Control-Allow-Methods: GET,POST,DELETE,OPTIONS\r\n").toByteArray())
    }

    private fun writeJson(out: OutputStream, code: Int, body: JSONObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        writeStatus(out, code); writeCors(out)
        out.write(("Content-Type: application/json; charset=utf-8\r\nCache-Control: no-store\r\n" +
                  "Content-Length: ${bytes.size}\r\n\r\n").toByteArray())
        out.write(bytes)
    }

    /** Stored file, else the bundled asset (DashboardStore.read). A 404 here means both are
     *  gone, which on a phone with no adb would be terminal — so it says which file. */
    private fun serveDashboard(out: OutputStream, name: String) {
        val bytes = DashboardStore.read(ctx, name)
        val contentType = DashboardStore.contentType(name)
        if (bytes == null || contentType == null) {
            writeJson(out, 404, JSONObject().put("error", "dashboard file unavailable: $name")); return
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
