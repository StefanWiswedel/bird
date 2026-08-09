# Bird station — data contract

The one representation of a detection, and the HTTP surface the dashboard consumes.
Written before either side, so the Android app and the dashboard build against the same
thing rather than against each other.

Version `schema: 1`. Anything that changes the meaning of an existing field bumps it.

---

## 1. A detection

```json
{
  "schema": 1,
  "id": 4127,
  "detected_at_ms": 1785640462000,
  "detected_at": "2026-08-01T05:14:22+02:00",
  "window": { "start_ms": 1785640462000, "duration_ms": 3000 },

  "taxon": {
    "key": "turdus_merula",
    "scientific": "Turdus merula",
    "common": "Eurasian Blackbird",
    "rank": "species",
    "group": "bird",
    "model_index": 5218,
    "regional": true
  },

  "detector": {
    "name": "birdnet",
    "version": "GLOBAL_6K_V2.4",
    "score_type": "birdnet_confidence"
  },
  "confidence": 0.834,

  "state": "confirmed",
  "repeat_count": 4,
  "regional": true,
  "in_season": true,

  "clip": { "url": "/api/clip/4127", "seconds": 18.4, "mime": "audio/wav",
            "starts_at_ms": 1785640457000, "detection_offset_s": 5.0 },
  "photo": { "url": "/api/photo/turdus_merula", "attribution": "…", "license": "CC BY-SA 4.0", "source": "https://commons.wikimedia.org/wiki/File:…" },

  "station": { "id": "balcony-5t", "name": "Balcony", "lat": 55.6478, "lon": 12.5875, "tz": "Europe/Copenhagen" }
}
```

### Field rules that matter

- **`taxon.group` exists so adding non-birds later is not a breaking change.** The
  dashboard must never hardcode `"bird"` — it groups and labels by `taxon.group` and
  `taxon.rank`, both free strings. When Orthoptera or insect-board detections arrive they
  are the same object with a different `group`, and the dashboard keeps working.
- **`taxon.common` may be empty; `taxon.scientific` never is.** The UI shows common name
  first and scientific alongside, every time — never scientific alone unless there is no
  common name. This is a project rule, not a preference.
- **`confidence` is only comparable within one `detector`.** BirdNET emits a 0–1 sigmoid
  confidence; Perch emits an uncalibrated logit. `score_type` names the scale. Nothing
  ever averages or compares scores across detectors, and the dashboard must not draw them
  on a shared axis.
- **`state`** is `"confirmed"` (cleared the display threshold *and* the repeat rule) or
  `"candidate"` (stored, above the retention floor, not yet shown by default). Curation is
  applied **at read time**: the same stored rows re-filter instantly when thresholds
  change, with no reprocessing. Nothing is deleted for being below threshold.
- **The threshold a row must clear to be `"confirmed"` is not always `display_threshold`.**
  It is, in order: (1) a per-species override if one has been set (`POST
  /api/species/{key}/threshold`), which replaces the global default outright for that
  species; otherwise (2) `display_threshold` plus a penalty if `regional` is `false`
  and/or `in_season` is `false` (only when `region_season_filter_enabled` is on). A
  species is never dropped for being out-of-region or out-of-season — only harder to
  confirm; it always remains visible as a candidate. `regional`/`in_season` are captured
  once, at detection time, against that detection's own calendar month — they do not
  change if you view the row again next season.
- **`photo` may be `null`.** Null means "we have no cached photo", which is *not* the same
  as "no photo exists" — a transport failure must never be reported as absent data. The
  photo cache tracks the difference internally; the API only ever promises "not available
  right now", and the dashboard renders a designed placeholder rather than a broken image.
- **`clip` may be `null`** for a candidate whose clip was pruned by the storage cap, or
  whose bout is still being recorded, or whose audio could not be written at all. These
  are different situations and none of them is "there was no sound" — see below.
- **A clip is a BOUT, not a window, and it is shared.** `seconds` is the real duration of
  the file, no longer the model's 3.0 s window. One file covers a whole bout — every
  detection in it, including detections of *different species* heard in the same passage,
  points at the same `url`. Consumers must not assume one clip per detection, and anything
  counting clips counts distinct URLs.
- **`clip.starts_at_ms` is when the clip's AUDIO begins**, which is earlier than
  `detected_at_ms` because the recording includes a pre-roll: detections fire mid-phrase
  and the opening notes are often the diagnostic part. `detection_offset_s` is the derived
  convenience — where in the file this particular detection starts — so a player can seek
  there instead of starting at zero.
  Both are **`null`, never `0`**, when the clip's start is unknown (a row written before
  bout clips existed). "Play from the beginning" and "we do not know where in this file to
  look" are different answers, and a fabricated `0` would be indistinguishable from a real
  one (§2d).
- **A clip is written AFTER its detection**, because it runs on past the trigger and the
  future cannot be recorded. A row therefore appears with `clip: null` and gains a clip
  seconds later. A row never points at a partially written file: the audio is renamed into
  place before any row references it, so a clip that is advertised is a clip that is
  complete.

---

## 2. Endpoints

All JSON responses are `application/json; charset=utf-8`. All are CORS-open (`*`) because
this is a LAN appliance the user opens from whatever device is to hand.

| method | path | purpose |
|---|---|---|
| GET | `/` | the dashboard (single self-contained page) |
| POST | `/api/dashboard/update` | re-fetch the dashboard's files from the pinned source URL |
| GET | `/api/health` | station status — see below |
| GET | `/api/detections` | detection list, newest first |
| GET | `/api/summary` | one day's rollup |
| GET | `/api/days` | dates that have data, for history navigation |
| GET | `/api/species` | distinct taxa seen, with photo + counts |
| GET | `/api/clip/{id}` | `audio/wav`, supports `Range` — the whole bout, not one window |
| GET | `/api/live-audio` | most recent 3 s raw window as `audio/wav`, not a stored clip |
| GET | `/api/photo/{taxonKey}` | `image/jpeg` from the on-device cache |
| GET | `/api/events` | SSE stream (live feed) |
| GET/POST | `/api/settings` | thresholds, applied at read time |
| POST | `/api/species/{key}/threshold` | set/bump/clear one species' threshold override |
| DELETE | `/api/data` | wipe all detections, clip files, and per-species overrides |

### `GET /api/detections`

Query params, all optional:

| param | default | meaning |
|---|---|---|
| `since_ms` | — | only detections with `detected_at_ms` **>** this. The live-feed cursor. |
| `date` | — | `YYYY-MM-DD` in station-local time |
| `limit` | 100 | max 500 |
| `min_conf` | from settings | overrides the display threshold for this query |
| `state` | `confirmed` | `confirmed` \| `candidate` \| `all` |
| `group` | — | filter by `taxon.group` |

```json
{ "schema": 1, "server_ms": 1785640470123, "count": 2, "detections": [ … ] }
```

`server_ms` is the server's clock at response time. **The dashboard must use it as the
next `since_ms`**, not its own clock — the browser and the phone are different machines
and a skewed client clock would silently skip or repeat detections.

### `GET /api/health`

```json
{
  "schema": 1, "station": { … }, "server_ms": 1785640470123,
  "listening": true,
  "uptime_ms": 84213000,
  "service_started_at_ms": 1785556257000,
  "restarts": 2,
  "model": { "name": "birdnet", "version": "GLOBAL_6K_V2.4", "loaded": true, "classes": 6522 },
  "audio": { "sample_rate": 48000, "source": "UNPROCESSED", "window_s": 3.0, "hop_s": 1.5 },
  "inference": { "last_ms": 118, "mean_ms": 124, "duty_pct": 8.3, "windows_total": 402118 },
  "thermal": { "battery_c": 33.2, "status": "NONE", "throttled": false },
  "storage": { "clips_bytes": 812334592, "cap_bytes": 17179869184, "free_bytes": 115964116992,
               "clips": 1412, "pruned_total": 0,
               "clips_written": 1508, "clips_failed": 0, "recording": false },
  "queue": { "pending": 0, "publishers": ["local"] },
  "settings": { "display_threshold": 0.65, "retention_floor": 0.10, "repeat_required": 2,
                "repeat_window_min": 30, "region_season_filter_enabled": true },
  "app": { "version": "0.1.0", "commit": "1a2b3c4d+", "built_at": "2026-08-08 14:02" },
  "dashboard": { "stored": true, "updated_at_ms": 1785640470123,
                 "source": "https://raw.githubusercontent.com/…/assets/www/" }
}
```

`thermal.throttled` is the station's own decision, not the OS's. It means inference has
been slowed deliberately. The dashboard should say so plainly rather than hide it.

`storage.clips` counts **files**, not rows, since a bout clip is shared by every detection
in it. `clips_failed` counts clips abandoned because the audio could not be written — a
station that has silently stopped keeping audio (disk full, storage unmounted) otherwise
looks exactly like a station on a quiet day, which is the §2d mistake in its purest form.
`recording` is true while a bout is open right now.

`dashboard` describes the **files being served**, which since `POST /api/dashboard/update`
are no longer necessarily the ones inside the APK. `stored` is `false` and
`updated_at_ms` is `null` when the station is still serving the bundled copy — a fresh
install that has never pulled. `app.commit` alone therefore no longer identifies what is
on screen, and the dashboard shows both stamps side by side.

### `GET /api/summary?date=YYYY-MM-DD`

```json
{
  "schema": 1, "date": "2026-08-01",
  "species_count": 11, "detection_count": 243,
  "first_ms": 1785623400000, "last_ms": 1785668100000,
  "most_active": { "taxon": { … }, "count": 61 },
  "by_hour": [0,0,0,0,2,14,31,…],
  "top": [ { "taxon": { … }, "count": 61, "best_confidence": 0.94, "last_ms": … }, … ],
  "station": { … },
  "weather": { "temperature_c": 18.4, "wind_ms": 3.1, "cloud_pct": 40, "source": "open-meteo", "fetched_ms": … }
}
```

`weather` may be `null` — it needs the internet and the station must work without it.

### `GET /api/events` (SSE)

```
event: detection
data: {<one detection object>}

event: status
data: {<the /api/health body>}

event: hello
data: {"server_ms":1785640470123,"schema":1}

: keepalive
```

- `hello` is sent immediately on connect and carries `server_ms`, so the client can
  establish its cursor without a clock assumption.
- `status` is sent every 15 s.
- `: keepalive` comment every 15 s keeps intermediaries and the browser from timing out.
- The client should also poll `/api/detections?since_ms=` as a fallback: SSE over a phone
  hotspot survives less well than a plain GET, and a live feed that silently stops is the
  same failure as a station that silently dies.

### `GET /api/live-audio`

Returns the most recently captured 3 s window as `audio/wav`, updated every hop (1.5 s)
**regardless of whether anything in it scored above the retention floor** — this is raw
ambient audio kept in memory for the dashboard's live spectrogram, never written to disk
and unrelated to `/api/clip/{id}`. `404` until the first window lands after capture
starts. The response carries `X-Window-Ms: <start_ms>` so a poller can tell a genuinely
new window from one it already drew (two polls landing inside the same 1.5 s hop would
otherwise redraw an identical frame).

### `GET/POST /api/settings`

```json
{ "display_threshold": 0.65, "retention_floor": 0.10, "repeat_required": 2,
  "repeat_window_min": 30, "region_season_filter_enabled": true }
```

POST accepts a partial object and returns the full one. **Changing these never
reprocesses anything** — they are query-time filters over rows already stored above the
retention floor. `retention_floor` is the exception: lowering it only affects future
detections, because rows below the old floor were never written. That asymmetry is
deliberate and is stated in the UI. `region_season_filter_enabled` toggles the
region/season penalty described above off entirely; per-species overrides apply either way.

### `POST /api/species/{key}/threshold`

Sets, bumps, or clears the confidence-threshold override for one species (`key` is
`taxon.key`, e.g. `ruddy_shelduck`) — the dashboard action for "this was actually a false
positive, raise the bar for this species". Body is one of:

```json
{ "delta": 0.05 }
```
Bump: new override = (existing override, or the global `display_threshold` if none is
set yet) + `delta`, clamped to `[0, 1]`. This is what the "+5%" / "+10%" buttons send —
repeated bumps are monotonic and never silently stack with the region/season penalty.

```json
{ "threshold": 0.8 }
```
Set an explicit override directly.

```json
{ "threshold": null }
```
Clear the override — that species reverts to the global default (plus any region/season
penalty).

Response:
```json
{ "taxon_key": "ruddy_shelduck", "threshold_override": 0.8, "display_threshold": 0.65 }
```
`threshold_override` is `null` if the species has no override (just cleared, or never set).
Like every other setting, this is a read-time filter: it takes effect on the next
`/api/detections` read, no reprocessing.

### `DELETE /api/data`

Wipes every detection row, deletes every clip file on disk, and clears per-species
threshold overrides — the Settings tab's "Clear all" action, for starting fresh. Global
settings (`display_threshold` etc.) and the static species reference table are untouched:
this clears what was *observed*, not how the station is configured. Irreversible.

```json
{ "cleared": true }
```

### `POST /api/dashboard/update`

Makes the station fetch `index.html` and `tokens.css` over HTTPS and replace the copies in
`getExternalFilesDir("dashboard")` — the Settings tab's "Update dashboard" action. No
request body, and **no parameters at all**.

**The source URL is a compile-time constant** (`BuildConfig.DASHBOARD_SOURCE_URL`) and is
never taken from the request. That is deliberate and is the endpoint's entire security
model: like `DELETE /api/data`, it is unauthenticated, because the API's threat model is a
trusted LAN — and because the URL is pinned, the worst a hostile device on that LAN can
achieve is making the station re-download the owner's own dashboard from the owner's own
repository. Accepting a URL parameter would turn that into arbitrary script execution in
the browser of whoever opens the dashboard next, which is why the parameter does not exist.

**Both files are downloaded in full, validated, and only then written.** Nothing is
streamed to a live file. If either fetch fails, *neither* file is written and the
dashboard already on the phone keeps serving — a station that cannot be reached by adb
must never be able to end up with a half-written UI. Validation rejects an empty body, a
body shorter than its own `Content-Length`, and a body that does not contain the marker
the real file always has (`id="panel-live"`, `:root`) — which is what catches a GitHub 404
page or a captive-portal login page arriving with a plausible status.

Success — `200`:

```json
{
  "schema": 1,
  "source": "https://raw.githubusercontent.com/…/assets/www/",
  "checked_at_ms": 1785640470123,
  "updated": true,
  "updated_at_ms": 1785640470456,
  "files": [
    { "name": "index.html", "ok": true, "bytes": 133969, "written": true, "error": null, "reason": null },
    { "name": "tokens.css", "ok": true, "bytes": 11615, "written": true, "error": null, "reason": null }
  ]
}
```

Failure — `502` when the fetch failed or returned something that is not the dashboard,
`500` when the station could not write what it successfully downloaded. `updated` is
`false`, every file still carries its own outcome, and `written` is `false` on all of them:

```json
{
  "schema": 1,
  "source": "https://raw.githubusercontent.com/…/assets/www/",
  "checked_at_ms": 1785640470123,
  "updated": false,
  "error": "network",
  "reason": "cannot resolve raw.githubusercontent.com — no DNS answer, so the station is probably off the network",
  "files": [
    { "name": "index.html", "ok": false, "bytes": 0, "written": false,
      "error": "network", "reason": "cannot resolve raw.githubusercontent.com — …" },
    { "name": "tokens.css", "ok": false, "bytes": 0, "written": false,
      "error": "network", "reason": "cannot resolve raw.githubusercontent.com — …" }
  ]
}
```

`error` is one of:

| value | meaning |
|---|---|
| `network` | DNS failure, no route, refused connection, or timeout — the station could not reach the host |
| `tls` | the connection was made but TLS failed |
| `http` | the host answered with a status other than 200; `reason` names it |
| `truncated` | the body ended short of its declared `Content-Length` |
| `validation` | the body was empty, or was not the file it claims to be |
| `too_large` | the body exceeded the 4 MB ceiling; that is not a dashboard file |
| `write` | both files downloaded intact but could not be written to storage |
| `storage` | external storage is not mounted, so there is nowhere to write |

**`reason` is a sentence, not a code.** DESIGN.md §2d: "the update failed" is
indistinguishable from "there was nothing to update", and the three real causes here —
the station is off the network, GitHub said no, what came back was not the dashboard —
need three different responses from the person holding the phone. The dashboard surfaces
`reason` verbatim and never substitutes a generic message.

---

## 3. Publishing

`LocalPublisher` is the only implementation today. The contract is:

```kotlin
interface Publisher {
    val name: String
    suspend fun publish(batch: List<Detection>): Result<Unit>
}
```

Every detection is enqueued in a SQLite outbox before any publisher runs, and marked
delivered per-publisher afterwards. The queue exists **now**, while nothing is remote, so
that adding a remote publisher later is a new `Publisher` implementation plus a token —
not a change to the write path, the schema, or the retry logic. The queue is the seam.
