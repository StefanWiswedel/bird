# Field Recorder (Android)

Native Kotlin recorder for the biodiversity platform. **Recorder first, identifier second**
— see `DESIGN.md` §10. This app captures audio; it does no identification.

It shares **no code** with the Python in `analysis/biomon/`. The only interface is the
**sidecar JSON** written beside each recording (§10d), whose key names match the existing
`session.json` `audio[]` fields so files drop straight into the desktop pipeline.

## Why native Kotlin (not Flutter/React Native/PWA)

One reason dominates: **`MediaRecorder.AudioSource.UNPROCESSED`**. Android's default capture
chain applies AGC, noise suppression and echo cancellation, which destroy faint
high-frequency content — precisely the 8–16 kHz band that separates real *Chorthippus*
stridulation from low-frequency rumble (§3). Cross-platform recording plugins typically
expose only the default `MIC` source with that processing baked in. Everything else
(foreground services, wake locks) is convenience by comparison.

## What it does

- One big record/stop button, sunlight-legible, one-handed.
- **Foreground service (`microphone` type) + partial wake lock** — survives screen-off and
  backgrounding. Audio needs no surface, so unlike the camera path nothing else must stay awake.
- **Segmented capture**, default 20 min/file (5/10/20/30/60 selectable) — bounds loss.
- **WAV, mono, 16-bit, highest supported rate** (48 kHz on a Pixel). No voice codec.
- **`UNPROCESSED` with capability check** → falls back to `VOICE_RECOGNITION`, and records
  **which source was actually used** in the sidecar, so mangled highs are diagnosable.
- AGC / NS / AEC explicitly disabled on the session, and the disabled set is recorded.
- **Interruptions logged** — if a call steals the mic, the gap appears in the sidecar rather
  than silently shortening the recording.
- **WAV or FLAC** — both lossless. FLAC is roughly a third the size on field audio and
  is verified end-to-end (ffmpeg, libsndfile and librosa all read it). If the encoder
  ever refuses, recording falls back to WAV mid-segment and the sidecar says so.
- **Live input level meter** with peak hold and a "no signal" warning — measured from
  the same PCM that goes to the file, so it is evidence about the recording itself.
- Recording list with playback, per-recording **observer note**, share-sheet export
  (audio + sidecar together).

## Live species candidates (optional, display only)

The app can suggest what it might have heard, on-device, with no network. **These are
candidates to check, never records.**

**Read this before trusting a name.** Fidelity was measured against the desktop
pipeline on recordings from **the same places, the same phone and the same microphone
this app was developed with**. On that in-domain set the phone reproduced the laptop's
top choice 100 % of the time. That number will not survive a change of scene: **expect
it to do worse somewhere new**, and treat every suggestion as a prompt to look and
listen rather than as an answer. The known failure mode is a *confident* cross-group
mistake — a grasshopper offered as a warbler — which is exactly the error that looks
most convincing. That is why the UI shows three possibilities and not one.

- Runs on **completed segments**, never on the live audio stream, never more than one
  at a time. Recording is never made to wait for it.
- Results go to `<recording>.live.json`, tagged `score_type: perch_fp16_live` and
  flagged `archival: false`. `biomon/import_recordings.py` **skips these files
  explicitly** — a live score must never land in `results.db` beside a full-precision
  Perch logit (DESIGN §4, §10g).
- Off by default; it costs battery and memory.

### Installing the model (once)

The 195 MB model is **not** in the APK — it is a swappable file, so the download stays
small and the feature stays optional. Without it the app simply does not offer live ID.

```bash
adb shell mkdir -p /sdcard/Android/data/dk.biomon.recorder/files/models
adb push perch_builtin_fp16.tflite /sdcard/Android/data/dk.biomon.recorder/files/models/perch_fp16.tflite
```

Regenerate the species asset (indices must come from perch-hoplite, never from the
model's `labels.csv` — see the §10f correction) with:

```bash
cd analysis && .venv-perch/Scripts/python tools/build_live_assets.py
```

## Layout

```
recorder/
├── settings.gradle.kts, build.gradle.kts, gradle.properties
└── app/
    ├── build.gradle.kts
    └── src/main/
        ├── AndroidManifest.xml
        ├── res/{values,xml}/
        └── java/dk/biomon/recorder/
            ├── AudioEngine.kt      # source selection, effect disabling, AudioSink, WAV writer
            ├── FlacWriter.kt       # lossless FLAC container (hand-built, device-verified)
            ├── RecorderService.kt  # foreground service, segments, level meter, interruptions
            ├── Sidecar.kt          # metadata JSON (the pipeline contract)
            ├── Recordings.kt       # list / share / delete / free space
            ├── Theme.kt            # MD3 tonal palettes + sunlight contrast
            ├── MainActivity.kt     # Compose UI
            └── live/               # optional on-device candidates (display only)
                ├── AudioDecode.kt  # WAV/FLAC decode, 48->32 kHz resample, framing
                ├── LiveSpecies.kt  # Danish subset + model output indices (asset)
                ├── LiveId.kt       # fp16 Perch, one segment at a time
                ├── LiveAnalyzer.kt # queue of completed segments
                ├── LiveResults.kt  # .live.json, explicitly non-archival
                └── LiveUi.kt       # the doubt-inviting card
```

## Build & install

**Verified building 2026-07-28** — APK 26 MB, `dk.biomon.recorder`, compileSdk/targetSdk 36.

Requires Android Studio (bundles JDK 21 + SDK). Run its setup wizard once so the SDK exists.

```bash
cd recorder
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ./gradlew assembleDebug
"$LOCALAPPDATA/Android/Sdk/platform-tools/adb" install -r app/build/outputs/apk/debug/app-debug.apk
```

### Working version set (do not bump casually)
| component | version | why |
|---|---|---|
| AGP | 8.13.2 | max compileSdk for AGP 8.x is **36** |
| Gradle | 8.13 | required by AGP 8.13 |
| Kotlin (+ compose plugin) | 2.3.21 | compose plugin version tracks Kotlin since 2.0 |
| compileSdk / targetSdk | 36 | matches the installed `android-36.1` platform |
| core-ktx / activity-compose / lifecycle | 1.17.0 / 1.11.0 / 2.9.4 | newer releases **require compileSdk 37 + AGP 9.1** |
| compose-bom | 2025.09.01 | same API-37 constraint |

Moving to the newest androidx means AGP 9.x, compileSdk 37 and an extra SDK platform
download. Not needed for this app.

On the phone: Settings → About → tap **Build number** ×7 → Developer options → **USB debugging**.

**No paid account is needed.** Debug builds are signed with a local debug keystore; the
$25 Google Play registration is only for publishing to the Play Store.

If Gradle cannot find the SDK, create `recorder/local.properties` (gitignored):
```
sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk
```
**Use forward slashes.** A `.properties` file treats backslash as an escape, so a
Windows path with single backslashes fails with `IOException: Invalid file path`
(the `U` in `Users` is read as a malformed unicode escape). This cost a build cycle.

## After first install — do these once

1. Grant **microphone**, **location**, **notifications** when prompted.
2. Settings → Apps → Field Recorder → Battery → **Unrestricted**. This is the main defence
   against the OS throttling a long recording. (Pixel is stock Android and relatively benign;
   other OEMs are far more aggressive — see dontkillmyapp.com.)

## Getting files to the laptop

Share sheet, or over USB:
```bash
adb pull /sdcard/Android/data/dk.biomon.recorder/files/recordings ./data/<session>/
```

## Known limits (physics, not bugs)

- Phone mics cap at **48 kHz** (24 kHz Nyquist). Fine for birds and Orthoptera — Perch's
  usable band is 0–16 kHz — but it will **not** match the ZOOM's 96 kHz.
- **Bats are out of reach** (need ~192–384 kHz). That needs different hardware.
- 48 kHz/16-bit mono ≈ **5.8 MB/min ≈ 346 MB/hour**; a 20-min segment ≈ 115 MB.
