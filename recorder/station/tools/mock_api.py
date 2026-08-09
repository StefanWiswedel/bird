#!/usr/bin/env python3
"""
Mock bird-station API — stdlib only, no dependencies.

Implements every endpoint in station/API.md with plausible fake data so the
dashboard can be developed and reviewed without the Android side existing.

    python tools/mock_api.py                 # normal, populated
    python tools/mock_api.py --empty         # today has no detections yet
    python tools/mock_api.py --port 8848

Runtime toggles (so one running server can be flipped while screenshotting):

    GET /mock/mode?empty=1|0        today's detections on/off
    GET /mock/mode?throttled=1|0    thermal.throttled
    GET /mock/mode?listening=1|0    health.listening
    GET /mock/mode?weather=1|0      summary.weather present/null
    GET /mock/mode?sse=1|0          allow/deny SSE (0 forces the polling fallback)
    GET /mock/mode?live=<seconds>   how often a new live detection is produced
    GET /mock/mode?dashboard=ok|network|http|validation|write
                                    what POST /api/dashboard/update reports
    GET /mock/mode?recording=1|0    health.storage.recording (a bout is open)
    GET /mock/mode?clips_failed=N   health.storage.clips_failed
    GET /mock/emit                  emit one live detection right now

Deliberate deviation from the contract, for the mock only: /api/photo/{key}
returns a synthesised image/png instead of image/jpeg, because writing a JPEG
encoder in the stdlib is not worth it. Browsers render it identically.
"""

import argparse
import hashlib
import io
import json
import math
import queue
import random
import re
import struct
import sys
import threading
import time
import wave
import zlib
from datetime import datetime, timedelta, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlparse, parse_qs

SCHEMA = 1
HERE = Path(__file__).resolve().parent
WWW = HERE.parent / "src" / "main" / "assets" / "www"

# Europe/Copenhagen, August. Fixed offset avoids a tzdata dependency on Windows.
TZ = timezone(timedelta(hours=2))

STATION = {
    "id": "balcony-5t",
    "name": "Balcony",
    "lat": 55.6478,
    "lon": 12.5875,
    "tz": "Europe/Copenhagen",
}

BIRDNET = {"name": "birdnet", "version": "GLOBAL_6K_V2.4", "score_type": "birdnet_confidence"}
# A second detector on a different scale, so the dashboard is exercised on the
# "never put two score_types on one axis" rule.
ORTHOPT = {"name": "orthoptera_edge", "version": "v1.2", "score_type": "logit"}

SPECIES = [
    # key, scientific, common, rank, group, model_index, weight, conf_mu, detector, regional, in_season
    ("turdus_merula", "Turdus merula", "Eurasian Blackbird", "species", "bird", 5218, 22, 0.80, BIRDNET, True, True),
    ("parus_major", "Parus major", "Great Tit", "species", "bird", 3907, 18, 0.74, BIRDNET, True, True),
    ("passer_domesticus", "Passer domesticus", "House Sparrow", "species", "bird", 4011, 16, 0.71, BIRDNET, True, True),
    ("cyanistes_caeruleus", "Cyanistes caeruleus", "Eurasian Blue Tit", "species", "bird", 1188, 12, 0.69, BIRDNET, True, True),
    ("erithacus_rubecula", "Erithacus rubecula", "European Robin", "species", "bird", 1902, 10, 0.77, BIRDNET, True, True),
    ("pica_pica", "Pica pica", "Eurasian Magpie", "species", "bird", 4402, 9, 0.83, BIRDNET, True, True),
    ("columba_palumbus", "Columba palumbus", "Common Wood Pigeon", "species", "bird", 1440, 8, 0.86, BIRDNET, True, True),
    ("corvus_cornix", "Corvus cornix", "Hooded Crow", "species", "bird", 1501, 7, 0.79, BIRDNET, True, True),
    ("apus_apus", "Apus apus", "Common Swift", "species", "bird", 388, 7, 0.66, BIRDNET, True, True),
    ("sturnus_vulgaris", "Sturnus vulgaris", "Common Starling", "species", "bird", 5012, 6, 0.63, BIRDNET, True, True),
    ("fringilla_coelebs", "Fringilla coelebs", "Common Chaffinch", "species", "bird", 2201, 5, 0.72, BIRDNET, True, True),
    ("larus_argentatus", "Larus argentatus", "European Herring Gull", "species", "bird", 3311, 4, 0.75, BIRDNET, True, True),
    ("phylloscopus_collybita", "Phylloscopus collybita", "Common Chiffchaff", "species", "bird", 4290, 3, 0.61, BIRDNET, True, True),
    ("carduelis_carduelis", "Carduelis carduelis", "European Goldfinch", "species", "bird", 990, 3, 0.68, BIRDNET, True, True),
    # The false-positive case this feature exists for: off the Danish checklist
    # entirely, but BirdNET occasionally fires on it anyway (e.g. on a human voice).
    # Exercises the "off-list" badge and gives the +5%/+10% buttons something to fix.
    ("tadorna_ferruginea", "Tadorna ferruginea", "Ruddy Shelduck", "species", "bird", 5443, 3, 0.68, BIRDNET, False, True),
    # On the checklist, but this call landed well outside its plausible months.
    ("turdus_pilaris", "Turdus pilaris", "Fieldfare", "species", "bird", 6229, 2, 0.70, BIRDNET, True, False),
    # Non-bird group: proves the dashboard groups by taxon.group, not by "bird".
    ("pholidoptera_griseoaptera", "Pholidoptera griseoaptera", "Dark Bush-cricket", "species", "insect", 41, 4, 2.9, ORTHOPT, True, True),
    # No common name at all: the UI must fall back to scientific, prominently.
    ("chorthippus_sp", "Chorthippus sp.", "", "genus", "insect", 12, 2, 2.2, ORTHOPT, True, True),
]

# Species without a cached photo — exercises the designed placeholder.
NO_PHOTO_KEYS = {"phylloscopus_collybita", "chorthippus_sp", "sturnus_vulgaris"}

PHOTO_META = {
    "attribution": "Photo by a Wikimedia Commons contributor",
    "license": "CC BY-SA 4.0",
    "source": "https://commons.wikimedia.org/wiki/Main_Page",
}

# Dawn chorus, midday lull, small evening lift.
HOUR_WEIGHT = [0.2, 0.1, 0.1, 0.6, 3.0, 7.5, 9.0, 8.0, 6.0, 4.5, 3.2, 2.6,
               2.2, 2.0, 2.0, 2.4, 3.0, 3.6, 4.2, 4.6, 3.4, 1.4, 0.6, 0.3]

SETTINGS = {
    "display_threshold": 0.65,
    "retention_floor": 0.10,
    "repeat_required": 2,
    "repeat_window_min": 30,
    "region_season_filter_enabled": True,
}

REGIONAL_PENALTY = 0.20
SEASONAL_PENALTY = 0.15
MAX_EFFECTIVE_THRESHOLD = 0.99

# taxon_key -> override threshold, set via POST /api/species/{key}/threshold.
SPECIES_OVERRIDES = {}

MODE = {
    "empty": False,
    "throttled": False,
    "listening": True,
    "weather": True,
    "sse": True,
    "live_interval": 14.0,
    # POST /api/dashboard/update outcome. "ok" or one of the failure kinds the station
    # can actually report; the failure UI has to be developable too, and it is the half
    # that never gets exercised by accident.
    "dashboard": "ok",
    # health.storage: clips the station could not write, and whether a bout is open.
    # Toggleable so the "audio is silently not being kept" state can be looked at.
    "clips_failed": 0,
    "recording": False,
}

# When the mock last "updated" the dashboard, epoch ms, or None for "never pulled" —
# the state a freshly installed station is in.
DASHBOARD_UPDATED_MS = [None]

DASHBOARD_SOURCE = ("https://raw.githubusercontent.com/StefanWiswedel/bird/main/"
                    "recorder/station/src/main/assets/www/")

LOCK = threading.RLock()
ROWS = []           # newest last
NEXT_ID = [1]
SUBS = []           # list of queue.Queue for SSE
START_MS = int(time.time() * 1000) - 84213000
RESTARTS = 2


# --------------------------------------------------------------------------- helpers

def now_ms():
    return int(time.time() * 1000)


def iso(ms):
    return datetime.fromtimestamp(ms / 1000, TZ).isoformat(timespec="seconds")


def local_date(ms):
    return datetime.fromtimestamp(ms / 1000, TZ).strftime("%Y-%m-%d")


def day_bounds(datestr):
    d = datetime.strptime(datestr, "%Y-%m-%d").replace(tzinfo=TZ)
    return int(d.timestamp() * 1000), int((d + timedelta(days=1)).timestamp() * 1000)


def taxon_of(sp):
    key, sci, com, rank, group, midx = sp[0], sp[1], sp[2], sp[3], sp[4], sp[5]
    regional = sp[9]
    return {"key": key, "scientific": sci, "common": com, "rank": rank,
            "group": group, "model_index": midx, "regional": regional}


def effective_threshold(key, regional, in_season, st):
    with LOCK:
        override = SPECIES_OVERRIDES.get(key)
    if override is not None:
        return override
    t = st["display_threshold"]
    if st.get("region_season_filter_enabled", True):
        if not regional:
            t += REGIONAL_PENALTY
        if not in_season:
            t += SEASONAL_PENALTY
    return min(t, MAX_EFFECTIVE_THRESHOLD)


# --------------------------------------------------------------------------- data gen

def make_row(sp, ts_ms, rnd):
    detector = sp[8]
    mu = sp[7]
    if detector is BIRDNET:
        conf = max(0.11, min(0.985, rnd.gauss(mu, 0.13)))
        # A quarter of rows land below the display threshold: stored, not shown.
        if rnd.random() < 0.22:
            conf = round(rnd.uniform(0.12, 0.63), 3)
        conf = round(conf, 3)
    else:
        conf = round(max(0.2, rnd.gauss(mu, 0.8)), 2)
    r = rnd.random()
    repeat = 1 if r < 0.18 else (2 if r < 0.42 else rnd.randint(3, 9))
    with LOCK:
        rid = NEXT_ID[0]
        NEXT_ID[0] += 1
    return {
        "id": rid,
        "ts": ts_ms,
        "sp": sp,
        "conf": conf,
        "repeat": repeat,
        "has_photo": sp[0] not in NO_PHOTO_KEYS and rnd.random() > 0.08,
        "has_clip": rnd.random() > 0.07,
        # Bout-scoped clips: pre-roll + the bout + post-roll, so the durations are the
        # station's real range now (ring 8 s + post-roll 4 s at the short end, the 60 s
        # cap at the long end) rather than the model's 3 s window.
        "clip_s": round(rnd.choice([12.0, 12.0, 15.5, 21.0, 33.0, 60.0]), 1),
        # How far into the clip this detection sits. Always at least the pre-roll, since
        # the recording starts before the trigger. A few rows carry None to exercise the
        # "clip written before bout clips existed" path, which must render as unknown
        # rather than as zero.
        "clip_offset_s": None if rnd.random() < 0.12 else round(rnd.uniform(5.0, 8.0), 1),
    }


def seed_history():
    rnd = random.Random(20260801)
    today = datetime.now(TZ).replace(hour=0, minute=0, second=0, microsecond=0)
    weights = [s[6] for s in SPECIES]
    rows = []
    for back in range(7, -1, -1):
        day = today - timedelta(days=back)
        if back == 4:
            continue  # a gap in history: /api/days must not list it
        n = rnd.randint(90, 240)
        if back == 6:
            n = 14  # a very quiet day
        for _ in range(n):
            hour = rnd.choices(range(24), weights=HOUR_WEIGHT)[0]
            ts = day + timedelta(hours=hour, minutes=rnd.randint(0, 59), seconds=rnd.randint(0, 59))
            if ts.timestamp() * 1000 > now_ms():
                continue
            sp = rnd.choices(SPECIES, weights=weights)[0]
            rows.append(make_row(sp, int(ts.timestamp() * 1000), rnd))
    # A DELIBERATE DENSE BOUT on today's date. The random rows above are mostly isolated,
    # so without this the "one bout, several clips" case — the whole reason the bout owns a
    # list — would almost never appear in the mock and could not be built against.
    singer = next(s for s in SPECIES if s[0] == "turdus_merula")
    base = today + timedelta(hours=6, minutes=12)
    for i in range(14):
        # 2 s apart inside a phrase, ~22 s between phrases: one bout (60 s gap rule),
        # several clips (4 s post-roll rule).
        offset = (i // 4) * 22 + (i % 4) * 2
        ts = int((base + timedelta(seconds=offset)).timestamp() * 1000)
        if ts > now_ms():
            continue
        r = make_row(singer, ts, rnd)
        r["has_clip"] = True
        r["conf"] = round(min(0.97, 0.71 + i * 0.01), 3)
        rows.append(r)
    rows.sort(key=lambda r: r["ts"])
    with LOCK:
        ROWS[:] = rows


def serialize(row):
    sp = row["sp"]
    st = settings_snapshot()
    confirmed = is_confirmed(row, st)
    photo = None
    if row["has_photo"]:
        photo = dict(PHOTO_META, url="/api/photo/" + sp[0])
    clip = None
    if clip_ready(row):
        off = row.get("clip_offset_s")
        clip = {
            "url": "/api/clip/%d" % row["id"],
            "seconds": row["clip_s"],
            "mime": "audio/wav",
            # null, never 0, when the offset is unknown (API.md) — the two mean different
            # things and a fabricated zero is indistinguishable from a real one.
            "starts_at_ms": None if off is None else int(row["ts"] - off * 1000),
            "detection_offset_s": off,
        }
    return {
        "schema": SCHEMA,
        "id": row["id"],
        "detected_at_ms": row["ts"],
        "detected_at": iso(row["ts"]),
        "window": {"start_ms": row["ts"], "duration_ms": 3000},
        "taxon": taxon_of(sp),
        "detector": dict(sp[8]),
        "confidence": row["conf"],
        "state": "confirmed" if confirmed else "candidate",
        "repeat_count": row["repeat"],
        "regional": sp[9],
        "in_season": sp[10],
        "clip": clip,
        "photo": photo,
        "station": dict(STATION),
    }


def settings_snapshot():
    with LOCK:
        return dict(SETTINGS)


def visible_rows():
    """Rows the API is allowed to talk about at all (today may be suppressed)."""
    with LOCK:
        rows = list(ROWS)
    if MODE["empty"]:
        t = local_date(now_ms())
        rows = [r for r in rows if local_date(r["ts"]) != t]
    return rows


def is_confirmed(row, st, min_conf=None):
    sp = row["sp"]
    thr = min_conf if min_conf is not None else effective_threshold(sp[0], sp[9], sp[10], st)
    return row["conf"] >= thr and row["repeat"] >= st["repeat_required"]


# --------------------------------------------------------------------------- media

def _png(width, height, pixel):
    raw = bytearray()
    for y in range(height):
        raw.append(0)
        for x in range(width):
            r, g, b = pixel(x, y)
            raw += bytes((r & 255, g & 255, b & 255))
    def chunk(tag, data):
        c = struct.pack(">I", len(data)) + tag + data
        return c + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)
    ihdr = struct.pack(">IIBBBBB", width, height, 8, 2, 0, 0, 0)
    return (b"\x89PNG\r\n\x1a\n" + chunk(b"IHDR", ihdr)
            + chunk(b"IDAT", zlib.compress(bytes(raw), 6)) + chunk(b"IEND", b""))


PHOTO_CACHE = {}
PALETTES = [
    ((34, 40, 30), (150, 160, 120), (210, 195, 150)),
    ((28, 32, 40), (110, 130, 150), (200, 210, 220)),
    ((44, 30, 26), (160, 110, 70), (225, 195, 155)),
    ((30, 40, 38), (90, 140, 130), (200, 220, 205)),
    ((40, 34, 24), (170, 140, 70), (230, 215, 170)),
]


def photo_png(key):
    if key in PHOTO_CACHE:
        return PHOTO_CACHE[key]
    h = hashlib.sha256(key.encode()).digest()
    pal = PALETTES[h[0] % len(PALETTES)]
    cx, cy = 0.35 + (h[1] / 255) * 0.3, 0.38 + (h[2] / 255) * 0.24
    rad = 0.24 + (h[3] / 255) * 0.14
    n = 220

    def pixel(x, y):
        u, v = x / n, y / n
        t = v * 0.9 + 0.1 * math.sin(u * 5.0 + h[4])
        a, b, c = pal
        if t < 0.55:
            k = t / 0.55
            base = [c[i] + (b[i] - c[i]) * k for i in range(3)]
        else:
            k = (t - 0.55) / 0.45
            base = [b[i] + (a[i] - b[i]) * k for i in range(3)]
        d = math.hypot(u - cx, v - cy) / rad
        if d < 1.4:
            s = max(0.0, 1.0 - d / 1.4) ** 1.6
            base = [base[i] * (1 - s * 0.72) + a[i] * s * 0.72 for i in range(3)]
        g = ((x * 7 + y * 13 + h[5]) % 17) - 8
        return tuple(max(0, min(255, int(base[i] + g * 0.9))) for i in range(3))

    png = _png(n, n, pixel)
    PHOTO_CACHE[key] = png
    return png


CLIP_CACHE = {}


def clip_wav(det_id, seconds):
    ck = (det_id, seconds)
    if ck in CLIP_CACHE:
        return CLIP_CACHE[ck]
    sr = 22050
    total = int(sr * seconds)
    rnd = random.Random(det_id)
    base = 1400 + rnd.random() * 2200
    buf = io.BytesIO()
    frames = bytearray()
    for i in range(total):
        t = i / sr
        env = 0.0
        # a few short chirps scattered through the clip
        for k in range(int(seconds * 1.6) + 1):
            c0 = 0.25 + k * 0.62 + rnd.random() * 0.0
            dt = t - c0
            if 0 <= dt < 0.22:
                env = max(env, math.exp(-((dt - 0.06) ** 2) / 0.0035))
        f = base + 900 * math.sin(t * 26.0)
        s = math.sin(2 * math.pi * f * t) * env * 0.42
        s += (rnd.random() - 0.5) * 0.012
        frames += struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32000))
    with wave.open(buf, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(sr)
        w.writeframes(bytes(frames))
    data = buf.getvalue()
    if len(CLIP_CACHE) > 40:
        CLIP_CACHE.clear()
    CLIP_CACHE[ck] = data
    return data


# --------------------------------------------------------------------------- payloads

def health_body():
    st = settings_snapshot()
    up = now_ms() - START_MS
    rows = visible_rows()
    clips = sum(1 for r in rows if r["has_clip"])
    return {
        "schema": SCHEMA,
        "station": dict(STATION),
        "server_ms": now_ms(),
        "listening": bool(MODE["listening"]),
        "uptime_ms": up,
        "service_started_at_ms": START_MS,
        "restarts": RESTARTS,
        "model": {"name": "birdnet", "version": "GLOBAL_6K_V2.4", "loaded": True, "classes": 6522},
        "audio": {"sample_rate": 48000, "source": "UNPROCESSED", "window_s": 3.0, "hop_s": 1.5},
        "inference": {"last_ms": 118, "mean_ms": 124, "duty_pct": 8.3,
                      "windows_total": 402118 + int(up / 1500)},
        "thermal": {"battery_c": 41.7 if MODE["throttled"] else 33.2,
                    "status": "MODERATE" if MODE["throttled"] else "NONE",
                    "throttled": bool(MODE["throttled"])},
        # clips counts FILES, not rows — a bout clip is shared. clips_failed is reported
        # separately so "could not write audio" never hides inside "quiet day" (API.md).
        "storage": {"clips_bytes": 812334592, "cap_bytes": 17179869184,
                    "free_bytes": 115964116992, "clips": clips, "pruned_total": 0,
                    "clips_written": clips + 96, "clips_failed": MODE["clips_failed"],
                    "recording": MODE["recording"]},
        "queue": {"pending": 0, "publishers": ["local"]},
        "settings": st,
        "dashboard": {
            "stored": DASHBOARD_UPDATED_MS[0] is not None,
            "updated_at_ms": DASHBOARD_UPDATED_MS[0],
            "source": DASHBOARD_SOURCE,
        },
    }


def dashboard_update_body():
    """The POST /api/dashboard/update response, per API.md.

    The mock never actually fetches anything — it reports the shapes, including the
    failure shapes, which is what the dashboard has to render. Flip between them with
    GET /mock/mode?dashboard=ok|network|http|validation|write.
    """
    kind = MODE["dashboard"]
    files = ["index.html", "tokens.css"]
    body = {"schema": SCHEMA, "source": DASHBOARD_SOURCE, "checked_at_ms": now_ms()}

    if kind == "ok":
        sizes = {}
        for name in files:
            f = WWW / name
            sizes[name] = f.stat().st_size if f.exists() else 4096
        DASHBOARD_UPDATED_MS[0] = now_ms()
        body.update({
            "updated": True,
            "updated_at_ms": DASHBOARD_UPDATED_MS[0],
            "files": [{"name": n, "ok": True, "bytes": sizes[n], "written": True,
                       "error": None, "reason": None} for n in files],
        })
        return body, 200

    # Failure: nothing is written, and the first file's failure is the reported cause —
    # exactly as the station reports it. Note that both files still carry an outcome.
    err, reason, code = {
        "network": ("network",
                    "cannot resolve raw.githubusercontent.com — no DNS answer, so the "
                    "station is probably off the network", 502),
        "http": ("http", "HTTP 404 from raw.githubusercontent.com for index.html", 502),
        "validation": ("validation",
                       'index.html does not look like the dashboard (no id="panel-live" '
                       "in 14 bytes) — an error page, not the file", 502),
        "write": ("write", "downloaded 131072 bytes but could not write them: ENOSPC", 500),
    }.get(kind, ("network", "mock failure", 502))

    body.update({
        "updated": False, "error": err, "reason": reason,
        "files": [
            {"name": files[0], "ok": False, "bytes": 0, "written": False,
             "error": err, "reason": reason},
            {"name": files[1], "ok": True, "bytes": 7000, "written": False,
             "error": None, "reason": None},
        ],
    })
    return body, code


def summary_body(datestr):
    st = settings_snapshot()
    lo, hi = day_bounds(datestr)
    rows = [r for r in visible_rows() if lo <= r["ts"] < hi and is_confirmed(r, st)]
    by_hour = [0] * 24
    counts, best, last = {}, {}, {}
    for r in rows:
        by_hour[datetime.fromtimestamp(r["ts"] / 1000, TZ).hour] += 1
        k = r["sp"][0]
        counts[k] = counts.get(k, 0) + 1
        if r["conf"] > best.get(k, -99):
            best[k] = r["conf"]
        last[k] = max(last.get(k, 0), r["ts"])
    by_key = {s[0]: s for s in SPECIES}
    top = sorted(counts.items(), key=lambda kv: -kv[1])
    top_out = [{"taxon": taxon_of(by_key[k]), "count": c,
                "best_confidence": best[k], "last_ms": last[k]} for k, c in top]
    weather = None
    if MODE["weather"] and datestr != local_date(now_ms() - 2 * 86400000):
        seed = int(datestr.replace("-", "")) % 97
        weather = {"temperature_c": round(15.0 + seed % 11 + 0.4, 1),
                   "wind_ms": round(1.2 + (seed % 7) * 0.6, 1),
                   "cloud_pct": (seed * 7) % 101,
                   "source": "open-meteo",
                   "fetched_ms": now_ms() - 1200000}
    return {
        "schema": SCHEMA,
        "date": datestr,
        "species_count": len(counts),
        "detection_count": len(rows),
        "first_ms": min((r["ts"] for r in rows), default=None),
        "last_ms": max((r["ts"] for r in rows), default=None),
        "most_active": ({"taxon": top_out[0]["taxon"], "count": top_out[0]["count"]}
                        if top_out else None),
        "by_hour": by_hour,
        "top": top_out[:8],
        "station": dict(STATION),
        "weather": weather,
    }


def days_body():
    st = settings_snapshot()
    seen = {}
    for r in visible_rows():
        if not is_confirmed(r, st):
            continue
        d = local_date(r["ts"])
        e = seen.setdefault(d, {"date": d, "detection_count": 0, "species": set()})
        e["detection_count"] += 1
        e["species"].add(r["sp"][0])
    out = [{"date": v["date"], "detection_count": v["detection_count"],
            "species_count": len(v["species"])} for v in seen.values()]
    out.sort(key=lambda d: d["date"], reverse=True)
    return {"schema": SCHEMA, "today": local_date(now_ms()), "days": out}


# --------------------------------------------------------------------------- bouts

def solar_day(datestr):
    """Six bin edges anchored to the sun, mirroring Solar.kt.

    The mock reproduces the SHAPE, not the astronomy: sunrise/sunset are interpolated
    across the year between the Copenhagen solstice extremes. That is enough for the
    dashboard to be built against, and it moves through the year the way the real thing
    does, which is the property the strip depends on.
    """
    lo, _ = day_bounds(datestr)
    y, m, d = (int(x) for x in datestr.split("-"))
    doy = (datetime(y, m, d, tzinfo=TZ) - datetime(y, 1, 1, tzinfo=TZ)).days
    # peaks at the June solstice (doy 172)
    season = math.cos((doy - 172) / 365.25 * 2 * math.pi)
    sunrise_h = 6.5 - 2.1 * season        # 04:26 midsummer .. 08:37 midwinter
    sunset_h = 18.75 + 3.2 * season
    def at(h):
        return int(lo + h * 3600000)
    edges = [lo, at(sunrise_h - 0.65), at(sunrise_h + 1.0), at((sunrise_h + sunset_h) / 2),
             at(sunset_h - 1.0), at(sunset_h + 0.65), lo + 86400000]
    edges = sorted(edges)
    return {
        "labels": ["night", "dawn", "morning", "afternoon", "dusk", "late"],
        "edges_ms": edges,
        "sunrise_ms": at(sunrise_h), "sunset_ms": at(sunset_h),
        "civil_dawn_ms": at(sunrise_h - 0.65), "civil_dusk_ms": at(sunset_h + 0.65),
        "solar": True,
    }


def bin_of(bins, ts):
    e = bins["edges_ms"]
    for i in range(6):
        if ts < e[i + 1]:
            return i
    return 5


def clip_ready(r):
    """Whether this row's clip exists YET.

    A bout clip is written after its trigger — pre-roll, the bout, post-roll, then the
    rename — so a detection that just arrived has no clip for a few seconds and its bout
    reports `pending`, not `none`. The mock reproduces that transition deliberately: it is
    the state that did not exist before bout clips, and the one most easily rendered as
    "no audio" when it means "not finished yet" (§2d).
    """
    return r["has_clip"] and (now_ms() - r["ts"]) > 20000


def bouts_of(rows, st):
    """Group one species' rows into bouts, then bouts into clips — mirrors Database.boutsOf.

    A bout can own SEVERAL clips: boutGapSeconds (60 s) decides what is one piece of
    evidence, BoutRecorder's 4 s post-roll decides what is one continuous recording.
    """
    gap_ms = st.get("bout_gap_s", 60) * 1000
    rows = sorted(rows, key=lambda r: r["ts"])
    groups = []
    for r in rows:
        if groups and r["ts"] - groups[-1][-1]["ts"] <= gap_ms:
            groups[-1].append(r)
        else:
            groups.append([r])
    out = []
    for g in groups:
        first = g[0]
        sp = first["sp"]
        thr = effective_threshold(sp[0], sp[9], sp[10], st)
        peak = max(r["conf"] for r in g)
        expected = sum(1 for r in g if r["conf"] >= st.get("clip_floor", 0.5))
        withclip = [r for r in g if clip_ready(r)]
        # Clips split within a bout every ~4 s of silence: rows more than that apart get
        # their own file, which is what makes "one bout, several clips" real in the mock.
        clips = []
        for r in withclip:
            if not clips or r["ts"] - clips[-1]["_last_ts"] > 4000:
                off = r.get("clip_offset_s") or 5.0
                clips.append({"url": "/api/clip/%d" % r["id"], "detection_id": r["id"],
                              "seconds": r["clip_s"], "mime": "audio/wav",
                              "starts_at_ms": int(r["ts"] - off * 1000), "_last_ts": r["ts"]})
            else:
                clips[-1]["_last_ts"] = r["ts"]
        covered = len(withclip)
        age = now_ms() - g[-1]["ts"]
        # "not yet" is not "none" (§2d). PENDING_GRACE mirrors BoutRecorder's cap + post-roll.
        if covered and covered >= expected:
            state = "recorded"
        elif covered:
            state = "partial"
        elif expected and age < 79000:
            state = "pending"
        elif expected:
            state = "unavailable"
        else:
            state = "none"
        for c in clips:
            c.pop("_last_ts", None)
        out.append({
            "bout_id": first["id"],
            "taxon": taxon_of(sp),
            "start_ms": first["ts"], "end_ms": g[-1]["ts"],
            "seconds": round((g[-1]["ts"] - first["ts"]) / 1000.0, 1),
            "detections": len(g),
            "detection_ids": [r["id"] for r in g],
            "peak_confidence": peak,
            "threshold": round(thr, 3),
            "above_threshold": peak >= thr,
            "clips": clips,
            "audio": {"state": state, "seconds": round(sum(c["seconds"] for c in clips), 1),
                      "covered_detections": covered, "expected_detections": expected},
        })
    out.sort(key=lambda b: b["start_ms"], reverse=True)
    return out


def rows_for_day(datestr):
    lo, hi = day_bounds(datestr)
    st = settings_snapshot()
    return [r for r in visible_rows()
            if lo <= r["ts"] < hi and r["conf"] >= st["retention_floor"]]


def day_species_body(datestr):
    st = settings_snapshot()
    bins = solar_day(datestr)
    by_key = {}
    for r in rows_for_day(datestr):
        by_key.setdefault(r["sp"][0], []).append(r)
    species = []
    for key, rows in by_key.items():
        sp = rows[0]["sp"]
        bouts = bouts_of(rows, st)
        activity = [0] * 6
        for b in bouts:
            activity[bin_of(bins, b["start_ms"])] += 1
        withaudio = [b for b in bouts if b["clips"]]
        best = max(withaudio, key=lambda b: b["peak_confidence"]) if withaudio else None
        species.append({
            "taxon": taxon_of(sp),
            "bouts": len(bouts),
            "bouts_above_threshold": sum(1 for b in bouts if b["above_threshold"]),
            "detections": len(rows),
            "first_ms": min(r["ts"] for r in rows), "last_ms": max(r["ts"] for r in rows),
            "peak_confidence": max(r["conf"] for r in rows),
            "threshold": bouts[0]["threshold"] if bouts else st["display_threshold"],
            "best_bout_id": best["bout_id"] if best else None,
            "best_clip": best["clips"][0] if best else None,
            "activity": activity,
            "species_status": "candidate",
            "photo": dict(PHOTO_META, url="/api/photo/" + key) if key not in NO_PHOTO_KEYS else None,
        })
    species.sort(key=lambda s: (s["bouts_above_threshold"], s["bouts"]), reverse=True)
    return {"schema": SCHEMA, "date": datestr, "server_ms": now_ms(),
            "count": len(species), "species": species,
            "activity_bins": bins, "station": dict(STATION)}


def day_bouts_body(datestr, taxon_key):
    st = settings_snapshot()
    rows = [r for r in rows_for_day(datestr) if r["sp"][0] == taxon_key]
    bouts = bouts_of(rows, st)
    return {"schema": SCHEMA, "date": datestr, "taxon_key": taxon_key,
            "count": len(bouts), "bouts": bouts}


def species_body():
    st = settings_snapshot()
    agg = {}
    for r in visible_rows():
        if not is_confirmed(r, st):
            continue
        k = r["sp"][0]
        e = agg.setdefault(k, {"sp": r["sp"], "count": 0, "last_ms": 0, "best": 0, "photo": False})
        e["count"] += 1
        e["last_ms"] = max(e["last_ms"], r["ts"])
        e["best"] = max(e["best"], r["conf"])
        e["photo"] = e["photo"] or r["has_photo"]
    out = []
    for k, e in sorted(agg.items(), key=lambda kv: -kv[1]["count"]):
        out.append({"taxon": taxon_of(e["sp"]), "count": e["count"], "last_ms": e["last_ms"],
                    "best_confidence": e["best"],
                    "photo": dict(PHOTO_META, url="/api/photo/" + k) if e["photo"] else None})
    return {"schema": SCHEMA, "server_ms": now_ms(), "count": len(out), "species": out}


# --------------------------------------------------------------------------- live

def broadcast(event, payload):
    msg = "event: %s\ndata: %s\n\n" % (event, json.dumps(payload))
    with LOCK:
        subs = list(SUBS)
    for q in subs:
        try:
            q.put_nowait(msg)
        except Exception:
            pass


def emit_detection():
    rnd = random.Random()
    sp = rnd.choices(SPECIES, weights=[s[6] for s in SPECIES])[0]
    row = make_row(sp, now_ms(), rnd)
    with LOCK:
        ROWS.append(row)
    if not MODE["empty"]:
        broadcast("detection", serialize(row))
    return row


def live_thread():
    last_status = 0.0
    last_det = time.time()
    while True:
        time.sleep(1.0)
        t = time.time()
        if t - last_status >= 15:
            last_status = t
            broadcast("status", health_body())
        iv = MODE["live_interval"]
        if iv > 0 and t - last_det >= iv:
            last_det = t
            emit_detection()


# --------------------------------------------------------------------------- server

class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "MockBirdStation/1"

    def log_message(self, fmt, *args):
        if "--verbose" in sys.argv:
            sys.stderr.write("%s %s\n" % (self.address_string(), fmt % args))

    # -- plumbing

    def _cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "content-type")
        # DELETE is here because /api/data is DELETE-only: without it a cross-origin
        # "Clear all" dies at the preflight, which looks like an unreachable station.
        self.send_header("Access-Control-Allow-Methods", "GET,POST,DELETE,OPTIONS")

    def _json(self, obj, code=200):
        body = json.dumps(obj).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self._cors()
        self.end_headers()
        self.wfile.write(body)

    def _bytes(self, data, ctype, cacheable=True):
        rng = self.headers.get("Range")
        if rng:
            m = re.match(r"bytes=(\d*)-(\d*)", rng.strip())
            if m:
                a = int(m.group(1)) if m.group(1) else 0
                b = int(m.group(2)) if m.group(2) else len(data) - 1
                b = min(b, len(data) - 1)
                if a <= b:
                    part = data[a:b + 1]
                    self.send_response(206)
                    self.send_header("Content-Type", ctype)
                    self.send_header("Accept-Ranges", "bytes")
                    self.send_header("Content-Range", "bytes %d-%d/%d" % (a, b, len(data)))
                    self.send_header("Content-Length", str(len(part)))
                    self._cors()
                    self.end_headers()
                    self.wfile.write(part)
                    return
        self.send_response(200)
        self.send_header("Content-Type", ctype)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "max-age=600" if cacheable else "no-store")
        self._cors()
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self):
        self.send_response(204)
        self._cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_POST(self):
        path = urlparse(self.path).path

        if path == "/api/dashboard/update":
            body, code = dashboard_update_body()
            return self._json(body, code)

        if path == "/api/settings":
            n = int(self.headers.get("Content-Length") or 0)
            try:
                patch = json.loads(self.rfile.read(n) or b"{}")
            except Exception:
                return self._json({"error": "bad json"}, 400)
            with LOCK:
                for k in ("display_threshold", "retention_floor", "repeat_required", "repeat_window_min"):
                    if k in patch and isinstance(patch[k], (int, float)):
                        SETTINGS[k] = patch[k]
                if "region_season_filter_enabled" in patch and isinstance(patch["region_season_filter_enabled"], bool):
                    SETTINGS["region_season_filter_enabled"] = patch["region_season_filter_enabled"]
                out = dict(SETTINGS)
            return self._json(out)

        m = re.match(r"^/api/species/([^/]+)/threshold$", path)
        if m:
            key = m.group(1)
            n = int(self.headers.get("Content-Length") or 0)
            try:
                body = json.loads(self.rfile.read(n) or b"{}")
            except Exception:
                return self._json({"error": "bad json"}, 400)
            st = settings_snapshot()
            with LOCK:
                if "threshold" in body and body["threshold"] is None:
                    SPECIES_OVERRIDES.pop(key, None)
                elif "threshold" in body and isinstance(body["threshold"], (int, float)):
                    SPECIES_OVERRIDES[key] = max(0.0, min(1.0, float(body["threshold"])))
                elif "delta" in body and isinstance(body["delta"], (int, float)):
                    current = SPECIES_OVERRIDES.get(key, st["display_threshold"])
                    SPECIES_OVERRIDES[key] = max(0.0, min(1.0, current + float(body["delta"])))
                else:
                    return self._json({"error": "expected 'delta' or 'threshold' in body"}, 400)
                override = SPECIES_OVERRIDES.get(key)
            return self._json({"taxon_key": key, "threshold_override": override,
                                "display_threshold": st["display_threshold"]})

        return self._json({"error": "not found"}, 404)

    def do_GET(self):
        u = urlparse(self.path)
        path, q = u.path, parse_qs(u.query)

        if path == "/" or path == "/index.html":
            f = WWW / "index.html"
            if not f.exists():
                return self._json({"error": "index.html not built yet at %s" % f}, 404)
            return self._bytes(f.read_bytes(), "text/html; charset=utf-8", cacheable=False)

        # The station serves this too. Without it the mock renders the dashboard with no
        # tokens at all, and every review against the mock is of the wrong thing.
        if path == "/tokens.css":
            f = WWW / "tokens.css"
            if not f.exists():
                return self._json({"error": "tokens.css missing at %s" % f}, 404)
            return self._bytes(f.read_bytes(), "text/css; charset=utf-8", cacheable=False)

        if path == "/api/health":
            return self._json(health_body())

        if path == "/api/settings":
            return self._json(settings_snapshot())

        if path == "/api/days":
            return self._json(days_body())

        if path == "/api/species":
            return self._json(species_body())

        if path == "/api/summary":
            d = (q.get("date") or [local_date(now_ms())])[0]
            try:
                day_bounds(d)
            except Exception:
                return self._json({"error": "bad date"}, 400)
            return self._json(summary_body(d))

        if path == "/api/day/species":
            d = (q.get("date") or [local_date(now_ms())])[0]
            try:
                day_bounds(d)
            except Exception:
                return self._json({"error": "bad date"}, 400)
            return self._json(day_species_body(d))

        if path == "/api/day/bouts":
            d = (q.get("date") or [local_date(now_ms())])[0]
            key = (q.get("taxon_key") or [""])[0]
            if not key:
                return self._json({"error": "taxon_key is required"}, 400)
            try:
                day_bounds(d)
            except Exception:
                return self._json({"error": "bad date"}, 400)
            return self._json(day_bouts_body(d, key))

        if path == "/api/detections":
            return self._json(self._detections(q))

        if path.startswith("/api/photo/"):
            key = path[len("/api/photo/"):]
            if key in NO_PHOTO_KEYS or not key:
                return self._json({"error": "no cached photo"}, 404)
            return self._bytes(photo_png(key), "image/png")

        if path.startswith("/api/clip/"):
            try:
                cid = int(path[len("/api/clip/"):])
            except ValueError:
                return self._json({"error": "bad id"}, 400)
            with LOCK:
                row = next((r for r in ROWS if r["id"] == cid), None)
            if not row or not row["has_clip"]:
                return self._json({"error": "clip not available"}, 404)
            return self._bytes(clip_wav(cid, row["clip_s"]), "audio/wav")

        if path == "/api/events":
            return self._sse()

        if path == "/mock/mode":
            for k in ("empty", "throttled", "listening", "weather", "sse"):
                if k in q:
                    MODE[k] = q[k][0] not in ("0", "false", "no")
            if "live" in q:
                MODE["live_interval"] = float(q["live"][0])
            if "dashboard" in q:
                MODE["dashboard"] = q["dashboard"][0]
            if "recording" in q:
                MODE["recording"] = q["recording"][0] not in ("0", "false", "no")
            if "clips_failed" in q:
                MODE["clips_failed"] = int(q["clips_failed"][0])
            return self._json(dict(MODE))

        if path == "/mock/emit":
            return self._json(serialize(emit_detection()))

        return self._json({"error": "not found", "path": path}, 404)

    def _detections(self, q):
        st = settings_snapshot()
        rows = visible_rows()
        state = (q.get("state") or ["confirmed"])[0]
        limit = max(1, min(500, int((q.get("limit") or ["100"])[0])))
        min_conf = float(q["min_conf"][0]) if "min_conf" in q else None

        if "date" in q:
            lo, hi = day_bounds(q["date"][0])
            rows = [r for r in rows if lo <= r["ts"] < hi]
        if "since_ms" in q:
            since = int(q["since_ms"][0])
            rows = [r for r in rows if r["ts"] > since]
        if "group" in q:
            g = q["group"][0]
            rows = [r for r in rows if r["sp"][4] == g]

        def ok(r):
            c = is_confirmed(r, st, min_conf)
            if state == "confirmed":
                return c
            if state == "candidate":
                return not c and r["conf"] >= st["retention_floor"]
            return r["conf"] >= st["retention_floor"]

        rows = [r for r in rows if ok(r)]
        rows.sort(key=lambda r: r["ts"], reverse=True)
        out = [serialize(r) for r in rows[:limit]]
        return {"schema": SCHEMA, "server_ms": now_ms(), "count": len(out), "detections": out}

    def _sse(self):
        if not MODE["sse"]:
            return self._json({"error": "sse disabled (mock)"}, 503)
        q = queue.Queue(maxsize=200)
        with LOCK:
            SUBS.append(q)
        try:
            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream; charset=utf-8")
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "keep-alive")
            self.send_header("X-Accel-Buffering", "no")
            self._cors()
            self.end_headers()
            self.wfile.write(("event: hello\ndata: %s\n\n" % json.dumps(
                {"server_ms": now_ms(), "schema": SCHEMA})).encode())
            self.wfile.write(("event: status\ndata: %s\n\n" % json.dumps(health_body())).encode())
            self.wfile.flush()
            while True:
                try:
                    msg = q.get(timeout=15)
                except queue.Empty:
                    msg = ": keepalive\n\n"
                self.wfile.write(msg.encode("utf-8"))
                self.wfile.flush()
        except Exception:
            pass
        finally:
            with LOCK:
                if q in SUBS:
                    SUBS.remove(q)

    def send_response(self, code, message=None):
        # Suppress the default Server/Date noise volume but keep it valid.
        BaseHTTPRequestHandler.send_response(self, code, message)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8848)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--empty", action="store_true", help="today has no detections")
    ap.add_argument("--no-sse", action="store_true", help="force the polling fallback")
    ap.add_argument("--throttled", action="store_true")
    ap.add_argument("--live", type=float, default=14.0, help="seconds between live detections, 0=off")
    ap.add_argument("--verbose", action="store_true")
    a = ap.parse_args()

    MODE["empty"] = a.empty
    MODE["sse"] = not a.no_sse
    MODE["throttled"] = a.throttled
    MODE["live_interval"] = a.live

    seed_history()
    threading.Thread(target=live_thread, daemon=True).start()

    srv = ThreadingHTTPServer((a.host, a.port), Handler)
    srv.daemon_threads = True
    print("mock station on http://%s:%d/   (serving %s)" % (a.host, a.port, WWW / "index.html"))
    print("rows seeded: %d" % len(ROWS))
    try:
        srv.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
