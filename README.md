# Birdmon — an always-on acoustic bird station

An Android phone, bolted somewhere outdoors, listening continuously. It records, runs
BirdNET on-device, curates what it hears, and serves a dashboard on the local network.
No cloud, no account, no audio leaving the device.

Split out of a larger biodiversity-monitoring repo in August 2026; the video/insect
pipeline stayed behind. History for the files here is preserved.

## What it does

- **Captures** 48 kHz mono continuously via `AudioSource.UNPROCESSED`, with AGC, noise
  suppression and echo cancellation disabled.
- **Classifies** with BirdNET GLOBAL_6K_V2.4 on 3.0 s windows at a 1.5 s hop (~170 ms per
  window on a OnePlus 5T, ~12 % duty cycle).
- **Curates** at read time: a display threshold, a bout rule, and a local-occurrence prior
  from BirdNET's own location/week meta-model. Nothing is filtered at write time except a
  retention floor, so moving a slider re-filters instantly and reprocesses nothing.
- **Serves** a dashboard over HTTP on the LAN: live streaming spectrogram, today's feed,
  a life list, a verification queue, day summaries and settings.
- **Verification is the point.** A species enters the life list only when a human confirms
  it by ear — never on a score. That is what makes verification worth doing.

## Repo layout

```
recorder/station/        the Android app (Kotlin, no framework, no web dependencies)
recorder/station/API.md  the data contract - written before either side, and authoritative
analysis/tools/          build_station_species.py, which generates the on-device species table
data/station_backup_*/   a 19-hour known-negative corpus (see "The corpus" below)
DESIGN.md                the project's reasoning record - read this first
.claude/skills/biomon-ui the binding design system for anything a human looks at
```

`DESIGN.md` still contains sections belonging to the insect pipeline it was split from.
Trimming it is a known outstanding task.

## Building

Requires the Android SDK and JDK 17.

```bash
cd recorder
./gradlew :station:assembleDebug
```

`local.properties` is machine-specific and not committed; Android Studio writes it, or
create it with `sdk.dir=/path/to/Android/Sdk`.

The build stamps the git commit and build time into `BuildConfig`, surfaced in
`/api/health` and in the dashboard header. A trailing `+` on the commit means the tree was
dirty when it was built — that is deliberate, because otherwise the commit names code that
is not what shipped.

## Deploying to a phone

The two BirdNET models are **not** in this repo — they are ~67 MB of binary that pip
already ships, and the app treats them as swappable files:

```bash
adb push BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite \
  /sdcard/Android/data/dk.biomon.station/files/models/
adb push BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite \
  /sdcard/Android/data/dk.biomon.station/files/models/
adb install -r recorder/station/build/outputs/apk/debug/station-debug.apk
```

Both ship with [`birdnetlib`](https://pypi.org/project/birdnetlib/) under
`birdnetlib/models/analyzer/`. Without the acoustic model the app refuses to start and
says so; without the meta-model it simply has no priors and shows no rarity flags, rather
than showing false ones.

Then open the app once — the service starts from the activity's permission callback, so
`am start-foreground-service` will not work — and grant microphone access. For an
unattended station, also grant the battery-optimisation exemption; that is the layer that
actually keeps it alive.

The dashboard is then at `http://<phone-ip>:8848`.

## Releases, and the keystore that must not change

`.github/workflows/release.yml` builds `:station` and attaches the APK to a GitHub Release.
It runs on every merge to `main` (published as `build-<run number>`), on a `v*` tag push,
and on manual dispatch. The phone installs a release by opening its page in the phone's own
browser and tapping the file — there is no adb once the station is deployed.

**Without the signing secrets below, a release is build verification only — it cannot be
installed as an update.** Gradle's debug signing config reads `~/.android/debug.keystore`
from the machine doing the building, and a CI runner has no such file, so one is generated
with a random key for that run alone. An APK signed that way will not install over the
build on the phone, and will not install over the previous CI build either; each run's key
differs from every other. The workflow labels those APKs `debugkey` and leads the release
body with the warning. Set the secrets to get releases that install over the top.

**Read this before setting the secrets.** Android will not install an APK over one signed
with a different key. The only way through is an uninstall, and uninstalling deletes
`station.db` **and** the two BirdNET models in `getExternalFilesDir("models")` — ~67 MB
that are gitignored and restorable only over adb from a computer, which is precisely what
this release flow exists to avoid needing.

So: the keystore to upload is **the existing `~/.android/debug.keystore` from the machine
that originally built and installed the app** (password `android`, alias
`androiddebugkey`, key password `android`). That is the key the phone already trusts, and
supplying that exact file means every future release installs straight over the current
build with no uninstall and no data loss. Generating a *new* keystore instead works, but
costs exactly one wipe — the first release signed with it cannot install over what is on
the phone.

```bash
base64 -w0 ~/.android/debug.keystore    # the value for STATION_KEYSTORE_BASE64
```

| repo secret | value |
|---|---|
| `STATION_KEYSTORE_BASE64` | the base64 above |
| `STATION_KEYSTORE_PASSWORD` | `android` for the debug keystore |
| `STATION_KEY_ALIAS` | `androiddebugkey` for the debug keystore |
| `STATION_KEY_PASSWORD` | `android` for the debug keystore |

**With no secrets set the workflow still completes** — `build.gradle.kts` falls back to the
debug signing config so the build produces a real APK rather than an unsigned one that
cannot be installed even once. But as above, that APK is signed with a key the runner made
up for that run: it proves the code compiles and is **not** installable over anything. Do
not hand a `debugkey` release to the phone expecting an update.

The APK's filename and the release body both name the key that was used (`debugkey` or
`releasekey`), because whether an install succeeds or costs a wipe depends entirely on it
and should not require inspecting the file to find out.

`versionCode` comes from the CI run number (`STATION_VERSION_CODE`), defaulting to `1`
locally. Sideloading an update requires it to increase.

## Updating the dashboard without reinstalling

The dashboard's `index.html` and `tokens.css` are served from
`Android/data/dk.biomon.station/files/dashboard/`, not from inside the APK — the app
copies the bundled versions there on first run. **Settings → Update dashboard** makes the
station fetch both files from `main` on GitHub and replace them, then reloads the page.

The source URL is fixed when the APK is built and cannot be set from the request; to point
a station at a fork, rebuild it. If the fetch fails, nothing is overwritten and the button
reports what actually went wrong (no network, an HTTP status, or a body that was not the
dashboard) rather than "failed". The header shows the dashboard's own last-updated stamp
beside the build commit, since after this the two can differ.

## Regenerating the species table

`recorder/station/src/main/assets/station_species.json` maps BirdNET's 6,522 output
indices to taxa, plus Danish-checklist membership and GBIF monthly fractions. It is
committed, so you only need this if something upstream changes:

```bash
python analysis/tools/build_station_species.py
```

It reads BirdNET's own label file from an installed `birdnetlib`, the DOF checklist and
`analysis/gbif_month_cache.json`, and it **refuses to write a shifted asset** — the label
list is an index-to-name map and is only correct when its length equals the model's output
width. This project has already retracted findings to that exact off-by-one.

## The corpus

`data/station_backup_20260803/station.db` is 19 hours of station output recorded **indoors
with no reachable bird audio**. All 5,499 detections across 101 species are therefore
known-false — a labelled negative set, which is rare and useful.

It bounds false positives and nothing else. It contains no true positives, so a rule that
rejects everything scores perfectly on it. Treat it as a **veto on rules that let junk
through, never a target to tune toward** — tuning to it alone produces a station that
cannot report anything, which is a worse failure than a false positive.

Curation measured against it:

| rule | false rows | false species |
|---|---|---|
| flat 0.65 threshold, count detections | 102 | 9 |
| + bout rule (≥2 bouts >60 s apart) | 81 | 6 |
| + local-occurrence prior | 50 | 4 |

## Known outstanding

- **Clips are uncompressed WAV** (288 KB per 3 s). A FLAC writer exists in the parent repo
  but is unverified on hardware, so it was deliberately not shipped.
- **`DESIGN.md` still carries insect-pipeline sections** from before the split.
- **The watchdog does not do what its comment claims.** Measured: a force-stop cancels the
  package's alarms, so the layer meant to catch "the OS killed the service" dies with it.
  What actually protects an unattended station is the battery-optimisation exemption plus
  `START_STICKY`, which recovers process death in under six seconds.
