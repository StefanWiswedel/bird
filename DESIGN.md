# Biodiversity Monitoring Platform — DESIGN.md

> Single source of truth. This chat (architect) updates it; Claude Code (builder) implements against it.
> A decision isn't real until it's written here. Design flows one direction: decide → doc → build.

---

## 0. Purpose

**Mission:** Make the invisible biodiversity around us visible, by creating tools that let
anyone discover, understand, and protect the wildlife in their everyday environment.

**Product:** A personal biodiversity companion that helps people discover and document the
living world around them.

### Implications to weigh in EVERY design decision
- **"Anyone" means no laptop, no code, no lab kit. The phone is the product.** A capability
  that only works on a developer's laptop is a prototype, not the product.
- **"Everyday environment" means balconies, parks and gardens — not reserves.** Noisy,
  shadowed, suboptimal conditions are the **target case, not the degraded one**. A pipeline
  that needs overcast light or a quiet reserve has not solved the problem; it has avoided it.
- **"Discover and understand" means in-the-moment feedback beats archival precision.** A
  candidate ID the user can confirm with their own eyes *now* is worth more than a calibrated
  logit next week.
- **"Document" means the record must still be trustworthy** — hence verification, and hence
  **live and archive scores must never be conflated** (§4 `score_type`).

### How §0 changes earlier judgements
Several decisions were taken against an archival standard and should be re-read in this light:
- Shadow sensitivity is not a capture-technique problem to be worked around by shooting only
  in open shade — that contradicts "everyday environment". It is a **software problem**.
- On-device inference was rejected in §10f against the *archive* bar (0.16-logit decision
  gap). Under §0 a **live-display** path has a different and much looser tolerance. The
  rejection stands for archive scores; it does not automatically settle live display.

## 1. What this is
A personal-first (maybe-later-product) biodiversity monitoring platform. One phone, minimal printed hardware, runs on other people's phones eventually. Two sensing modes sharing one framework:
- **Video** — insect board: fixed top-down camera over an A4 board, detect + crop + classify landings.
- **Audio** — passive acoustic ID (birds now; bats / Orthoptera later).

## 2. Core rules — READ THIS FIRST

### 2a. Unify at the ends, keep the middles separate.
- Shared: capture-in, results-out. One config system, one results store, one review/verification UI, one export path.
- Separate: the processing pipelines never merge. Do NOT build a fusion layer that makes audio and video inform each other. Two independent detectors writing to one table.

```
          ┌──────────── shared spine ────────────┐
capture → │ session/config → [PIPELINE] → results │ → review → export
          └───────────────────────────────────────┘
                              │
          video path: framediff → crop → classify (Perch-less, CV)
          audio path: Perch embed → regional linear probe
```

### 2b. One consistent rule everywhere beats optimal-per-video.
**Do not tune parameters per capture or per session.** Detection/threshold parameters are
fixed platform-wide; a capture either works under the common rule or the *rig/capture*
gets fixed, not the parameter.

Rationale: the scientific output is **comparability across sessions** — visit rates on
day A vs day B, colour A vs colour B. If every video has its own threshold, cross-session
comparison is meaningless, and the discontinuity is invisible in the results table.
A slightly worse per-video result is an acceptable price for a comparable series.

Corollary: tuning knobs may exist for *debugging* (e.g. `paper_thr`, `bg_model` in
`video_pipeline.process`), but they stay at their defaults in `session.json`. Any change
to a default applies to **all** sessions, is re-run across the corpus, and is recorded in
the changelog as a discontinuity.

### 2c. Optimise the binding constraint, not the visible one.
Before improving a component, ask what the *current* bottleneck actually is. As of
2026-07-28 the corpus is **22 insect detections** — so classifier accuracy is not the
limit; **visit rate** is, and that is governed by weather, site and season, not code.
Model work that improves labels on 22 detections is effort spent at the wrong end.
Corollary: prefer work that is unblocked and compounding (more captures, licence
cleanliness, the results store) over work that is merely conspicuous (model swaps).

## 3. Model decision — Perch for audio (BirdNET retired on validation, not on schedule)
- **All audio moves to Perch 2.0** (Apache-2.0, commercially clean). Birds too — Perch is SOTA on BirdSet.
- **Keep BirdNET installed and working until Perch is validated. Delete only on evidence, not on schedule.** It's CC BY-NC-SA (non-commercial) so it cannot ship, but it stays as the prototyping + sanity-check reference until Perch demonstrably matches it.
- Perch is a 5-second-window model. Live/near-real-time UX is our engineering on top of it.
- **Bird classifier: use Perch 2.0's built-in classification head directly — do NOT train a linear probe.** The head already covers most bird species (trained on Xeno-canto). Apply a **Danish species list as an output filter**, and **tune per-species detection thresholds on our own recordings** (Perch's logits are uncalibrated and unreliable for rare species; the docs explicitly recommend own-data threshold tuning). The BirdNET comparison is a **sanity check, not a gate.**
- **Orthoptera: ✅ TESTED 2026-07-28 — COVERED. No separate Orthoptera probe is needed.** Checked the actual 14,795-class list (not the blog claim): **17/17 unique Danish Orthoptera species present**, incl. Roesel's bush-cricket (`Roeseliana roeselii`), dark/speckled/great-green bush-crickets, both coneheads, oak bush-cricket, field/meadow/common-green/lesser-marsh/bow-winged/mottled grasshoppers, field/wood/house crickets and mole cricket. 218 Orthoptera classes across common European genera, plus cicadas. (The only "misses" were superseded synonyms — `Metrioptera roeselii`, `Chorthippus parallelus` — both present under current names.) **Consequence: Orthoptera uses the same head → Danish-list filter → per-species threshold pattern as birds; the planned probe and InsectSet459 training are unnecessary for these species.**
- **Linear probes are a fallback only** for taxonomic groups the head does not cover.
- **The canonical argument for per-species thresholds — the eagle owl.** In the 210726 meadow
  capture (open grassland, **10:00**), Perch scored **`Bubo bubo` at 12.3** — *above* several
  genuine detections, and from an in-list, geographically plausible species. Eagle owls are
  nocturnal; at that hour in that habitat it is near-certainly a false positive.
  - *Scale, stated precisely (corrected 2026-07-28):* **1 window out of 661** clears 11.0.
    An earlier note of "287 rows" conflated **rows stored above the ingest floor (5.0)** with
    **detections above threshold** — the floor exists precisely so weak rows are retained, so
    counting them as detections overstates the case. It is one strong false positive, not a
    sustained one.
  - *Cause — traffic hypothesis TESTED and REFUTED.* Perch's ~200 non-species sound-event
    classes were checked for co-occurrence: correlation between the `Bubo bubo` logit and the
    max vehicle-class logit is **−0.015** (any sound-event class: **+0.001**), and mean vehicle
    logit is **4.24** in the suspect window vs **4.20** elsewhere. Road noise does **not**
    explain it, so a noise-class suppressor would not have worked — worth knowing before
    building one. (`biomon/noise_probe.py` retains the test for reuse on future suspects.)
  This case still shows:
  1. **No global threshold can work.** A cut that keeps the real birds (true positives 11–14)
     also keeps this. Only a per-species threshold separates them.
  2. **A species list is not a plausibility filter.** Being Danish and in-range is not enough —
     real plausibility needs **time-of-day and season**, which the current filter has no notion
     of. A diurnal/nocturnal flag and a seasonal window per species are the obvious next layer
     (future work, not built).
- **MEASURED PRECISION — the `020825` Orthoptera "anchor" is NOT clean (2026-07-28).**
  Observer verified 43 `Chorthippus brunneus` windows under a strict presence criterion
  (`y` = grasshopper audible, `n` = not audible, `s` = unsure): **14 confirmed, 29 rejected
  — 33 % precision.** Precision by score band: 10.5–11.0 → 18 %, 11.0–11.5 → 33 %,
  11.5–12.0 → 36 %, 12.0–12.6 → 44 %. Mean score confirmed **11.59** vs rejected **11.43** —
  a gap of **0.16** on a band spanning ~2.
  **The logit barely separates presence from absence for this species, so no threshold choice
  fixes it.** Precision rises with score but only 18 %→44 %, never reaching usable.
  **Consequence: downgrade the earlier "020825 is a clean calibration anchor" framing.**
  It is a *labelled* dataset, not a clean one; 33 % precision cannot anchor a threshold.
  What it does establish is that per-species thresholds alone are insufficient here.
- **Why the logit fails here — two follow-up checks on the same 43 labels (2026-07-28).**
  - **(1) Temporal persistence: supported, not established.** Run length computed over *all*
    detections (not just the verified subset). At a ≥11.0 cut: isolated windows 22 % precise,
    runs of 2 → 33 %, runs of 3–5 → 18 %, **runs of 6+ → 100 % (6/6 confirmed, 0 rejected)** —
    and that run is exactly the t 1040–1065 bout. 24 of 29 rejects are isolated windows or
    pairs. **But**: the 6+ bucket is *one bout*, i.e. n=1 event and 6 correlated windows, not
    6 independent samples; and at a ≥9.5 cut long runs are only 48 % precise because runs merge.
    Promising, still unproven.
  - **(2) What is in the rejected windows: low-frequency noise — the observer's ears were right.**
    | group | n | 8–16 kHz energy share (mean / max) | <1 kHz share |
    |---|---|---|---|
    | confirmed, in bout | 6 | **0.121 / 0.281** | 0.71 |
    | confirmed, outside bout | 8 | 0.005 / 0.025 | ~0.99 |
    | **rejected** | 29 | **0.000 / 0.002** | **0.998** |
    Every rejected window is **essentially devoid of 8–16 kHz energy** and ~99.8 % of its energy
    sits **below 1 kHz**. Their top competing classes are `Vehicle`, `Motor_vehicle_(road)`,
    `Engine`, `Wind`, `Thunder`/`Thunderstorm`, `Boom`, `Human_voice`, plus American
    low-frequency taxa (`Lithobates`, `Bubo virginianus`, `Strix varia`, `Scaphiopus`).
    **Perch is scoring `Chorthippus brunneus` on low-frequency rumble** — the same failure mode
    as the eagle owl, now with a spectral fingerprint.
  - **The 32 kHz / roll-off caveat is REFUTED for these rejects.** Perch resamples to 32 kHz
    (usable band 0–16 kHz), and *Chorthippus* stridulation carries its energy at 8–16 kHz. If
    the rejects held inaudible high-frequency cues they would show HF energy — they show
    **none**. So these are genuine false positives, not a listening limit.
  - **A spectral gate separates what the logit cannot**: bout 0.18–0.28 HF share vs ~0.000 for
    everything else — a clean margin where the logit gap was 0.16. **`tools/strid_scan.py`
    already measures exactly this** (6–24 kHz band-limited envelope, pulse periodicity,
    roll-off) and independently flagged t≈992, 1035, 1052, 1062, 1070 — matching the observer's
    confirmations (995, 1040–1065). **Reconsider its retirement**: it is not redundant with
    Perch, it supplies the discriminating feature Perch's head lacks for Orthoptera.
  - *Honest wrinkle*: the 8 confirmed windows **outside** the bout are spectrally
    indistinguishable from the rejects (HF ≈ 0.005). Either they are distant/attenuated song, or
    those particular labels are less reliable than the in-bout ones. The label set itself may be
    noisy for isolated windows.
- **Hypothesis worth testing next: temporal persistence as a discriminator.** The confirmed
  Orthoptera bout in `020825` occupies **7+ consecutive 5 s windows** (t 1040–1070, logits
  11.6–12.5) — a continuous ~35 s song. The eagle-owl false positive is **1 isolated window**.
  A real singer produces a *run* of windows; a spurious peak often does not. This generalises
  across species (unlike a per-species threshold) and is cheap to compute from data already
  stored. **Not yet built or validated** — it is an observation from two cases, not a rule.
- **Empirical confirmation on our own audio (2026-07-28).** Perch was run over the five Aug-2025 stridulation bouts that `strid_scan.py` had independently flagged (regular ~48.9 Hz pulse train, broadband to 17 kHz, t≈1035–1070 s). Perch returns **`Chorthippus brunneus`** (field grasshopper — common in Denmark, sings in late summer) as **top-1 on 5/5 clips**, logits **10.2–12.3** against runners-up of 5.5–7.2, and those runners-up are themselves mostly Orthoptera (*Euchorthippus*, *Chorthippus jacobsi*, *Myrmeleotettix maculatus*, *Arcyptera fusca*) — i.e. confidently in the right taxonomic neighbourhood.
  - **What the corroboration does and does not cover.** The two methods agree only that **an orthopteran was stridulating here** — `strid_scan` measures *pulse periodicity* and cannot separate *Chorthippus brunneus* from any other grasshopper. The **species call rests on Perch alone: uncalibrated, single model, single bout.** Do not state this as "two independent methods agree on the species."
  - It also **corrects BirdNET**, which had labelled this bout *"Fork-tailed Bush Katydid"* — a North American Tettigoniid. BirdNET has no Danish Orthoptera classes, so it snapped to the nearest available one; the same nearest-class failure mode as the video classifier's ant→`fly_small`. Perch has the actual Danish species.
  - Caveat kept in view: Perch logits are uncalibrated and this is one model on one bout — strong, not certain, until threshold-tuned.

## 3b. Adversarial-audio field test — session `280726` (findings only, nothing changed)

Three BirdNET Live app recordings, Copenhagen park, 2026-07-28, deliberately hostile:
wind 6.1 m/s, bike noise, children. lat/lon read per-recording from each
`.metadata.json` (55.6316–55.6343 N, 12.5750–12.5826 E). FLAC is read natively.

**Comparison caveat.** The bundled `.selections.txt` is **BirdNET+ V3.0-preview3.1
Global 10K-pruned** (9,789 classes spanning birds, amphibians, mammals, insects) **with a
location geomodel**, on-device, already internally thresholded. That is a *newer, different*
model from the BirdNET V2.4 used elsewhere here, and only its accepted detections are in the
file. So this is Perch-vs-BirdNET-V3-app, not a like-for-like score contest.

**Head-to-head (Perch @ 11.0):** BirdNET-app 13 species, Perch 2. Shared: *Columba palumbus*
(BirdNET 0.88 / Perch 12.7), *Cyanistes caeruleus* (0.74 / 11.6). **Perch-only: none.
Same-window disagreements: none** — where both fire, they agree. Everything else is
"BirdNET only", and for the in-list ones Perch *did* score them, below threshold:
Rook 10.2, Barn Swallow 10.3, Gull 9.9, Jay 8.3, Mistle Thrush 8.1, Great Tit 6.5.

**The Harbor Seal — what actually protected us.** `Phoca vitulina` scored 0.66–0.90 in the
app, across all three files. Two independent things went right, and the weaker one is the
filter:
1. **Perch was not fooled**: max `Phoca vitulina` logit **0.92** over the whole file, and
   **−0.87 / −0.33** in the exact windows the app called seal at 0.86/0.90.
2. **Our Danish list would have excluded it anyway** — `Phoca vitulina` *is* in Perch's
   classes (as are `Halichoerus grypus`, `Orcinus orca`), so the filter is doing real work.
   **But be honest about why:** our list is birds + Orthoptera, so it excludes *all* mammals
   by construction. It did not detect implausibility; it has a narrow taxonomic scope.
   Same for `Apis mellifera`, `Alopochen aegyptiaca`, `Branta bernicla` — filtered because
   absent from the list, and the latter two are arguably genuine list gaps (both occur in DK).
Note also the app had a **location geomodel and still reported a marine mammal in an inland
park** — reinforcing §3: a range filter is not a plausibility filter; habitat and time matter.

**Score distribution — noise does NOT inflate false positives; it suppresses true ones.**
This is the opposite of the concern that prompted the test.

| session | capture | dur | max logit | ≥11 | ≥11 per min |
|---|---|---|---|---|---|
| 020825 | aug2025 | 3314 s | 13.0 | 61 | 1.10 |
| 210726 | woodland(vid) | 1226 s | 13.7 | 28 | 1.37 |
| 210726 | meadow | 3307 s | 12.8 | 22 | 0.40 |
| **280726** | **park1** | 258 s | **11.6** | 1 | 0.23 |
| **280726** | **park2** | 337 s | **12.7** | 2 | 0.36 |
| **280726** | **park3** | 617 s | **10.3** | **0** | **0.00** |

p99 in the park (9.0–10.1) is **at or below** the clean sessions (9.2–11.4), and no park
capture reaches the 13+ maxima seen in clean audio. Meanwhile the *noise* classes score
high — `Vehicle` 10.6, `Motor_vehicle_(road)` 10.3, `Engine` 10.3 — i.e. Perch correctly
identifies the noise as noise rather than as a species.

**OBSERVER GROUND TRUTH — this is now verified evidence, not inference.** The observer was
present throughout and recorded **session-level** ground truth (species present at the site;
stored in `session_ground_truth`, deliberately *not* as row-level `verified_status`, since
presence at a site is a weaker claim than any given window being correct):

| species | observer | Perch max | passes @ 11.0 |
|---|---|---|---|
| *Columba palumbus* (Wood Pigeon) | **certain, observed** | 12.7 | yes |
| *Corvus frugilegus* (Rook) | **certain, observed** | **10.2** | **NO — false negative** |
| *Hirundo rustica* (Barn Swallow) | **certain, observed** | **10.3** | **NO — false negative** |
| *Cyanistes caeruleus* (Blue Tit) | probable, not observed | 11.6 | yes |

**Two of the three species the observer is CERTAIN about are missed at 11.0**, while the only
*probable, unobserved* one passes. Both misses sit just **0.7–0.8 below** the threshold, and
BirdNET-app independently flagged both (Rook 0.89, Barn Swallow 0.90). This is direct,
observer-confirmed evidence — not an inference from score distributions — that **a threshold
tuned on clean audio produces false negatives in noisy conditions.**

The **ingest floor is vindicated** by this case: the missed detections are already stored
(Rook 7 windows, Barn Swallow 5 windows above the 5.0 floor), so lowering a threshold later
recovers them with **no audio reprocessing** — exactly what storing raw scores was for.

**Implication for threshold setting (not acted on — one session):** any eventual per-species
calibration should be conditioned on recording conditions, or set from noisy audio too. But
that needs more than one session, and the ground truth above is *session-level* — it can
prove a species was missed, but cannot label which window was right, so it constrains
thresholds without supplying row-level training labels.

## 3c. Moving-shadow trial — session `280726_1` (findings only, nothing changed)

§0 says noisy, shadowed conditions are the **target case, not the degraded one**, so
moving vegetation shadow was treated as a software problem and measured rather than
worked around. Seven preprocessing variants, run through a **verbatim copy** of the
white-mode pipeline (`tools/shadow_trial.py`) with two injection points; the board
mask always comes from the raw median frame and the classifier always sees raw
pixels, so nothing but the variant changes. Ten clips, 60–90 s: `mount2` (heavy
moving shadow, **0 real insects**) as the failure case, `mount5`/`mount6` (13 insects)
as the recall controls. Scored by `tools/shadow_report.py`.

**The baseline ceiling is 11/13, not 13/13.** All 13 ground-truth insects do fall
inside the clip windows, but the baseline itself only recovers 11 when run on short
clips — the rolling background model has far less history than in the full-length run
the ground truth came from. That is a property of the harness. **Variants are scored
against baseline, not against 13.**

| variant | mount2 blobs | vs base | cand tracks | insect FP | recall | precision | detect cost |
|---|---|---|---|---|---|---|---|
| baseline | 27,390 | — | 127 | 1 | 11/13 | 0.923 | 1.00× |
| homomorphic σ25 | 22,543 | −18 % | 47 | 0 | **10/13** | 1.000 | 1.48× |
| homomorphic σ50 | 28,391 | +4 % | 94 | 1 | 11/13 | 0.923 | 2.43× |
| homomorphic σ100 | 28,023 | +2 % | 128 | 1 | 11/13 | 0.933 | 4.09× |
| chromaticity R/(R+G+B) | 9,127 | −67 % | 150 | 1 | **9/13** | 0.889 | 1.21× |
| **MOG2 `detectShadows`** | 86,131 | +214 % | 322 | **0** | **11/13** | **1.000** | **1.07×** |
| homomorphic σ50 + MOG2 | 71,471 | +161 % | 191 | 0 | 11/13 | 1.000 | 3.71× |

**The premise is weaker than it looked.** Baseline produces **one** false insect
verdict across 4.5 minutes of heavy moving shadow — from 27,390 raw blobs. The track
filters and the classifier are already absorbing the shadow; what it costs is
**compute**, not precision. "Shadow forces shooting only in open shade" is not
supported by the end-to-end numbers.

**Blob count is a misleading headline, and this trial shows why.** MOG2 has the
*best* output and **triples** the blob count: vetoing shadow pixels fragments large
blobs, and fragments of a formerly oversized (>`AMAX`) blob land back inside the
accepted area range. Blob count and false-positive count are close to decoupled —
optimise the one that reaches the report.

**Recall is the binding constraint, and it eliminates the cheap wins.** The two
variants that cut blobs hardest both lose real insects: chromaticity loses two bees
(its premise is broken on a *white* board — normalising by R+G+B discards exactly
the intensity signal a grey shadow on white paper carries), and homomorphic σ25
loses the single mount6 insect. Either would have looked like a success on blob
count alone.

**Best variant, if one is ever adopted: MOG2 with `detectShadows=True`**, shadow
pixels vetoed from the foreground mask — the only variant that holds baseline recall
*and* removes the false positive, at **+7 % detection time**, where every homomorphic
variant costs 1.5–4× for no gain.

### CORRECTED ASSUMPTION — shadow costs compute, not accuracy (decided 2026-07-29)

**The assumption that started this track was wrong, and it is retired here.**

> ~~"Shadow sensitivity forces shooting only in open shade or overcast, which
> contradicts §0's claim that everyday conditions are the target case."~~

The measurement does not support it. **Baseline emits ONE false insect verdict across
4.5 minutes of the heaviest moving shadow in the corpus** — out of 27,390 raw blobs.
The track filters and the classifier were already absorbing the shadow. What shadow
actually costs is **compute** (blobs, candidate tracks, classifier calls), not
**accuracy**. Nothing about shooting conditions needs to change, and §0's "noisy,
shadowed conditions are the target case" was already being met.

**Decision: MOG2 is NOT adopted.** Deprioritised. The win on offer is one false
positive on 4.5 minutes; that is not evidence at the scale a pipeline change needs,
and adopting it would spend a permanent complexity and compute cost against a problem
that is not hurting the output.

**Kept, not deleted:** `tools/shadow_trial.py` (the harness) and
`tools/shadow_report.py` (the scorer) stay, with the per-clip CSVs, so re-running is
cheap. **Re-run over the FULL `mount2`/`mount5`/`mount6` captures when convenient, and
adopt only if the false-positive reduction holds at that scale.** Session `290726_0`
(same rig, heavy grass shadow throughout, ~1h48m) is the first at-scale opportunity.

**The methodological lesson is the durable output of this track.** Blob count was the
metric the premise was built on, and it is close to decoupled from what reaches the
report — the *best* variant tripled it. Optimise the number that reaches the report.

## 4. Results schema (the unification point)
One table, both pipelines write to it. Draft columns:
- `id`, `session_id`, `module` (e.g. insect_board | birds | orthoptera), `timestamp`
- `media_path` (crop image or audio clip)
- `taxon_pred`, `confidence`, `verified_status` (unverified | confirmed | corrected | rejected)
- pipeline-specific features stored alongside (e.g. audio: pulse_rate, peak_freq, bout_dur, temp; video: blob_area_mm, residence_s)
- `lat`, `lon`, `verified_taxon`
Store as SQLite (single file, zero-admin).

**Ground truth has two levels, and they must not be conflated.** `results.verified_status`
is a **row-level** judgement ("this detection is correct"), produced by `biomon/verify.py`.
`session_ground_truth` is a **session-level** presence claim ("this species was at this site"),
which an observer can assert without being able to vouch for any particular window. Session
ground truth can therefore prove a **false negative** (species known present, never detected)
and constrain thresholds — but it must **never** be used to set `verified_status`, nor as
row-level labels for calibration or training. Absence from it is non-observation, not absence.

**Thresholds live in the DATA, not the code.** `results` stores every detection above a
low **ingest floor** (a storage-retention decision, currently logit ≥ 5.0) with its **raw**
score. Detection thresholds sit in a `thresholds` table (`detector, module, taxon` →
`threshold`; `taxon='*'` is the default) and are applied at **query/review time** by the
`results_scored` view, which exposes a `passes` flag. Consequences:
- **Re-tuning is an `UPDATE`, never a reprocess.** Per-species thresholds can be calibrated
  incrementally as the verified corpus grows — the only way they will ever actually get
  calibrated, since calibration needs verified detections per species and we have almost none.
- **Never drop a class to suppress false positives — raise its threshold instead.** Dropping
  a class means the species can *never* be detected; a high threshold means it is detected
  only on strong evidence. Rare species (e.g. Ortolan Bunting, seen at 11.9) therefore stay
  in the list as **flagged candidates for review**, not silent absences.
- Below-threshold rows are retained as reviewable candidates, not discarded.

**Scores are not interchangeable — `confidence` carries a `score_type`.** BirdNET returns a
0–1 confidence; **Perch returns raw, uncalibrated logits** (e.g. 13.67). Store the **raw
logit** and record `score_type` in `features_json` (`birdnet_confidence` | `perch_logit`).
Do **not** squash Perch logits into 0–1: a sigmoid-squashed logit is not the same quantity
as a BirdNET confidence, so the column would *look* comparable while meaning different
things — worse than an obviously different number. Anything needing comparison converts
explicitly at query time, and per-species thresholds are set per `score_type`.

### 4b. Weather is a covariate, stored separately from the session record
Visit rate is the binding constraint on the video module (§2c), and it is governed by
weather, site and season rather than by anything in the code. Weather is therefore recorded
per session in a **separate `session_weather` table**, not as columns on `sessions`:

- `sessions` mirrors the hand-authored `data/<id>/session.json` — identity and configuration.
- `session_weather` is **derived and re-fetchable**, so it carries its own provenance
  (`source`, `fetched`, the grid point Open-Meteo actually served, and the raw hourly rows the
  means were reduced from). A re-fetch is one upsert and cannot corrupt the session record.
- "We looked and there is no data" is stored explicitly (`source='missing'` plus a note),
  never as a null that is indistinguishable from "never asked".

Values are **duration-weighted means over the hours the captures actually overlap**, not
calendar-day means: a session running 16:05–18:16 weights hours 16/17/18 by the seconds
recorded in each. Source is the Open-Meteo archive API (free, no key, no attribution burden).

**What the backfill shows, and what it does not.** All 8 sessions backfilled; 7 succeeded,
1 (`020825_0`) has no coordinates and is recorded as missing. Two correlations were then run:

| question | n | best coefficient | verdict |
|---|---|---|---|
| wind speed vs Perch score suppression | 4 | Spearman ρ = −0.80 (p = 0.20) | **not significant** |
| temperature vs insect visit rate | 5 | Pearson r = +0.18 (p = 0.77) | **not significant** |
| cloud cover vs insect visit rate | 5 | Pearson r = −0.96 (p = 0.009) | significant **and confounded** |

The wind result points the way the earlier three sessions suggested, but at n = 4 a single
session flips the sign and |r| would have to exceed 0.95 to clear p < 0.05. Temperature spans
only 2.2 °C across the whole corpus — there is essentially no signal to correlate against.
The one coefficient that does clear significance is **confounded with site**: the only
clear-sky session is also the only Kalvebod Fælled meadow session, every overcast one is the
home garden. Cloud cover is also the least trustworthy backfilled field (disagrees with the
BirdNET-app figure by 5–67 pp; the observer logged one session as "sunny" where Open-Meteo
says 73 %).

**All of this is SUGGESTIVE and UNCONFIRMED.** Do not tune a threshold, a detector or a field
protocol on it. The fix is more sessions, not more statistics.

## 5. Datasets
- **AMI dataset** — ⚠️ **premise corrected 2026-07-28 after verification. AMI is a MOTH dataset, not a general insect dataset.** Verified facts:
  - **AMI-GBIF** ≈2.5M images = **5,364 moth species** / 1,734 genera / 77 families, from citizen-science + museum photography (varied angles/backgrounds — **not** top-down board crops, so the "framing matches our geometry" claim was wrong).
  - Plus ≈**350k non-moth images** labelled at order/family level (Diptera, Coleoptera, Hemiptera, Orthoptera, Araneae, Odonata, **Formicidae**, Ichneumonidae, Trichoptera…), present only as the negative class for a binary moth/non-moth task.
  - **AMI-Traps** (2,893 images / 52,948 labelled insects) are from **nocturnal UV light traps**, 22:00–05:00.
  - Benchmark tasks are: binary moth/non-moth · moth species · moth genus/family. **There is no general-insect / daytime order-level classifier to drop in.**
  - Licences: **dataset MIT** (Zenodo, permissive ✔). **`ami-data-companion` code is AGPL-3.0** — copyleft, a commercial-use concern of the same kind that is retiring BirdNET; avoid depending on that codebase for anything shipped. Its ResNet50 moth models (incl. a West-European/Denmark–UK model, F1 0.784 over 244 moth species) auto-download at runtime; weights are not separately licensed in the paper.
  - **Consequence**: AMI's *moth* half is wrong for the daytime `insect_board`, but is an excellent fit for a future **`moth_sheet`** module (nocturnal, UV, and a Denmark/UK model already exists). AMI's *non-moth* half is the part relevant to `insect_board` — as **training data** for a coarse order-level classifier, not as a ready model.
- ~~**InsectSet459**~~ — **NO LONGER NEEDED (2026-07-28).** It was scoped as seed data for training an Orthoptera probe; Perch 2.0's head already covers **17/17 Danish Orthoptera species** (§3), so there is no probe to train and no per-source XC/iNat licence audit to do. Keep only as a future *evaluation* set if we ever want to benchmark Perch on insect audio.
- Public bird data (BirdSet/BEANS) to ground-truth the Perch bird path.

## 6. Capture contract (still partly open — blocks the native app)
- [ ] **fps knee test** — stills vs video? (run ffmpeg -vf fps=N decimation on existing clip; find where visits disappear)
- [ ] capture rate + **segmenting** (fixes the 4.6GB / ~38-min single-file cutoff)
- [ ] **detect on-device vs ship raw** — current lean: keep raw around detections so new models can re-run on old captures; on-device detect only to decide what to keep
- [ ] focus/exposure lock behaviour
- App is capture-and-plumbing; the classifier is a swappable file. App can be built against a placeholder model.

## 7. Hardware (context, not code)
- Printed A4 tray + post + phone platform; square-post/socket + side thumbscrew coupling; north-side orientation for shadow; locked manual focus. (Frame essentially designed; printing pending.)

## 8. Open questions / parking lot
- Native app (APK) needed for unattended deployment; PWA insufficient for background camera. Not started.
- Domain mismatch: public clips are recordist-quality; deployment audio is cheap-mic-in-a-garden. Band-limit training to mic bandwidth and/or add a few own recordings per region for calibration.
- Verification-as-a-feature: the differentiator competitors lack. Human-in-the-loop confirm/correct that feeds a defensible local dataset.
- Regional expansion = new probe per region (filter dataset to local species, retrain probe; embedding model never changes).

## 9. Changelog
- (2026-07-29, **corrected assumption**) **Shadow costs compute, not accuracy — MOG2 NOT adopted, track deprioritised** (§3c). The premise that opened Track A ("shadow sensitivity forces shooting only in open shade or overcast") is **retired**: baseline emits **one** false insect verdict across 4.5 min of the heaviest moving shadow in the corpus, from 27,390 raw blobs. The filters and classifier were already absorbing it, and §0's "shadowed conditions are the target case" was already being met. A one-FP win on 4.5 min is not evidence at the scale a pipeline change needs, and adopting MOG2 would spend permanent complexity and compute against a problem that is not hurting the output. Harness (`tools/shadow_trial.py`) and scorer (`tools/shadow_report.py`) kept; re-run over the FULL mount captures when convenient and adopt only if the reduction holds at scale. Durable lesson: **blob count is close to decoupled from what reaches the report** — the best variant *tripled* it.
- (2026-07-29) **Moving-shadow trial: MOG2 measured best, premise refuted** (§3c) — *superseded by the corrected-assumption entry above; recommendation withdrawn, findings stand.* Seven variants over a verbatim pipeline copy, 10 clips. **Baseline already emits only 1 false insect verdict across 4.5 min of heavy moving shadow** despite 27,390 raw blobs — the track filters and classifier absorb shadow already, so it costs compute, not precision, and "shadow forces shooting only in open shade" is not supported end-to-end. **Blob count is a misleading metric**: the best variant (MOG2 `detectShadows`) *triples* it, because vetoing shadow pixels fragments oversized blobs back into the accepted area range. Recall killed the cheap wins — chromaticity (−67 % blobs) loses 2 bees and its premise is broken on a white board; homomorphic σ25 (−18 %) loses the mount6 insect. **Recommended: MOG2 shadow veto** — holds baseline recall 11/13, removes the FP, +7 % detect time, where every homomorphic variant costs 1.5–4× for nothing. Caveat stated: the gain is **one** false positive; promising, not proven. **Nothing changed in the pipeline.**
- (2026-07-29) **Live display passes where archive fails — §10f re-opened as §10g.** On a *targeted* set (the 41 non-leaked windows `results.db` actually flagged at ≥ 11) the fp16 embedder + linear head names **the same species as the laptop 95.1 %** of the time and holds it in **top-3 100 %** of the time — against 67 % / 85 % on the uniformly-sampled set §10f measured. **The first pass was worthless because uniform sampling of a mostly-quiet corpus put exactly ONE real detection in 400 windows**; every "case that matters" number rested on n = 1. Sampling uniformly to evaluate a detector measures the silence. Both things now hold at once: good enough to *show* a candidate, nowhere near good enough to *record* one (moves *Chorthippus brunneus* / Field Grasshopper by up to 2.10 logits against a 0.16 confirmed/rejected gap). Live operating point ≈ 9.0 (below the archive threshold, deliberately). Architecture noted: live scores **never** enter `results.db`, inference runs concurrently at a 6.6 % duty cycle rather than between segments, gate it on the new level meter, and memory (627 MB) is the real constraint. Caveat: n = 41, few species, and the head was fitted on this same corpus — 95 % is an **in-domain** number. **Feasible, not decided. Nothing built.**
- (2026-07-29, bug) **Off-by-one class index in the on-device spike scripts — §10f's species-level numbers retracted.** `<model>/assets/labels.csv` has 14,796 lines against 14,795 model outputs (first line `inat2024_fsd50k`), and the header guard only stripped a literal `label`/`labels`, so every logit was attributed to the species after the one it was labelled with. **Production is unaffected** — `biomon/audio_pipeline.py` reads perch-hoplite's class list and never touches `labels.csv`; nothing in `results.db` changes. §10f's *conclusion* survives (the learning curve measures reproducing a fixed 193-dim slice, which is index-independent), but its *C. brunneus*, top-1 and genus figures are wrong. Corrected measurement in §10g.
- (2026-07-29) **Weather recorded as a covariate; both correlations come back NOT significant.** New `session_weather` table (§4b) — separate from `sessions` because it is derived and re-fetchable, and it carries its own provenance including the grid point Open-Meteo actually served and the raw hourly rows. Backfilled all 8 sessions from the Open-Meteo archive (free, no key); 7 succeeded, `020825_0` has no coordinates and is stored as `source='missing'` rather than as a null. Means are **duration-weighted over the hours the captures overlap**, not calendar-day means. Results: wind vs score suppression **ρ = −0.80, p = 0.20 (n = 4)** — points the way the three earlier sessions suggested but does not clear significance; temperature vs visit rate **r = +0.18, p = 0.77 (n = 5)**, and temperature spans only 2.2 °C across the entire corpus so there is barely a predictor to correlate against. Cloud cover vs visit rate does clear significance (**r = −0.96, p = 0.009**) but is **confounded with site** (the one clear-sky session is the only meadow session) and cloud is the least trustworthy backfilled field (5–67 pp disagreement with the BirdNET app). **Suggestive, unconfirmed, nothing tuned on it.** The fix is more sessions, not more statistics.
- (init) Doc created. Decisions: Perch-only, two-pipelines-one-spine, SQLite results, AMI for video, InsectSet459 for audio Orthoptera.
- (inventory) Appended "Current State (as built)" + "Migration Plan" from a read-only pass over the existing `analysis/` scripts. No code changed.
- (amend 1) Bird classifier: use Perch 2.0's built-in head directly + Danish-list output filter + per-species threshold tuning on own recordings — NO linear probe. BirdNET comparison downgraded to a sanity check, not a gate. Orthoptera: test head coverage before building. Updated §3 + Phase 3 steps 12–13.
- (amend 2) Promoted the AMI video-classifier swap to its own **Phase 2.5** (before Perch) — Delta 3 hurts results today; the licence swap is commercial-only. Keep BirdNET installed/working until Perch is validated (delete on evidence, not schedule).
- (build) **Phases 0–2 delivered.** `analysis/biomon/`: paths→`data/`, per-session `session.json` config, SQLite `results.db` (§4 schema), shared spine, faithful video-pipeline port, CSV→DB ingest, backfill of all historical results, DB-backed reporting. No model changes.
- (build) Working practice adopted: **iterate only on short test clips** (`analysis/testclips/`, seconds per run) with known detections; full-length runs are for final confirmation only. Measure-then-change, never change-then-see.
- (build) Background-model convergence (Phase 0 step 2) **deferred with measurements** rather than forced — see the notes under Phase 0. Both models ported as-is and verified bit-identical to the original scripts.
- (rule) Added **§2b: one consistent rule everywhere beats optimal-per-video.** No per-capture/per-session parameter tuning; comparability across sessions is the scientific output. Debug knobs stay at defaults in `session.json`.
- (**discontinuity — 210726 results changed**) The adaptive paper-mask threshold (`max(100, 0.72*p95)`, introduced during the 240726 session to fix the picnic capture where a fixed 150 selected only 6 % of the board) widens 210726's mask 1.7× (170,333 → 291,365 px). Session 210726 was therefore **re-run on 2026-07-27** and its stored results supersede the original 2026-07-21 numbers:
  - `meadow`: **8 → 10** insect tracks (all 8 originals retained; +2 are earlier pickups of already-known visits at t≈954.6 and t≈1243.0).
  - `wood`: **7 → 6** insect tracks (−1 `fly_small` @ t 592.2, 27 % confidence; the `beetle` @ 657.7 shifted to 656.5 — same insect, detected 1.2 s earlier).
  - Cause is the threshold, **not** the Phase 0 refactor (the port is bit-identical on test clips). Accepted per §2b: one rule everywhere. Any comparison spanning 2026-07-21 must use the re-run values now in `results.db`.
- (2026-07-28) **Orthoptera collapses from a build to a filter.** Perch's head covers **17/17 Danish Orthoptera**, and on our own audio it calls the Aug-2025 bout **`Chorthippus brunneus`** (top-1 on 5/5 clips, logit 10.2–12.3). The independent DSP detector corroborates only that **an orthopteran stridulated there** (it measures periodicity, not species); the species call is Perch alone, uncalibrated. It **corrects BirdNET's "Fork-tailed Bush Katydid"** (a North American species; BirdNET had no Danish Orthoptera class). **Deletes in one go**: the Orthoptera linear probe, InsectSet459 seed data + its per-file XC/iNat licence audit, and the associated labelling work — previously scoped as a substantial winter build. `strid_scan.py` retired to `tools/` as a validated cross-check.
- (2026-07-28, schema) **Scores stay honestly distinct**: store Perch's **raw logit** with `score_type` in `features_json` rather than squashing to 0–1 — a squashed logit is not a BirdNET confidence, and false comparability is worse than an obviously different number (§4).
- (2026-07-28) **Orthoptera calibration anchor secured**: session `020825` yields **33 passing `Chorthippus brunneus` windows** (logit 11.0–12.5), including **7 consecutive windows** over t 1040–1070 — the same bout `strid_scan` found by pulse periodicity. Contrast with the isolated single-window eagle-owl FP suggests **temporal persistence** may be a better, species-agnostic discriminator than per-species thresholds (logged in §3 as a hypothesis to test, not built).
- (2026-07-28) **Session `020825` registered** (the Aug-2025 first-pass phone test) — audio only, because it holds the **only confirmed Orthoptera bout** (*Chorthippus brunneus*) and is therefore the sole calibration anchor for Orthoptera thresholds. Its **video is deliberately not registered**: the oblique/grazing geometry is not comparable with the top-down rig, so its visit rates must not be pooled with later sessions (§2b).
- (2026-07-28) **Traffic hypothesis for the eagle-owl FP: tested, REFUTED** (r = −0.015 vs vehicle classes). Also **corrected an overstatement**: the "287 rows" figure was rows above the *ingest floor*, not detections above threshold — it is **1 window in 661**. `biomon/noise_probe.py` keeps the co-occurrence test available for future suspects.
- (2026-07-28) **Verification workflow added** (`biomon/verify.py`) — crop or audio snippet + spectrogram, predicted taxon and score, `y/n/r/s/q` keys writing `verified_status`/`verified_taxon`, **sorted by score descending** so the strongest evidence is verified first (that is where thresholds get set). This is the input that per-species calibration has been waiting on.
- (2026-07-28) **New track opened: §10 Field Recorder App (Android).** Principle: *recorder first, identifier second* — the app must never be gated on the pipeline, because Perch is one pass over 14,795 classes, so new taxa are new class filters over an existing corpus, not new fieldwork. v1 = reliable background-safe segmented recording + sidecar metadata + notes; explicitly no on-device inference. Coupling to `biomon/` is the sidecar JSON only, reusing existing `session.json` audio[] keys. Stack/toolchain reported for review; no code written yet.
- (2026-07-28) **First measured precision — and it downgrades the Orthoptera anchor.** 43 observer-verified `Chorthippus brunneus` windows (strict presence criterion): **33 % precision** (14/29), score gap between confirmed and rejected only **0.16**. No threshold fixes it. Two follow-ups on the same labels: **temporal persistence** looks strong at ≥11 (runs of 6+ → 6/6) but rests on one bout; **spectral content is decisive** — all 29 rejects have ~zero 8–16 kHz energy and ~99.8 % of energy below 1 kHz, i.e. Perch is scoring the species on low-frequency rumble. The 32 kHz/roll-off "inaudible cue" caveat is **refuted** (rejects have no HF energy at all). Consequence: **`tools/strid_scan.py` should probably come back** — its band-limited envelope/periodicity measure is exactly the discriminator Perch lacks, and it independently flagged the confirmed bout.
- (2026-07-28) **Observer ground truth recorded for `280726`** in a new **`session_ground_truth`** table (session-level presence, explicitly NOT row-level `verified_status`). Result: **Rook (10.2) and Barn Swallow (10.3) are observer-confirmed FALSE NEGATIVES at threshold 11.0** — both "certain, observed", both independently flagged by the BirdNET app (0.89 / 0.90), both ~0.7 below threshold. First **verified** (not inferred) evidence that a clean-audio threshold under-detects in noisy audio. Thresholds deliberately left unchanged. The missed rows are already stored above the 5.0 ingest floor, so a future re-tune recovers them without reprocessing.
- (2026-07-28, Phase 3 step 12–13) **Perch audio pipeline built and wired as the default engine.** One inference pass emits both `birds` and `orthoptera`. Danish class filters (161 birds / 32 Orthoptera) with a **safety valve** that reports high-scoring out-of-list classes — it caught the `Astur gentilis` taxonomy trap and a genuine omission (`Porzana porzana`). **Thresholds moved into the DB** (`thresholds` table + `results_scored` view, ingest floor 5.0): re-tuning is an `UPDATE`, never a reprocess; below-threshold rows are kept as reviewable candidates; **classes are never dropped to suppress FPs — their threshold is raised instead**, so rare species stay detectable. BirdNET rows preserved alongside (clears are now `detector`-scoped). **BirdNET NOT deleted** — step 14 still gated on calibrated evidence.
- (2026-07-28, Phase 3 step 11) **Perch 2.0 stood up and verified.** Apache-2.0 ✔ (perch + perch-hoplite); `perch_v2_cpu` runs on CPU, 388 MB, **no Kaggle auth**; 14,795 classes, 5 s windows @32 kHz. Smoke test **top-1 matches BirdNET 4/4**. **18/18** of our detected birds covered (Jackdaw = `Coloeus monedula`). **Orthoptera: 17/17 Danish species covered → the planned Orthoptera probe and InsectSet459 training are unnecessary** (§3). New env `analysis/.venv-perch`; CRT shim factored to `biomon/_crt.py`.
- (2026-07-28, bug) **Renamed `analysis/platform/` → `analysis/biomon/`** — the package name shadowed Python's stdlib `platform` module and broke any library importing it from that directory (surfaced when TF/h5py failed via `platform.uname`). Imports, docs and `python -m` usages updated.
- (2026-07-28) Added **§2c: optimise the binding constraint, not the visible one.** **Phase 2.5 deferred (option C)** — with 22 insect detections, visit rate (weather/site) is the limit, not classifier accuracy. Phase 3 (Perch) promoted to next. (A) retained as the plan for when the crop archive grows (MIT dataset only, no AGPL tooling); (D) recorded for a future `moth_sheet`.
- (2026-07-28, Phase 2.5 step 8) **AMI premise corrected — §5 rewritten.** AMI is a **moth** dataset (5,364 moth species; nocturnal UV traps; binary moth/non-moth + moth-species tasks), not the general-insect, top-down-matched classifier the doc assumed. Dataset is MIT ✔ but `ami-data-companion` is **AGPL-3.0** ✗. No drop-in classifier exists for the daytime board; Phase 2.5 is **blocked pending a choice between options A–D**. AMI reserved for a future `moth_sheet` module.

---

## 10. Field Recorder App (Android)

### 10a. Principle — recorder first, identifier second
The app's job is to **reliably capture audio that actually gets used**. Identification is a
later addition and must never gate the recorder.

Rationale: Perch is **one inference pass over 14,795 classes**, so every recording already
contains birds, Orthoptera, amphibians and mammals. **New taxa are new class filters over an
existing corpus, not new fieldwork.** Timestamped, geotagged, unprocessed recordings are
therefore the durable asset — if the ID layer never ships, the corpus is still the thing the
whole project depends on. Corollary: **do not block this on the pipeline, thresholds, or
on-device inference.**

### 10b. v1 scope
- Record to file; **one big record/stop control**, usable one-handed, outdoors, in sunlight.
- **Background-safe: foreground service (`microphone` type) + partial wake lock.** The single
  most important requirement — a recorder that silently dies is worse than useless. Unlike the
  camera path, **audio does not need the screen on** (verified: `AudioRecord` has no surface
  dependency; the constraint that blocked the camera PWA in §8 does not apply here).
- **Segmented capture**: new file every N minutes (default **20**, configurable). Bounds
  corruption loss and fixes the single-file cutoff seen on the video side (§6).
- **Sidecar JSON per recording** (see 10d) — start time, duration, lat/lon + accuracy, device,
  sample rate, app version.
- **Audio: uncompressed PCM WAV, mono, highest supported rate.** No voice codec. Android's
  default capture chain applies AGC / noise suppression / echo cancellation, all of which
  destroy the faint high-frequency content that matters — see §3 (the 8–16 kHz band is exactly
  what separates real *Chorthippus* stridulation from low-frequency rumble). These are
  **disabled explicitly**, not assumed off.
- **Session list** — date, duration, location, size; tap to play back.
- **Export** via the system share sheet.
- **Free-text note per recording** — observer ground truth. Justified by §3b/§4: observer
  confirmation is the strongest evidence the project has, and it is what `session_ground_truth`
  consumes.

### 10c. Explicitly NOT in v1
On-device inference, species lists, thresholds, live ID display, cloud sync, accounts, video,
the insect-board pipeline.

### 10d. Sidecar contract (the ONLY coupling to the desktop pipeline)
The app and `biomon/` share **no code** — the interface is a JSON file written next to the
audio. Keys reuse the existing `session.json` `audio[]` names (`biomon/config.py`) so files
drop straight in; recorder-specific fields are additive.

```json
{ "label": "park1", "file": "REC_20260728_092426.wav", "prefix": "park1",
  "start_iso": "2026-07-28T09:24:26",           // naive LOCAL — matches config.capture_start_iso
  "start_utc": "2026-07-28T07:24:26Z",          // additive, unambiguous
  "tz_offset_min": 120,
  "duration_s": 1200.0, "lat": 55.6343287, "lon": 12.5750133, "lat_accuracy_m": 4.0,
  "sample_rate": 48000, "channels": 1, "bit_depth": 16,
  "audio_source": "UNPROCESSED", "effects_disabled": ["AGC","NS","AEC"],
  "device": "Google Pixel 9a", "app_version": "0.1.0",
  "notes": "observer free text" }
```
Aggregation into a `data/<session_id>/session.json` is a **desktop-side** step over a folder of
sidecars — the app never writes session-level config.

### 10d-bis. VERIFIED on the first real recording (2026-07-28, session `280726_2`)
First capture from our own app: home balcony, 20:51–21:23, 5-min segments.
- **Segmenting works.** 4 continuous segments of 300.37 s each; **0.88 s lost over 20 min
  (0.07 %)**, ~0.29 s per boundary (file close + header patch + sidecar write). Accepted as
  immaterial. Screen-lock had no effect; zero interruptions logged.
- **Sidecars complete** — all 21 fields present, `start_iso` parses through `biomon/config.py`.
- **The Pixel 9a does NOT support `UNPROCESSED`** (`unprocessed_supported: false`); the app
  fell back to `VOICE_RECOGNITION` and recorded that. **AGC could not be disabled**
  (`AutomaticGainControl.isAvailable()` == false) — only NS and AEC were. AGC affects
  amplitude, not spectrum, so it does not threaten the 8–16 kHz discriminator, but recorded
  levels are not comparable across sessions.
- **SPECTRAL RESULT — the fallback does NOT low-pass, and our app beats the camera app.**
  Peak-normalised PSDs show the app's `VOICE_RECOGNITION` WAV continuing smoothly through
  8–16 kHz to Nyquist (24 kHz) with **no cliff**, while the phone's **camera app shows a hard
  brick wall at ~17 kHz** — the AAC encoder in the video container, not the microphone.
  - **Corollary, and it matters for every comparison: all video-derived audio in the existing
    corpus is truncated at ~17 kHz.** Sessions `020825_0`, `210726_0`, `240726_*`, `260726_0`
    and `280726_1` are handicapped in exactly the band that discriminates Orthoptera (§3), so
    their stridulation scores are **not comparable** with anything the app records from here on.
    Treat pre-app audio as a separate, band-limited source.
  - Caveat: absence of a cliff refutes spectral filtering only. It says nothing about AGC.
- **Perch on the balcony**: *Columba palumbus* (Common Wood Pigeon) 12.0, *Turdus merula*
  (Common Blackbird) 11.3, *Apus apus* (Common Swift) 11.2. Max logit 12.0 and only 6
  detections passing — the lowest of any session, in the noisiest (heavy traffic). A **third**
  datapoint that noise depresses true positives rather than inflating false ones (§3b).
- **Bug found and fixed**: `getLastKnownLocation()` returns null once backgrounded, so lat/lon
  was null from segment 3 onward. The service now keeps active location updates while
  recording and caches the last good fix, and the sidecar records `location_age_s` so a stale
  fix is visible rather than passed off as current.

### 10f. On-device classification: SETTLED — classification stays on the laptop

> ⚠️ **CORRECTION (2026-07-29): every species-level number in this section is wrong.**
> The spike scripts read class names from `<model>/assets/labels.csv`, which holds
> **14,796** lines against the model's **14,795** outputs — its first line is
> `inat2024_fsd50k`, a dataset tag, and the header guard only stripped a line
> literally equal to `label`/`labels`. **Every class index was therefore shifted by
> one**, and each logit was attributed to the species *after* the one it was
> labelled with. So the *C. brunneus* column, the top-1 agreement figure and the
> genus/group figures below all describe the wrong classes.
>
> **Scope of the error — production is NOT affected.** `biomon/audio_pipeline.py`
> takes its class list from perch-hoplite (`model.class_list['labels'].classes`) and
> never reads `labels.csv`. Nothing in `results.db` is wrong, and no stored detection
> changes. The bug lived only in the throwaway on-device spike scripts.
>
> **The section's CONCLUSION survives**, because the quantity it rests on is
> index-independent: the learning curve measures whether a linear map on the pooled
> 1536-d embedding can reproduce *a fixed 193-dimensional slice of Perch's output*,
> and it flattens at max |Δ| ≈ 3.5 whichever 193 columns you pick. The head is
> information-limited either way. Corrected species-level numbers are in §10g.

Two spikes (2026-07-28). Both measured on the Pixel 9a; nothing built.

**Spike 1 — full Perch to TFLite.** Converts with **builtin ops only** (no Flex).
fp32 390 MB / fp16 195 MB; **282 ms** per 5 s window at 4 threads (~18x real-time);
**~1.8 GB peak RAM**. Rejected on fidelity: fp16 gave **max |Δlogit| = 2.16**.

**Spike 2 — embedder-only + small linear head.** The premise was right about the
shape of the problem: the classification head is `[14795, 1536, 4]` = **90.9 M of
the model's 101.8 M params (89 %)**, so dropping it shrinks everything:

| | full model | embedder only |
|---|---|---|
| fp16 size | 195 MB | **21.9 MB** |
| int8 size | — | **12.5 MB** |
| peak RAM (fp16) | 1842 MB | **627 MB** |
| latency @4 threads | 282 ms | **330 ms** |

Note latency did **not** improve — the head is 90.9 M params but only ~90.9 M MACs
in a single matmul, which XNNPACK handles almost for free; the convolutional
embedder dominates compute. The head is a *memory* cost, not a *time* cost.

**Why it still fails — the head cannot be reconstructed.**
- *(a) Extract Perch's own head rows*: **not feasible.** The head does not consume
  the 1536-d `embedding`; its `4` axis matches the `spatial_embedding` (16, 4, 1536).
  No tested reduction (max / mean / sum / logsumexp over either tensor, with or
  without either bias) reproduced the reference logits. Reverse-engineering the
  pooling was out of spike budget.
- *(b) Fit a linear head on the 1536-d embedding*: **fails, and more data will not
  fix it.** Learning curve on 2,600 real corpus windows:

  | N train | test max abs Δ | test mean abs Δ |
  |---|---|---|
  | 200 | 7.76 | 0.351 |
  | 800 | 4.50 | 0.278 |
  | 1600 | 4.29 | 0.252 |
  | 2200 | **3.47** | **0.243** |

  Error is flattening well above tolerance. This is **information-limited, not
  data-limited**: Perch pools the 16x4x1536 spatial map down to 1536 dims *before*
  the embedding output, and its head reads the un-pooled map. Information the head
  needs is gone by the time we see the embedding.

**Full chain vs desktop fp32 Perch, 400 held-out windows:**

| chain | max abs Δ | mean abs Δ | top-1 agree | *C. brunneus* max abs Δ |
|---|---|---|---|---|
| fp32 embedder + linear head | 3.469 | 0.243 | 69.8 % | 0.615 |
| **fp16 embedder + linear head** | **3.478** | 0.243 | 70.0 % | 0.618 |
| int8 dynamic + linear head | 4.958 | 0.329 | **39.5 %** | 0.922 |

**Verdict: fails the bar by an order of magnitude.** The confirmed/rejected gap for
*Chorthippus brunneus* (Field Grasshopper) is **0.16 logits**; this chain moves that
species' logits by up to **0.62** and other species by up to **3.48**, with **top-1
agreeing only 70 %** of the time. Calibrated int8 did **not** beat fp16 (contrary to
the usual mobile result) — it was clearly worse, and top-1 collapsed to 39.5 %.
Quantisation is not the problem; **the linear head is**, which is why fp32 and fp16
are indistinguishable here.

Also: full-int8 would not run — the benchmark binary rejected it (`SQRT` v2
unsupported) and the desktop interpreter hit a divide-by-zero in the quantised
graph. Additional deployment friction on top of a chain that already fails.

**Settled: recording on the phone, classification on the laptop.** Revisit only if
Google ships a Perch head that consumes the pooled embedding, or a first-party
mobile Perch.
### 10g. Live display is a different question from archive — and it passes (2026-07-29)

§10f rejected on-device classification against the **archive** bar. §0 argues live
display has a different tolerance: a live call is *a candidate to confirm with your
own eyes*, not a record. Re-measured on that basis, with the class-index bug of §10f
fixed (perch-hoplite's class list, asserted against a known `results.db` row before
anything else: db 13.68 → recomputed 13.70).

**The first re-measurement was worthless and it is worth saying why.** The 400
held-out windows were sampled *uniformly* over the corpus, and the corpus is mostly
quiet — exactly **one** of the 400 was a window the desktop calls a detection. Every
statement about "the case that matters" rested on n = 1. **Sampling uniformly to
evaluate a detector measures the silence, not the detector.**

Corrected, with a **targeted** set: the windows `results.db` actually flagged at
≥ 11.0, minus any that leaked into the head's training data (220 flagged → 140 leaked
→ 39 unresolvable → **41 clean**).

| | uniform 400 (what §10f measured) | **targeted 41 (live-display case)** |
|---|---|---|
| top-1 = desktop | 67.0 % | **95.1 %** |
| top-3 contains desktop | 85.0 % | **100 %** |
| genus | 68.5 % | 95.1 % |
| bird vs Orthoptera | 93.8 % | 97.6 % |
| max abs Δ logit | 4.51 | 3.30 |
| *C. brunneus* (Field Grasshopper) max abs Δ | 3.86 | 2.10 |

**Both things are true at once.** On windows where something is genuinely calling,
the fp16 embedder + linear head names *the same species the laptop would* 95 % of the
time and holds the right answer in its top 3 **every** time. And it is still nowhere
near the archive bar: the *Chorthippus brunneus* (Field Grasshopper) confirmed/rejected
gap is **0.16 logits** and this chain moves that species by up to **2.10**. So:
**display yes, record no.** That is not a compromise, it is the §0 distinction holding.

(Note the corrected *C. brunneus* figure is **3.86** on the uniform set, not the
0.615 §10f reported — the old number described a different species entirely.)

**On-device display threshold** (targeted set, fp16):

| device thr | shown | top-1 ok | top-3 ok | fabricated |
|---|---|---|---|---|
| 9.0 | 100 % | 95.1 % | 100 % | 2.4 % |
| 10.0 | 87.8 % | 94.4 % | 100 % | 2.8 % |
| 11.0 | 68.3 % | 92.9 % | 100 % | 3.6 % |

**~9.0 is the live operating point** — it surfaces everything the laptop would flag,
at 2.4 % fabrication. Note the live threshold is *lower* than the archive threshold
and that is correct: a missed candidate teaches nothing, a shown candidate is
confirmed by the person standing there.

#### Architecture, if it gets built

- **Live scores never enter `results.db`.** Not with a different `score_type`, not in
  the same table with a flag. A separate `live_display` table (or nothing persisted at
  all), tagged `score_type = 'perch_fp16_live'`, never joined by `results_scored`.
  §4's rule is that false comparability is worse than an obviously different number,
  and these differ by up to 3.3 logits from the archive quantity of the same name.
- **Inference runs concurrently, not between segments.** §10f measured **330 ms per
  5 s window** at 4 threads — a **6.6 % duty cycle**. There is no need to interleave
  it with segment boundaries, and doing so would either gap the recording or delay the
  display past the moment it is meant to serve. Segments are file boundaries, not
  capture breaks.
- **Gate inference on the level meter.** The recorder now measures RMS dBFS on every
  captured buffer. The corpus median window has a max logit of 7.4 — mostly nothing.
  Skipping embedding on windows sitting at the silence floor should cut inference work
  substantially for free, and the measurement already exists.
- **The real constraint is memory, not compute**: 627 MB peak for the fp16 embedder,
  on a phone that is simultaneously recording. That is the number to check first on
  hardware.

**What this does NOT establish.** n = 41, dominated by *Chorthippus brunneus* (Field
Grasshopper) with a handful of *Phylloscopus collybita* (Common Chiffchaff),
*Coloeus monedula* (Western Jackdaw), *Corvus cornix* (Hooded Crow) and others — it
is not a broad species test. More importantly the head was **fitted on 2,200 windows
from this same corpus**, i.e. these sites, this microphone. Its 95 % is an
in-domain number; a live display in an unfamiliar place could be materially worse,
and nothing here measures that. The single top-1 disagreement is also instructive:
the phone said *Chorthippus brunneus* (Field Grasshopper) where the laptop said
*Phylloscopus collybita* (Common Chiffchaff) — a cross-group confusion, the exact
error a live display would show most confidently and most wrongly.

#### BUILT 2026-07-29 — and the architecture changed on measurement

Approved for build as **display-only candidates**. Two things changed from the analysis
above, both because they were measured rather than assumed:

**1. The full fp16 model is used, not the embedder + linear head.** §10f reported the
full model at max |Δlogit| = 2.16 and rejected it. **That figure does not reproduce.**
Re-measured with the corrected class list:

| chain | set | top-1 vs desktop | top-3 | max abs Δ | *C. brunneus* max abs Δ |
|---|---|---|---|---|---|
| fp16 embedder + linear head | targeted 41 | 95.1 % | 100 % | 3.30 | 2.10 |
| **fp16 FULL model** | **targeted 41** | **100 %** | **100 %** | **0.19** | **0.041** |
| fp16 FULL model | uniform 400 | 99.0 % | 100 % | 0.71 | 0.198 |

The full model is not marginally better, it is *an order of magnitude* better, and it
needs no fitted head at all — so the in-domain question stops being about the
classifier and becomes purely about the recording conditions. Latency 204–228 ms/window
on the laptop; §10f measured 282 ms on the Pixel 9a, i.e. a 25-min segment costs ~84 s.

**2. Inference runs on completed segment FILES, one at a time**, with the interpreter
created and closed per segment, so its working set never coexists with itself and is
not held while merely capturing. Stated honestly: with continuous segmented recording
there is no true idle gap, so analysing segment N does overlap capture of segment N+1.
What is guaranteed is that inference never touches the live buffer, never blocks the
reader, and never runs twice at once.

**Containment — a live score cannot become a record.** Output goes to
`<recording>.live.json`, tagged `score_type: perch_fp16_live`, flagged
`archival: false` / `never_import: true`, and `biomon/import_recordings.py` **skips
`*.live.json` explicitly** (tested with an adversarial file that carries `start_iso`
and `file` and so would otherwise have passed the shape check). Nothing merges it into
`results.db`.

**The UI copy is the safety mechanism.** The measured failure mode is a *confident
cross-group error* — *Chorthippus brunneus* (Field Grasshopper) shown as *Phylloscopus
collybita* (Common Chiffchaff). So the display says "possibly", shows **three**
candidates rather than one, keeps the score small and unlabelled (it is an
uncalibrated logit, and any prominence would read as a probability), leads with the
common name, and carries the in-domain caveat on screen — not only in the README.

**Operating point 9.0**, deliberately below the archive threshold of 11.0: a missed
candidate teaches nothing, and a shown candidate is checked by the person standing
there.

**The in-domain caveat is the honest limit.** 100 % top-1 was measured on this corpus,
these sites, this microphone. It is not a claim about a new place, and the UI says so.

**Status: BUILT, display-only. The archive bar remains unmet and unchallenged.**

### 10e. Known physical limits (set expectations now)
- **Built-in phone mics cap at 48 kHz** via `AudioRecord` (24 kHz Nyquist). That is ample for
  birds and Orthoptera (Perch's usable band is 0–16 kHz; stridulation 8–16 kHz), and it will
  **not** match the ZOOM's 96 kHz.
- **Bats are out of reach on phone hardware** (need ~192–384 kHz). §1's "bats later" requires
  different hardware, not this app.
- Storage: 48 kHz/16-bit mono ≈ **5.8 MB/min ≈ 346 MB/hour**; a 20-min segment ≈ 115 MB. The UI
  must show free space and estimated remaining recording time.

### 10h. Open decision — RESOLVED
Stack, toolchain, background-survival strategy and repo location — reported for review before
any code was written. **Approved 2026-07-28: native Kotlin, for the reason that mattered —
`AudioSource.UNPROCESSED`.** Built and field-tested since (§10d-bis).

*(Renumbered from a second "10f" that collided with the on-device-classification section.)*

## Current State (as built)

Reality check as of the inventory pass. Everything lives in `analysis/` as **standalone CLI Python scripts run by hand, one per outing** — there is no framework, no app, no DB. Two Python environments: **base Python 3.14** (cv2/numpy) for all video + DSP; a separate **`analysis/.venv-bn` (Python 3.12 + tensorflow-cpu)** that exists *solely* to run BirdNET. Raw captures now live in `data/{210726,240726,260726}/` and `data/PXL_20250802….mp4`.

### Video pipeline (matches doc intent: framediff → crop → classify, CV-only)
- **`run_insect.py <src> <outdir> <x:y:w:h>`** — plain white board. Adaptive bright "paper" mask → grayscale *darker-than-background* detection → **EMA background with freeze-on-foreground** → blob list → greedy space/time track-linking → shape/persistence/contrast candidate filter → **multi-frame classify** → junk-reject.
- **`run_colour.py <src> <outdir> <x:y:w:h>`** — colour-choice board. 3-channel *colour-difference* detection vs a **rolling-median background** (240 s window; this is the corrected model) → HSV segmentation of blue/yellow/white regions with edge-exclusion bands → tags each track with the colour it sat on → emits `region_map.jpg` + a per-colour visit/residence tally. Otherwise identical track/classify/output structure.
- **Classifier**: `model/efficientnet-b0_imgsz128.onnx` run via `cv2.dnn` (no TF). This is **Sittinger insect-detect EfficientNet-B0, 27 fixed classes** (ant/bee/…/wasp + none_* junk classes). It is **not** the AMI model from §5. Junk-rejection uses its `none_bg/none_dirt/none_shadow/none_bird` classes.
- **Helpers**: `roi_finder.py` (auto-suggest crop rect); `grid.py` (pixel-grid overlay for picking crops by eye — lives in scratchpad, not repo).
- **Output per run**: `analysis/<outdir>/{blobs.csv, track_classifications.csv, crops/<verdict>_…jpg}` (+ `region_map.jpg` for colour).
- **Superseded building blocks** still present but folded into the two `run_*` scripts: `detect2.py, analyze.py, crops.py, classify.py, classify_tracks.py`.

### Audio pipeline (BirdNET — the thing to replace)
- **`birds.py <wav> <prefix> [min_conf] [lat] [lon] [week]`** — the only BirdNET code. Runs **only in `.venv-bn`**. Loads `birdnetlib` → BirdNET Global 6K V2.4; optional lat/lon/week applies BirdNET's eBird range filter (materially reduces false positives). Top of file carries a **VC++ CRT preload shim** needed to load TF 2.21 on this machine.
- **Input**: `.WAV` from a ZOOM recorder, or a video's audio track pre-extracted with ffmpeg into `analysis/audio/<name>_audio.wav`.
- **Output**: `analysis/<prefix>_bird_species.csv` (per-species aggregate) + `analysis/<prefix>_bird_detections.csv` (one row per 3 s window: start_s, end_s, common_name, scientific_name, confidence).

### Stridulation offshoot (Orthoptera-audio, but not in the intended design)
- **`strid_scan.py <prefix> <audio…>`** — a pure-DSP Orthoptera *detector*: 6–24 kHz band-pass → RMS envelope → high-pass envelope → 5 s-window autocorrelation, ranks periodicity (pulse-rep 10–100 Hz). Exports top-5 clips + spectrogram PNGs to `analysis/stridulation/` and reports each file's sample rate + energy roll-off. **Heuristic, no learned model, no Perch.** Validated (fires on the Aug-2025 katydid, silent on non-stridulating recordings).

### Session / combine layer (the closest thing to a "spine")
- **`session.py <session> [conf]`** — hardcoded `SESSIONS` dict maps a date-code to its video + audio files, then reads each pipeline's CSVs and writes **`analysis/combined_results_<session>.csv`** plus extracts merged bird audio clips and zoomed insect video clips (+ `clips_manifest_<session>.csv`).
- **`combine_results.py`, `extract_clips.py`** — earlier one-off versions, superseded by `session.py`.

### Current results/output format
Per-session **CSV**, not a DB. `combined_results_<session>.csv` columns: `capture, module, start_s, end_s, taxon, sci_name, rank, confidence, detector, notes`. Two `module` values in use: `insect_board`, `birds`. Video pipeline-specific info (residence, agreement) is stuffed into a freetext `notes`; audio carries `sci_name`. `detector` is a string literal (`framediff+effnetB0` / `birdnet_v2.4`).

### What already matches the doc
- **Two independent pipelines, no fusion layer** — held throughout; audio and video only ever meet at the combine step. ✔
- **Video = framediff → crop → classify, Perch-less CV** — exactly as §2. ✔
- **A single shared results table as the unification point** — exists in spirit (`combined_results_*.csv`) with a stable-ish schema. ◑
- **Region/config per session** — the `SESSIONS` dict is a proto-config. ◑
- **Swappable classifier as a file** — video classifier is a drop-in ONNX. ✔

### Deltas (reality vs intended design)
1. **Audio is BirdNET, not Perch.** Direct conflict with §3; the whole `birds.py` + `.venv-bn` + `detector='birdnet_v2.4'` chain is CC BY-NC-SA. No Perch present.
2. **Results are per-session CSVs, not one SQLite table.** Missing vs §4 schema: `id`, `session_id` (encoded in filename instead), `media_path` (crops/clips exist as files but aren't linked from the table), `verified_status`, `lat/lon` columns (lat/lon are passed to BirdNET as *args* but never persisted per row), typed pipeline-features (crammed into `notes`). Also no absolute `timestamp` — only `start_s/end_s` offsets, because session start time isn't recorded.
3. **Video classifier is Sittinger EfficientNet-B0 (27 pollinator classes), not AMI.** AMI (§5) is unused. The 27-class set frequently mis-labels non-pollinators (ants → fly_small, wasp → bee).
4. **No shared spine / config system / review UI / export path.** `session.py` is a hardcoded dict invoked by hand; capture-in and results-out are manual.
5. **Two divergent background models.** `run_insect.py` still uses the buggy EMA+freeze; `run_colour.py` uses the fixed rolling-median. Same job, two implementations.
6. **`strid_scan.py` is an out-of-architecture Orthoptera path** — heuristic DSP, not the intended "Perch embedding → linear probe." Decision needed: feed it into the Perch audio pipeline as a feature, or retire it.
7. **`session.py` paths are stale after the `data/` move** — the `SESSIONS` dict points at `{repo}/<date>/…`, now `data/<date>/…`; and `260726` isn't registered.
8. **Env split is a BirdNET artifact.** `.venv-bn` exists only because BirdNET needs TF; removing BirdNET reopens the "what runs Perch, and where" question.

---

## Migration Plan

Proposed, not executed. Guiding order: **build the target (SQLite + spine) first, then swap BirdNET→Perch against that stable target.** Video internals (framediff → crop → classify) stay untouched throughout — only *where it writes* changes. Each phase should leave the system runnable.

### Phase 0 — Stop the bleeding (truth + consistency, no new features)
1. **Repoint paths to `data/`** and register `260726`. Centralise capture locations in one place (see step 3's session config) so `SESSIONS` stops being a stale hardcoded dict.
2. **Converge the two background models**: port `run_colour.py`'s rolling-median into `run_insect.py` (or merge both into one detector with a white/colour mode). Removes Delta 5; keeps the framediff→crop→classify contract identical.
   - **Status: DEFERRED, not done.** Both models were ported faithfully into `biomon/video_pipeline.py` and each mode keeps its own validated default (white=`EMAFreeze`, colour=`RollingMedian`). Verified **bit-identical** to the original scripts on test clips (white: 349 blobs/4 tracks; colour: 78 blobs/1 track). Convergence onto one model is now a *measured, standalone decision* — deliberately not taken as a refactor side-effect. Use `process(..., bg_model='ema'|'median')` to A/B.
   - *Measurement (white board, 6-min clip spanning the 240 s median window, 4 known insects)*: EMA 3,033 blobs → 16 candidates → **7 insects**; median 6,115 blobs → 21 candidates → **7 insects — identical set**. So the median **loses no detections**, but roughly doubles blob noise at 6 min, and diverges much further at full length (55-min meadow: 181k vs 13k blobs, 668 vs 109 candidate tracks → a much slower classify stage). Recommendation: keep per-mode defaults; revisit only if a single model becomes necessary.
   - *Why they differ*: the white board's dominant noise is **moving vegetation shadows** that an EMA absorbs and a fixed-window median cannot; the colour board's is **hard high-contrast edges** where the EMA's freeze-on-foreground got permanently stuck (58k phantom blobs). Different failure modes, not a port bug.

   - *Second finding — the adaptive paper threshold (pre-existing, not from this refactor)*: the `max(100, 0.72*p95)` mask threshold added during the 240726 session (it fixed the picnic video, where a fixed 150 selected only 6% of the board) **widens the mask 1.7× on 210726** (170,333 → 291,365 px; the 170,333 figure exactly reproduces that session's original log). Re-running 210726 on current code therefore differs from its stored 2026-07-21 numbers: **meadow 8 → 10 insects (all 8 retained, +2 earlier pickups of known visits)**, **wood 7 → 6** (lost one 27 %-confidence `fly_small`; a `beetle` shifted 657.7→656.5, i.e. same insect found 1.2 s earlier). Net: more sensitive, slightly noisier. **DECIDED: stays on `auto` platform-wide, not pinned per session** (§2b). The lost detection was a 27 %-confidence `fly_small` — effectively the model's dustbin label for anything unrecognised, i.e. noise, not a real loss — and pinning per session would start a per-video tuning habit that destroys cross-session comparability. `paper_thr` remains available in `process()` for debugging only.

### Phase 1 — The results store (unification point, before touching models)
3. **Session config**: one small file per outing (`data/<date>/session.yaml`: `session_id, date, lat, lon, board_type, video[], audio[]`). This replaces the `SESSIONS` dict and supplies the `lat/lon`/`timestamp` the schema needs.
4. **`db.py` + SQLite schema** exactly per §4: `results(id, session_id, module, timestamp, media_path, taxon_pred, confidence, verified_status, lat, lon, verified_taxon, features_json)` + a `sessions` table. Provide a tiny insert/query API. `verified_status` defaults `unverified`.
5. **Backfill ingest**: load every existing `combined_results_*.csv` (and per-session `track_classifications.csv` / `*_bird_detections.csv`) into SQLite, mapping current columns → schema, linking `media_path` to the existing crops/clips. Proves the schema against real data and loses nothing.

### Phase 2 — Shared spine (both pipelines write to the DB)
6. **Pipeline interface**: `(session_config, capture_file) → rows inserted into results`. Refactor `run_insect.py`/`run_colour.py` to write to `db.py` instead of per-session CSVs — internals unchanged. `insect_board` features (residence_s, blob_area, colour) move from `notes` into typed `features_json`.
7. **Spine runner** `run_session(session_config)`: dispatches to the enabled pipelines and lets each write to the DB. This is `session.py`'s evolution into the `session/config → [PIPELINE] → results` box of §2. Clip/media export becomes a read-from-DB step, not a bespoke pass.

### Phase 2.5 — Better video classifier (DEFERRED 2026-07-28; Phase 3 runs first)
Delta 3 (ants→`fly_small`, wasp→`bee`) is real but **not the binding constraint** — see §2c. With 22 insect detections in the corpus, visit rate (weather/site) limits results, not classifier accuracy; training an order-level classifier to relabel 22 detections optimises the wrong end. **Decision: option (C).** Revisit when the crop archive has grown.

**When resumed, the plan is (A)**: train a coarse **order-level** classifier over the ~8–12 classes we actually see, on AMI's ≈350k **non-moth** subset (MIT), mixed with **Sittinger's Zenodo crops** (2,422 top-down platform-matched images) to close the domain gap. Use the **MIT dataset only — not the AGPL `ami-data-companion` tooling**, so no copyleft is taken on. **(D) recorded**: AMI is genuinely excellent for a future nocturnal `moth_sheet` module (a Denmark/UK moth model exists at F1 0.784 over 244 species).

8. ✅ **Done — findings invalidated the original "drop-in swap" plan.** Verification (§5) shows AMI ships **no general-insect classifier**: its models are moth-species / binary-moth, its trap imagery is nocturnal UV, and its companion code is AGPL-3.0. Options considered:
   - **(A) Train a coarse order-level classifier** on AMI's ≈350k **non-moth** subset (MIT), restricted to the ~8–12 classes we actually see (fly · hoverfly · bee · wasp · **ant** · beetle · true bug · orthoptera · spider · lepidoptera + junk), optionally mixed with **Sittinger's Zenodo crops** (2,422 images — small, but genuinely top-down and platform-matched) to close the domain gap. Properly fixes ant→`fly_small`. Cost: dataset assembly + fine-tuning a pretrained backbone + validation. Biggest win, real work.
   - **(B) Keep Sittinger, fix labels in the loop** — coarse-label our own crops through the verification workflow and fine-tune on them. Cheapest, but the corpus is 22 insect rows today; too little.
   - ✅ **(C) CHOSEN — defer** the video classifier; run **Phase 3 (Perch)** next (well-defined, unblocked) and revisit once the crop archive is larger.
   - ✅ **(D) recorded**: reserve AMI for a future **`moth_sheet`** module, where it is exactly the right tool.
9. *(when resumed)* **Swap behind the existing classify step's interface** — the framediff→crop→classify contract and `biomon/video_pipeline.py` structure stay unchanged; only the model file + class map change.
10. **Re-run existing sessions through the DB and compare** taxon labels (ant now labelled ant, etc.). Keep the Sittinger model as a fallback/comparison until the replacement is validated on our own crops.

### Phase 3 — Replace BirdNET with Perch (the licence goal)
11. ✅ **DONE 2026-07-28 — Perch 2.0 is stood up and verified on this hardware.**
    - Env: fresh **`analysis/.venv-perch`** (Python 3.12) via `pip install "perch-hoplite[tf]" msvc-runtime`; `.venv-bn` kept untouched. Same VC++ CRT preload needed — factored into **`biomon/_crt.py`**.
    - **Licence: Apache-2.0 ✔** on both `google-research/perch` and `perch-hoplite`. The commercial goal is achievable.
    - Model `perch_v2_cpu` (`TaxonomyModelTF`): **388 MB, downloads without any Kaggle auth**, runs on CPU. 32 kHz input, **5 s window / 5 s hop**, 1536-d embeddings, **14,795 classes** as scientific names (iNaturalist taxonomy) incl. ~198 non-species sound events (`Vehicle`, `Motor_vehicle_(road)` — useful, our sites are noisy).
    - API quirks to remember: class list is `model.class_list['labels']`, but logits come back keyed **`'label'`** (singular).
    - **Smoke test vs BirdNET — top-1 matches on 4/4 clips**: Barn Swallow→*Hirundo rustica* (13.67), Goldfinch→*Carduelis carduelis* (13.58), White Wagtail→*Motacilla alba* (11.69 / 13.27).
    - **Coverage of our actual species: 18/18 birds** detected to date (Jackdaw is present under the modern genus **`Coloeus monedula`**, not `Corvus`).
12. ✅ **DONE 2026-07-28** — `biomon/audio_pipeline.py`: `wav → 5 s windows → Perch head → Danish class filter → threshold → results`. **One inference pass emits BOTH modules** (`birds` and `orthoptera` are two filters over the same logits). Wired into the spine as the **default** audio engine (`--birdnet` selects the legacy path).
    - **Class filters**: `biomon/species_lists/` — curated Danish seed ∩ Perch classes, rebuilt by `build_lists.py`: **161 birds, 32 Orthoptera**. It **reports any curated species Perch lacks**, which caught two real taxonomy traps: Jackdaw is `Coloeus monedula` (not `Corvus`) and Goshawk is `Astur gentilis` (not `Accipiter`). Tetrigidae (`Tetrix`) are deliberately excluded — they are mute, so their absence is correct, not a gap.
    - **Safety valve**: high-scoring classes *outside* the Danish lists are reported rather than silently dropped. It surfaced a genuine omission (`Porzana porzana`, added) and confirmed the filter is doing its job (Blue Jay, Anna's Hummingbird, American Toad all scoring 9.5–11 and correctly excluded).
    - **Engines coexist in the DB**: `_clear_unverified` is scoped by `detector`, so a Perch run never overwrites BirdNET rows (and vice-versa). Required, since Perch is validated *against* BirdNET.
13. **Thresholds — architecture DONE, calibration deferred by design (2026-07-28).**
    - **Provisional global threshold 11.0** for `perch_v2` (both modules), seeded in the DB. Per-species calibration is *deliberately* deferred: it requires verified detections per species, and the verified corpus is ~0 — tuning now would fit a handful of clips.
    - **Thresholds live in the DB, applied at query time** (`thresholds` table + `results_scored` view). Ingest floor is **logit ≥ 5.0** (storage retention, not detection). Demonstrated end-to-end: setting `Bubo bubo → 14.0` removed it from the passing set **with no audio reprocessed**, and all 755 of its rows were **retained as reviewable candidates**. Re-tuning is now an `UPDATE`, so calibration can proceed incrementally as verification accumulates.
    - Scale check: 210726 audio = **23,918 stored** Perch bird rows → **81 passing** at 11.0; DB 8.7 MB. Orthoptera: 219 stored, **0 passing** — the July meadow hits (8.0–9.3) sit below threshold and are honestly candidates, not detections.
    - Sanity check vs BirdNET **PASSED** (see below); per-species calibration remains open.
    - **Every BirdNET species is recovered by Perch**, with more plausible ones besides: woodland(vid) BirdNET {Magpie 1.00, Blue Tit 0.98, Jackdaw 0.72} → Perch {*Cyanistes caeruleus* 13.7, *Parus major* 13.6, *Pica pica* 13.0, + Robin, Swift, Wood Pigeon}; meadow BirdNET {Swallow, Jackdaw, Magpie, Greenfinch} → all present in Perch. The `Coloeus` fix mattered — without it the second-commonest bird would have been silently filtered out.
    - **The provisional global threshold of 8.0 is too permissive.** Evidence: confirmed true positives sit at **11–14**; out-of-region false positives reach **10–11** (Blue Jay 11.0, Fish Crow 10.8); and implausible in-list hits appear in the 8–12 band (**`Bubo bubo` 12.3 in an open meadow at 10:00** — eagle owls are nocturnal; `Gryllotalpa gryllotalpa` 8.19). **A single global cut cannot separate these — this is the concrete case for per-species thresholds** (§3), which is step 13's remaining work.
    - Orthoptera in 210726 meadow (*Chorthippus brunneus* / *biguttulus*, 8.0–9.3) sit **below** the Aug-2025 confirmed bout (10.2–12.3), so they are candidates, not confirmations.

14. **Cut over, then delete BirdNET — only once validated** (step 13 sanity check passes): switch audio to Perch; remove `birds.py`, the BirdNET dependency, `detector='birdnet_v2.4'`, and the CRT/TF shim if Perch doesn't need it. This is the point the repo becomes commercially clean.

### Phase 4 — Cleanup / follow-on (not blocking)
15. Retire superseded scripts (`detect2, analyze, classify, classify_tracks, combine_results, extract_clips`) once the spine + DB cover them.
16. ✅ **Done (2026-07-28)** — `strid_scan.py` moved to `analysis/tools/`. Retired as a pipeline component (Perch covers Danish Orthoptera) but **kept as an independent cross-check**: pure DSP, no learned model, so agreement with Perch is real corroboration, and it still answers what Perch cannot (pulse rate, mic roll-off, presence of HF energy).
17. Verification UI + export path (§4/§8) — future, reads/writes the same SQLite.

**Suggested stopping point for first PR**: Phases 0–2 (paths, one background model, SQLite + spine, backfill) — a pure restructure with zero model change, fully reversible, and it makes Phase 3 a clean drop-in. Perch (Phase 3) as the second PR.
