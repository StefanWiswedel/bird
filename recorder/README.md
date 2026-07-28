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
- Recording list with playback, per-recording **observer note**, share-sheet export
  (audio + sidecar together).

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
            ├── AudioEngine.kt      # source selection, effect disabling, WAV writer
            ├── RecorderService.kt  # foreground service, segments, interruption logging
            ├── Sidecar.kt          # metadata JSON (the pipeline contract)
            ├── Recordings.kt       # list / share / delete / free space
            └── MainActivity.kt     # Compose UI
```

## Build & install

Requires **JDK 17** and the **Android SDK** (platform 35 + build-tools). Easiest path is
Android Studio, which bundles both; the command line works too.

```bash
cd recorder
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On the phone: Settings → About → tap **Build number** ×7 → Developer options → **USB debugging**.

**No paid account is needed.** Debug builds are signed with a local debug keystore; the
$25 Google Play registration is only for publishing to the Play Store.

If Gradle cannot find the SDK, create `recorder/local.properties`:
```
sdk.dir=C\:\\Users\\<you>\\AppData\\Local\\Android\\Sdk
```

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
