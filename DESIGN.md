# Biodiversity Monitoring Platform — DESIGN.md

> Single source of truth. This chat (architect) updates it; Claude Code (builder) implements against it.
> A decision isn't real until it's written here. Design flows one direction: decide → doc → build.
>
> **Anything a human will look at is governed by [`.claude/skills/biomon-ui/SKILL.md`](.claude/skills/biomon-ui/SKILL.md) — read it before writing any HTML, CSS or JS. It is binding, not inspiration.**

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

The audio path now has two deployments: the desktop pipeline analysing recordings after
the fact (§3–§9), and an **always-on Android bird station** (§11) doing the same job live,
continuously, on a wall-mounted phone.

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

### 2d. Distinguish "the call failed" from "the answer is no".

A general rule, earned twice in two days at real cost:

- The species-image prefetch reported **"529 of 538: no usable photo"**. Both API calls
  were correct. It was firing 1,076 requests with no pacing, and Wikimedia rate-limits
  hard — a measured burst returns 200 **nine** times and then 429 for everything after,
  which is exactly how many images arrived. The code mapped every non-200 to "no photo",
  so a throttling failure was indistinguishable from an absent photograph, and it sent
  two people to debug the query rather than the pacing.
- The thermally-killed recording (§6c) rebuilt cleanly, dropped its rotation matrix, and
  reported **"104 blobs, 1 insect"** while silently monitoring 60 % of the board. A run
  that had half-failed looked like a run that had succeeded and found little.

Both have the same shape: **a partial failure that produces confident, plausible-looking
output.** That is far more dangerous than a crash, because nothing prompts you to
disbelieve it, and the plausible number gets written down.

So, everywhere:

- **Never let a transport, permission or resource failure be reported in the same channel
  as a negative result.** Count and surface them separately — "3 network errors, try
  again" is a different sentence from "3 species have no photograph".
- **When a result is suspiciously low, check the denominator before believing it.**
  Compare against a known-good run: paper-mask area against another capture from the
  same rig, download successes against the number of requests actually answered.
- Applies equally to the pipeline: a capture that yields no detections and a capture that
  failed to decode must never look alike in a report.

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
- **DOWNGRADED 2026-08-02 on station evidence — persistence measures DURATION, not veracity.**
  The bird station ran BirdNET at a 3.0 s window / 1.5 s hop and produced a **19-consecutive-window**
  run of *Porzana porzana* (Spotted Crake) on a Copenhagen balcony in August, confidence tracing a
  single smooth arc (0.15 → 0.92 → 0.15) across ~30 s. By the rule above that is the strongest
  possible evidence; in fact it is one noise event, and the species calls April–June at night.
  **30 of 47 inter-detection gaps were exactly 1.5 s — the hop interval**, i.e. the run length was
  reporting how long a sound lasted divided by the hop, and nothing else.
  Two things were conflated and must now be kept apart:
  - **A run of windows is a run of overlapping samples of the same air.** At 3.0 s / 1.5 s each
    window shares half its audio with its neighbour, so a run of *n* is closer to *n/2* independent
    observations, and none of them is independent of the others' sound source.
  - **Duration is not identity.** A 30 s continuous sound and a 30 s song produce identical run
    lengths. The `020825` bout scored 6/6 because it *was* a long song; the balcony run scored 19/19
    because a machine ran for half a minute. The statistic cannot tell them apart, and no threshold
    on it can.
  What survives: persistence remains useful as a **necessary** condition (an isolated window is
  still weak evidence) and useless as a **sufficient** one. The consequence for the station is
  logged in §9 — the repeat rule now counts **bouts separated by > 60 s**, not consecutive windows.
  The `020825` 6/6 figure is not retracted; it is reinterpreted as one bout, which is what §3b
  already warned it was ("n=1 event and 6 correlated windows, not 6 independent samples").
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

## 3d. Three-way cross-check — session `290726` jetty (findings only, nothing changed)

The first outing with **three independent identifiers on the same birds at the same
time**: the observer, Merlin Sound ID, and Perch — the latter on two capture paths at
once (`290726_0` video audio, AAC-truncated at 17 kHz, 1 h 48 m; `290726_1` BirdNET
Live FLAC, full band, 24 min).

**Observer claims, all corroborated but one.** Swallows → *Hirundo rustica* (Barn
Swallow), 649 windows, max 14.67. Wagtails ("black and white") → *Motacilla alba*
(White Wagtail), 24. Tern → *Sterna hirundo* (Common Tern) 8 **and** *Sterna
paradisaea* (Arctic Tern) 2 — the ambiguity I flagged when recording the claim was
real, and Merlin says Arctic. Large heron → *Ardea cinerea* (Grey Heron), 2, max 13.5.
Gulls → *Chroicocephalus ridibundus* (Black-headed Gull) 4, *Larus canus* (Common
Gull) 1. **Not found: *Phalacrocorax carbo* (Great Cormorant)**, which the observer was
certain of — cormorants are near-silent away from colonies, so this is an expected
false negative of an *audio* method, not a failure of the model. The "one large gull"
(*Larus marinus*, Great Black-backed Gull) was also not detected.

**Merlin agreed on 8 of its 12.** Both found Barn Swallow, both wagtails, Hooded Crow,
European Goldfinch, Black-headed Gull, Arctic Tern, Grey Heron and Rook. Merlin alone
reported *Branta canadensis* (Canada Goose), *Himantopus himantopus* (Black-winged
Stilt), *Tringa glareola* (Wood Sandpiper). Perch alone reported ten more, including
*Carduelis carduelis* (European Goldfinch) at 89 windows.

**⚠️ This surfaced a real gap in the Danish species list.** *Tringa glareola* (Wood
Sandpiper) and *Himantopus himantopus* (Black-winged Stilt) **are in Perch's classes
but not in `denmark_birds.json`** — while three other *Tringa* species are. Wood
Sandpiper is a regular Danish passage migrant; its absence means Perch **can never
report it**, which is precisely the failure §4 forbids ("never drop a class — raise its
threshold"). It was never dropped, it was never added. Two consequences:

- The list needs regenerating against a proper Danish checklist, and rarities belong
  **in** it with a high threshold, not absent from it.
- **This is not a free fix.** `audio_pipeline.py` only *stores* rows whose class is in
  the list; out-of-list classes are reported by the safety valve as a max score and
  then discarded. So adding a species requires **re-running Perch** on affected
  sessions — unlike a threshold change, which is an `UPDATE`.

**The safety valve did its job again**, flagging high-scoring out-of-list classes that
are geography traps rather than gaps: *Corvus brachyrhynchos* (American Crow) 11.2,
*Agelaius phoeniceus* (Red-winged Blackbird) 10.7, *Bubo virginianus* (Great Horned
Owl) 10.4 — all North American. Alongside them the FSD50k non-bird classes behaved
sensibly for a jetty with people: `Liquid` 10.8, `Human_voice` 10.2, `Shout` 10.2.

**Capture-path note.** The two paths were recorded simultaneously, so their counts are
**not independent samples** and must never be pooled as such (§2b). Per-minute rates
are comparable: Barn Swallow 6.0/min on the video path vs 2.7/min on the FLAC, which
is the wrong direction for the 17 kHz truncation hypothesis and most likely reflects
mic placement and the shorter window rather than bandwidth. **Not a controlled
comparison; nothing concluded from it.**

## 4. Results schema (the unification point)
One table, both pipelines write to it. Draft columns:
- `id`, `session_id`, `module` (e.g. insect_board | birds | orthoptera), `timestamp`
- `media_path` (crop image or audio clip)
- `taxon_pred`, `confidence`, `verified_status` (unverified | confirmed | corrected | rejected)
- pipeline-specific features stored alongside (e.g. audio: pulse_rate, peak_freq, bout_dur, temp; video: blob_area_mm, residence_s)
- `lat`, `lon`, `verified_taxon`
Store as SQLite (single file, zero-admin).

**A geographic list is not a plausibility filter — but seasonality was NOT the fix.**
Denmark's list contains Fieldfare, Brambling, Lapland Longspur and Whooper Swan because
they occur *in Denmark*, not because they occur in Copenhagen in July. So a month-of-year
plausibility filter was built from GBIF's month-faceted Danish occurrence records (no API
key, DOFbasen included; a month counts as plausible at ≥ 2 % of that species' annual
Danish records, species with < 30 records get every month open, 27 species with no Danish
records **fail open**). It is applied at **display time only** — out-of-season detections
stay stored and reviewable, because an out-of-season bird is the most interesting thing
this project could find and a filter that deleted it would be worse than useless.

**Recorded as an honest null result: it removed 7 species of 51 alone, and ZERO on top of
the score threshold.**

| | season OFF | season ON |
|---|---|---|
| 9.0 / ≥1 detection *(old)* | 51 | 44 |
| **11.0 / ≥2 detections** | **7** | **7** |

Every species it catches was already a single hit at 9.0–10.5, so the score-plus-repeat
rule did 44 of the 44 removals. The filter is **kept for the case it was built for** — a
winter thrush scoring 12, which no threshold can catch — but it must not be remembered as
one of the fixes, because it fixed nothing here. It also cannot help with *Lophophanes
cristatus* (Crested Tit), *Regulus regulus* (Goldcrest) or *Bubo bubo* (Eurasian
Eagle-Owl): all three are **resident** in Denmark and implausible in central Copenhagen
for habitat and rarity reasons, which is a prior we do not have.

**A third model's opinion is not ground truth.** `external_claims` holds species claims
made by other classifiers (Merlin Sound ID, the BirdNET Live app) — separate from
`session_ground_truth`, which holds what a **person** claims to have observed. The
distinction is not bookkeeping: session ground truth is what *proves a false negative*,
so admitting another model's output would let a Merlin false positive manufacture a
phantom Perch false negative — a machine error laundered into evidence. `external_claims`
carries the detector that made each claim, is never read by threshold or false-negative
analysis, and deliberately has **no score column**: these tools emit a species list, not
calibrated per-window scores, and inventing a confidence for them would be exactly the
false comparability §4 forbids.

**Ground truth has two levels, and they must not be conflated.** `results.verified_status`
is a **row-level** judgement ("this detection is correct"), produced by `biomon/verify.py`.
`session_ground_truth` is a **session-level** presence claim ("this species was at this site"),
which an observer can assert without being able to vouch for any particular window. Session
ground truth can therefore prove a **false negative** (species known present, never detected)
and constrain thresholds — but it must **never** be used to set `verified_status`, nor as
row-level labels for calibration or training. Absence from it is non-observation, not absence.

**Species lists come from an authority, not from memory.** The Danish bird filter is
DOF's official checklist (Appendix 1, Categories A/B/C) intersected with Perch's
classes — not a hand-written seed. The seed failed in the least visible way possible:
it simply never contained *Tringa glareola* (Wood Sandpiper), a regular Danish passage
migrant, so Perch **could never report it** and nothing in any output hinted at the
absence. Two rules follow:

- **A missing class is worse than a false positive.** A false positive is visible and
  can be thresholded away; a missing class is silent forever. Rebuild from the
  authority when the checklist or the model changes.
- **Watch for taxonomy splits when rebuilding.** DOF follows AviList, Perch follows
  iNaturalist. AviList ranks Hooded Crow as a *subspecies* (*Corvus corone cornix*)
  where Perch has *Corvus cornix* as a species — a naive binomial intersection would
  have silently deleted the commonest crow in Denmark. `build_lists.py` expands
  abbreviated subspecies and re-tests them, and **reports anything dropped** so a
  regression cannot pass unnoticed.

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

### CAPTURE RATE: DECIDED — 3 fps interval stills (adopted 2026-07-29)

**The video board is captured as 3 fps interval stills, not continuous video.** This is
now the capture contract, not a recommendation. Evidence in §6b; the numbers that
decided it, over 59 confirmed insect visits:

| rate | caught ≥1× | caught ≥3× | tracker still links? |
|---|---|---|---|
| 1 fps | 99.0 % | 59.7 % | **no** — 1.0 s > `DT` 0.6 s |
| 2 fps | 100 % | 83.6 % | yes |
| **3 fps** | **100 %** | **96.9 %** | **yes** |
| 5 fps | 100 % | 100 % | yes |

3 fps catches **every** confirmed visit at least once and **96.9 %** at least three
times — enough to crop and vote a classification — while its 333 ms spacing stays
inside the existing `DT = 0.6 s` link radius, so tracks still form. Going to 5 fps buys
the last 3.1 % of three-capture visits; going to 1 fps is not a trade but a **structural
break**, since at 1.0 s spacing no two captures ever link and every capture becomes a
one-blob track the `n≥3` filter discards.

**It was adopted for a thermal reason, not a data one.** The phone hit thermal cutoff
after ~30 min in direct sun and left `290726_2` as 4.5 GB of `mdat` with no index
(recovered, §6c). Continuous H.264 is the dominant load; interval stills skip
inter-frame motion estimation entirely and encode ~10× fewer frames.

**TWO CAVEATS, both unresolved, both stated here so they are not forgotten:**

1. **The sub-0.8 s blind spot is UNMEASURED.** The tracker only ever emitted visits of
   ≥3 detections and ≥0.8 s, so the corpus contains **no short visits at all** — their
   true frequency is unknown and this corpus cannot estimate it. Every catch rate above
   is *conditioned* on visits that already lasted 0.8 s. If brief touchdowns are common,
   3 fps loses them and we will not know it. Closing this needs a deliberate
   high-rate capture with the persistence filter relaxed, not more analysis.
2. **The corpus cannot see below ~6 fps.** Frames are processed every `STEP=5`th, so
   ~5.97 Hz is the finest temporal resolution in the data. The 5 fps row is near that
   limit and the 10 fps row is **extrapolation, not measurement**. 3 fps sits safely
   inside the measurable range; nothing above ~6 fps has been validated at all.

**Not measured: the thermal saving itself.** The mechanism is sound but no watt or
junction-temperature measurement has been taken. The decision rests on catch rates.

- [x] **fps knee test — ANSWERED 2026-07-29 (§6b).**
- [x] **capture rate — DECIDED: 3 fps interval stills** (above). **Segmenting still open** for the 4.6 GB / ~38-min single-file cutoff; note interval stills change its shape entirely, since there is no single growing file to truncate.
- [ ] **detect on-device vs ship raw** — current lean: keep raw around detections so new models can re-run on old captures; on-device detect only to decide what to keep
- [ ] focus/exposure lock behaviour
- App is capture-and-plumbing; the classifier is a swappable file. App can be built against a placeholder model.

### 6b. fps knee test — answered from the corpus, not from spliced footage (2026-07-29)

**Why it stopped being a data-quality question.** The phone hit thermal cutoff after
~30 min recording video in direct sun. Continuous H.264 encoding is the dominant
thermal load, so interval stills may be what makes hot-day recording possible at all.
The cost is already on disk: `data/290726_2/VID_20260729_155533.mp4` is **4.5 GB of
`mdat` with no `moov` box** — the camera was killed before it could write the index,
so the entire recording is currently unplayable.

**Method changed deliberately.** The plan in §6 was `ffmpeg -vf fps=N` decimation of an
existing clip. That would splice discontinuous frames into one stream and **break the
rolling-median background model at every join**, generating detections from the joins
themselves. Instead this is answered analytically from residence times already in the
corpus: for a capture clock of rate *r* with uniform random phase, the number of
captures landing inside a presence interval of length *L* is `floor(Lr)` or
`floor(Lr)+1`, the latter with probability `frac(Lr)` — exact, no Monte Carlo.
`tools/fps_knee.py`.

**Two censoring facts constrain every number.** Both are properties of how the corpus
was made, and no amount of analysis removes them:
1. The tracker only ever emits visits of **≥3 detections AND ≥0.8 s**
   (`video_pipeline.py`). **The corpus therefore contains no short visits at all** —
   not because they did not happen, but because they were discarded before anything was
   written down. Every catch rate below is conditioned on visits that already survived
   that filter, and is therefore an **optimistic bound**.
2. Frames are processed every `STEP=5`th, so the finest resolution in the data is
   **~6 fps, not the camera's 30**. 10 fps cannot be validated here, only extrapolated.

**1. Residence time**, 59 confirmed visits, 6 sessions (s):

| min | Q1 | median | Q3 | max | mean |
|---|---|---|---|---|---|
| 0.80 | 1.75 | **3.20** | 14.95 | 71.0 | 9.20 |

Bimodal: 39/59 (66 %) are brief 0.8–5 s touchdowns, 15/59 (25 %) are long 15–71 s
settlings. The min is the filter floor, not biology.

**2. Single-frame visits: none — by construction.** Fewest detections in any confirmed
visit is **4**; only 1 visit (1.7 %) has ≤4. The ≥3-detection filter makes it
impossible for a single-frame visit to appear. **Their true frequency is unknown and
this corpus cannot estimate it.** That is the single largest uncertainty in the
recommendation.

**3. Residence is an underestimate — mainly through SPLITTING, not gaps.** Within
tracks, detection is near-continuous (distinct frames / frames spanned: median 0.99,
Q1 0.96; 76 % of visits detected on essentially every pass). But within-track gaps
*cannot* exceed the linker's `DT=0.6 s`, so an insect that settles longer does not
produce a gappy track — it **ends one track and starts another**. Three such groups
cover **9/59 visits (15 %)**, with **105 s of presence inside a span but not counted as
residence** (e.g. 4 tracks summing 11.4 s across a 51.9 s span). So true presence is
longer than measured, and the catch rates below are **conservative**.

**4. Simulated interval capture** (phase-averaged, exact):

| rate | interval | caught ≥1× | caught ≥3× | linker still works? |
|---|---|---|---|---|
| 1 fps | 1000 ms | 99.0 % | 59.7 % | **no** — 1.0 s > `DT` 0.6 s, tracks never link |
| 2 fps | 500 ms | 100 % | 83.6 % | yes |
| **3 fps** | **333 ms** | **100 %** | **96.9 %** | **yes** |
| 5 fps | 200 ms | 100 % | 100 % | yes |
| 10 fps | 100 ms | 100 % | 100 % | yes (extrapolated) |

**RECOMMENDATION: 3 fps.** It catches **100 %** of confirmed visits at least once and
**96.9 %** at least three times — enough to crop and vote a classification — while its
333 ms spacing stays inside the existing `DT = 0.6 s` link radius, so the tracker needs
no retuning to form tracks at all. 1 fps is not merely worse, it is **structurally
broken**: at 1.0 s spacing no two captures ever link, so every capture becomes its own
one-blob track and the `n≥3` filter discards all of them.

**What it costs.**
- Against 5 fps: **3.1 %** of visits drop below 3 captures (fewer crops to vote over,
  not a missed detection).
- Against continuous video: **0 %** of the visits we have ever recorded — but the
  corpus cannot see visits shorter than 0.8 s, so the real cost is **unknown** and
  falls entirely on brief touchdowns. This is the honest limit of the answer.
- **The candidate filter must be retuned.** At 3 fps a 0.8 s visit yields ~3 captures
  spanning 0.67 s, which fails the current `dur ≥ 0.8 s` test. `n≥3` survives;
  `dur≥0.8` does not and must drop to roughly `2/rate`.

**Not measured: the thermal saving itself.** The mechanism is sound — interval JPEG
stills skip inter-frame motion estimation entirely, which is the expensive part of
H.264, and encode ~10× fewer frames — but no thermal measurement has been taken, and
this recommendation rests on catch rates, not on watts.

### 6c. Recovering a thermally-killed recording (2026-07-29)

`290726_2` is the first capture lost to heat, and it is worth writing down because the
failure mode is silent and the recovery has three traps.

**What the cutoff does.** MP4 writes its index (`moov`) *last*. A camera killed
mid-recording leaves `ftyp` + `free` + `mdat` and nothing else — 4.5 GB of valid H.264
that no player will open, because there is no sample table. `ffmpeg` cannot help: the
`mdat` is length-prefixed AVCC, not Annex-B, so it does not parse as an elementary
stream either.

**Recovery: `untrunc`** (anthwlock build), which rebuilds the index by learning the
sample structure from a *healthy reference file from the same camera*. Recovered 32,158
frames / ~19 min / 536 keyframes. Then three traps, in the order they bit:

1. **Guessed timestamps break SEEKING, silently.** `untrunc` warns its frame durations
   "will probably be wrong"; what it does not say is that the resulting non-monotonic
   DTS makes OpenCV seek to the *wrong frame*. Verified directly: sequential read vs
   `CAP_PROP_POS_FRAMES` disagreed on 2 of 3 probes. The classifier crops by seeking, so
   this would have silently taken crops from the wrong moments. Fixed by re-muxing to
   constant frame rate from the elementary streams, after which all probes matched.
2. **The reference's metadata is inherited, including GPS.** The recovered file reported
   the *reference's* coordinates. The original never had a `moov`, so it never had GPS —
   those coordinates are the morning jetty's, and using them would place an afternoon
   session at the wrong site. `lat`/`lon` are left **null** pending the observer.
3. **Rebuilding from elementary streams DROPS the rotation matrix.** The phone records
   1920×1080 with `rotation=-90`; without it OpenCV hands back landscape, the portrait
   crop clips at row 1080, and the paper mask covers **215 k px instead of 369 k — 60 %
   of the board**. The run completed and looked plausible (104 blobs, 1 insect) while
   silently monitoring the wrong region. Caught only by comparing mask area against
   other captures. Restored with `-display_rotation -90` and re-run.

**The lesson is trap 3, not trap 1.** A recovery that fails loudly costs an hour. A
recovery that succeeds *partially* and produces confident, low numbers is how a corpus
acquires a session that looks like "few insects here" when it means "we looked at 60 %
of the board". **Always compare a recovered capture's paper-mask area against a healthy
one from the same rig before trusting a single detection from it.**

Also: the recovered capture ran at **28.14 fps** against 29.87 for un-throttled
segments — and the last segment of `290726_0` (also late, also hot) ran at 28.13. The
camera was already throttling its frame rate before it died, which is a thermal signal
visible in the metadata of every hot capture.

### 6d. First interval-stills capture — session `300726_1` (2026-07-30)

**1,026 images at 1.973 fps over 8.7 minutes, 4032×3024, yellow panel with diluted
lavender oil, 22.9 °C and 0 % cloud — the hottest and clearest capture in the corpus.**
The pipeline reported **0 insects. That is a false negative, and the two reasons it
happened are the substance of this section.**

Read it alongside §6c: both are cases where a run *completed* and produced a confident,
plausible number while being wrong (§2d).

#### The stills path itself — an adapter, not a second pipeline

`biomon/image_sequence.py` presents a folder of JPEGs through the `VideoCapture` subset
`process()` uses, so the validated detection logic is untouched. Three things differ from
video and all three are stated in code:

- **`STEP` must be 1.** Every interval still is already a deliberate sample; skipping four
  of five would sample 2 fps at 0.4 fps and put successive detections 2.5 s apart, outside
  the linker's `DT = 0.6 s`. No track would ever form.
- **Timestamps are nominal.** Android filenames carry whole seconds, so frame *i* is
  placed at `i / fps`. Real spacing jitters ±0.5 s around that. Residence times from
  stills are a lower-precision measurement than residence times from video.
- **Bursts are found, not assumed.** The folder held the 1,026-image session plus two
  10-image framing bursts, one from 21:22 the previous night. Those are not harmless: the
  background is built from frames sampled across the whole source, so a night-time board
  would enter the median that every daytime frame is then differenced against.

#### THREE CONSTANTS THAT WERE SECRETLY ABOUT THE CAMERA, NOT THE INSECT

The same defect as the persistence filter in §6b, three more times. Each was corrected by
deriving the constant from a measurable property of the capture, and each derivation
**reproduces the corpus value exactly** — verified, not argued.

| constant | was | means at 1.97 fps / 12 MP | corrected to | corpus unchanged? |
|---|---|---|---|---|
| `AMIN`/`AMAX` blob area | 8..2500 px | board is **4.65×** the corpus area, so "insect-sized" became "small insect only" | 37..11620, scaled by measured board area | yes — `area_scale` is opt-in |
| `RollingMedian(sample_every=30)` | 30 frames | **730 s** window — longer than the whole capture, so the buffer never filled and the background was the median of the entire session | `for_rate()`: 240 real seconds at any rate, so 10 here | yes — returns 30 at 5.97 Hz; re-ran `240726_1` and diffed 2,410 blobs byte-for-byte |
| crop context floor / `EMAFreeze(alpha=0.02)` | 90 px / 50 frames | context collapses from ~7.5× the blob to 1.9×; EMA time constant 8.4 s becomes 25 s | **NOT changed** — swept and refuted below / no white stills capture to verify against | n/a |

**The area limits deserve the general statement.** Both existing colour sessions already
produce blobs sitting exactly on the 2500 ceiling, so the ceiling is live, not slack. A
pixel is not a property of an animal. Any threshold in pixels is a threshold about the
*camera*, and it silently changes meaning when the camera does.
`tools/area_scale_probe.py` uses the board as the ruler and prints the conversion.

#### The support wires — 94.6 % of everything

Raw output was **20,232 blobs in 8.7 minutes**, against 2,689 for a 23-minute video
session. The count was not flat: it ramped from 0 per frame in the first minute to 40 in
the ninth. A blob heatmap put **94.6 % of them on three horizontal lines** — the board's
two support wires and a decking seam — and phase correlation showed the camera **sinking
monotonically, 11.7 px over the capture**. The mount sagged, and a slow drift past a
horizontal high-contrast wire lights up its whole length, worse as the drift grows.

**Why the video corpus never showed this.** The same wires are in the 1080p captures. At
that resolution they are about a pixel wide in the half-scale crop and the 3×3
`MORPH_OPEN` erased them. At 12 MP they survive it. **The bug did not appear because the
board changed; it appeared because the sensor got better.** Raising resolution moved a
physical structure across a filter's threshold.

`biomon/registration.py` aligns each frame to the first sampled frame by phase correlation
before the background sees it — translation only, because the measured failure is a shift
and fitting rotation and scale would give a moving insect three more parameters to hide
in. It refuses rather than guesses: a weak correlation or an implausible shift means the
frame passes through unshifted and is *counted* in the log (§2d).

| variant | blobs | on the wires | elsewhere | tracks | insects |
|---|---|---|---|---|---|
| as-was | 20,232 | 19,141 | 1,091 | 566 | 0 |
| + 240 s median window | 9,300 | 9,260 | 40 | 367 | 0 |
| + registration | **3,610** | 3,537 | 73 | 199 | 0 |

An 82 % reduction, and **the insect count is 0 in all three**. 3,537 blobs still sit on the
wires, because a wire in 4.6 m/s wind moves independently of the board and no global
translation can register that away. **The remaining fix is physical, not computational:
do not run wires across the board.** Masking them would cost ~8 % of board area and is
worth building only if the wires stay.

#### THE TWO REASONS THE COUNT IS ZERO

**(1) Real visits lasted one or two frames, and `MIN_SAMPLES` — not the rate-derived
rule — is what deleted them.** `tools/stills_candidates.py` reproduces the set;
`300726_1_stills/discarded_candidates.jpg` is all **17** of them at full resolution. At
least four are unambiguous insects settled on the panel — a sharply focused fly at +07:44
with legs, wings and thorax visible, an in-focus beetle at +01:46 — plus roughly seven
more in flight, several with visible wing-beat streaks. Frame by frame the fly is
**present in frame 917 and absent in 913–916 and 918–921**.

**And the beetle at +01:46 is the precise diagnosis.** It appears in TWO consecutive
frames, +01:46 and +01:47 — a ~0.5 s measured span, which **passes** the rate-derived
duration test (`dur ≥ 0.461 s` at 1.97 Hz) and fails only the sample count. Look at what
`persistence()` actually computes:

| rate | rate-derived `n` | `MIN_SAMPLES` floor | which binds |
|---|---|---|---|
| 5.97 Hz (video corpus) | 3 | 3 | neither — they agree |
| 3.00 Hz (adopted rate) | **1** | 3 | **the floor** |
| 1.97 Hz (this capture) | **1** | 3 | **the floor** |

§6b was careful to state the persistence rule in the animal's terms, and it did — for the
duration half. **`MIN_SAMPLES = 3` is the half that stayed in the camera's terms.** It is
invisible at the corpus rate because it coincides with the derived value there, and it
becomes an override the moment the capture rate drops. Its stated justification is "enough
frames to link a track and vote a class" — and **(2) below removes the second half of that
justification**, because the vote fails regardless of how many frames it gets.

**This also measures §6b's caveat 1, and the answer is the unwelcome one.** The fps knee
test could not see visits under 0.8 s because the persistence filter had already deleted
them, so their frequency was unknown. Here, where a single 12 MP frame is unambiguous
enough to adjudicate by eye, **short visits are not a minority — they are all of them.**

Relaxing the floor has a price, so here it is rather than a recommendation without one —
candidate tracks at each threshold, over every stored `blobs.csv`:

| capture | n≥3 (now) | n≥2 | n≥1 |
|---|---|---|---|
| `mount2` | 5,876 | 10,819 | 23,191 |
| `jetty1` | 5,313 | 7,018 | 10,629 |
| `meadow` | 1,527 | 1,950 | 2,867 |
| `b0726` | 111 | 133 | 222 |
| `300726_1` | **0** | 0 | 17 |

**The open decision (the observer's, per §2b): make `MIN_SAMPLES` follow the capture
mode.** Continuous video has the temporal density to afford `n ≥ 3`; interval stills at
≤3 fps do not, because a visit shorter than `n / rate` seconds is *structurally* invisible
to them. This is not a threshold to tune per capture — it is one rule that has to be
stated in the animal's terms, like `MIN_PRESENCE_S`.

**(2) And relaxing it would not have helped, because the classifier rejects all 17.**
Every candidate comes back junk at 41–92 % confidence, including **`none_dirt` at 87 % on
the sharp fly** and `none_bg` at 88 % on the in-focus beetle. Two hypotheses were swept and both refuted:

- **Framing.** In the corpus a median blob leaves `side = max(max(w,h)*2*1.9, 90)` pinned
  at the 90-px floor, so the insect fills about an eighth of the crop; at 12 MP the same
  expression gives 1.9× the blob and the insect fills the frame. Context factors 1.9, 3.0,
  4.5, 6.0, 7.5, 10.0 give **0, 0, 0, 0, 0, 1** insect verdicts of 14.
- **Pixel scale.** Downsampling the whole frame toward the corpus's own resolution
  (1×, 1/1.5, **1/2.16**, 1/3, 1/4.5) gives **0, 0, 0, 0, 1** of 14.
- **Control, so the finding is not a bug in the probe.** The same inference code on the
  corpus's 63 confirmed insect crops returns **54 insect**. The code is sound.
- Both sweeps ran over the 14 largest candidates, before the structure rule in
  `stills_candidates.py` was tightened from a size guess to pure recurrence; the tool's
  own pass over the final 17 returns **0 insect** as well.

By elimination this is **domain mismatch** — the parking-lot risk, now measured on real
data rather than anticipated. The 27-class model was trained on the insect-detect rig and
does not transfer to a hand-painted yellow panel at 12 MP. **It fails confidently, which
is the dangerous way to fail:** a session that reads "0 insects" is indistinguishable from
a session where nothing came.

This is the strongest evidence yet for Phase 2.5 (better video classifier), and it changes
its character: not an accuracy upgrade, a **correctness** problem. Until it is addressed,
**interval-stills captures must not be reported as counts.** The detector output plus the
candidate sheet is the honest deliverable.

#### Thermal — the claim is plausible and this capture cannot test it

The observer reports the phone overheated again and suspects it would overheat *idle* at
these temperatures. What the data says:

- The capture rate is **flat to the last frame** — 1.98 fps in minute 0, 1.95 in minute 8,
  no gaps, and every one of the 1,026 JPEGs ends with a valid `ffd9` marker. That is
  **unlike** `290726_2`, where the frame rate had already fallen to 28.14 from 29.87
  before the camera died. No throttling ramp here.
- But it ran only **8.7 minutes**, far short of what would test heat tolerance, and
  interval stills are individually complete files by nature, so "nothing truncated" is
  weaker evidence than it looks.
- **It was a Pixel 6a, not the 9a used for every video session.** Any comparison with
  yesterday's video cutoff compares two phones.
- Data rate was **3.70 MB/s sustained** (1.93 GB in 8.7 min). 12 MP JPEG at 2 fps is ISP,
  encoder and flash write all at once; it is not obviously a lighter load than 1080p
  H.264, and §6b's thermal argument for interval stills **still rests on mechanism, not on
  a measurement**.

**So the honest position is unchanged from §6b: the thermal saving is asserted, not
measured.** Testing it needs a deliberate run — same phone, same sun, video then stills,
with `dumpsys thermalservice` logged — not another opportunistic capture.

#### Efficiency note (a cost, not a finding)

At 12 MP the rolling median costs **2.89 s per rebuild**, and holding the window at 240 s
means one rebuild every 10 frames: ~5 min of the ~8 min detection pass. At the adopted
3 fps that is ~35 min of background computation per recorded hour. Parking lot: the
background does not need full resolution, but downsampling it changes results and so needs
its own measurement.

## 7. Hardware (context, not code)
- Printed A4 tray + post + phone platform; square-post/socket + side thumbscrew coupling; north-side orientation for shadow; locked manual focus. (Frame essentially designed; printing pending.)

## 8. Open questions / parking lot
- **Rig: no wires across the board.** They are 94.6 % of `300726_1`'s blob output and wind moves them independently of the board, so registration cannot remove them (§6d). Physical fix first; a wire mask costs ~8 % of board area and is only worth building if the wires stay.
- **Rolling median is the compute bottleneck at 12 MP** - 2.89 s per rebuild, ~35 min per recorded hour at 3 fps. The background does not need full resolution, but downsampling it changes results, so it needs its own measurement (§6d).
- **`EMAFreeze(alpha=0.02)` is a 50-FRAME time constant**, i.e. 8.4 s at 5.97 Hz and 25 s at 1.97 Hz - the same rate-dependence fixed for the median window, deliberately left alone because there is no white-board stills capture to verify a fix against (§6d).
- **Thermal saving from interval stills is still unmeasured.** Needs a deliberate run: one phone, one sun, video then stills, `dumpsys thermalservice` logged (§6b, §6d).
- Native app (APK) needed for unattended deployment; PWA insufficient for background camera. Not started.
- Domain mismatch: public clips are recordist-quality; deployment audio is cheap-mic-in-a-garden. Band-limit training to mic bandwidth and/or add a few own recordings per region for calibration.
- **HYPOTHESIS, NOT ADOPTED — within-window species co-occurrence as a species-agnostic noise
  discriminator (logged 2026-08-02, n=12).** In 55 minutes of station audio, **115 windows produced
  a detection and 12 produced more than one species**. One 3 s window at 21:18:14 returned
  *Regulus regulus* (Goldcrest), *Porzana porzana* (Spotted Crake), *Poecile montanus* (Willow Tit)
  and *Fulica atra* (Eurasian Coot) simultaneously. Four species cannot vocalise in the same three
  seconds on a balcony, so the window is far better explained by one broadband event exciting
  several classes than by four birds.
  **Proposed rule, stated so it can be tested rather than argued about: suppress any window in
  which ≥ 3 distinct species fire above the retention floor.** Attractions: it needs no per-species
  tuning, no regional list and no phenology, so it generalises to Orthoptera and to the insect
  vision path unchanged; and it is computable from data already stored, retroactively, at read time.
  **Why it is NOT adopted.** n = 12 co-occurrence events from a single hour at a single site with a
  single microphone — the same sample-size mistake §10g already made once ("sampling uniformly to
  evaluate a detector measures the silence"). Two specific ways it could be wrong:
  - **Dawn chorus is a genuine ≥3-species window.** The rule as stated would be most destructive
    exactly when the station is most productive, which is the §4 failure mode — a species that
    cannot be reported is worse than a false positive.
  - **The threshold may be on the wrong axis.** It may be that the *count* matters less than
    whether the co-firing species are acoustically unrelated (a crake, a tit and a coot share no
    frequency band); a spectral-overlap test might be the real discriminator, with co-occurrence
    only its cheap proxy.
  **What would settle it**: three nights of data, then compare co-occurrence rates in confirmed-bird
  windows against confirmed-noise windows, with the dawn hour separated out. Not before.
- **REFUTED 2026-08-03, on 19 h of ground-truth negative audio (§11f).** The station ran overnight in a
  LOUNGE — the observer confirms no bird audio was reachable, so all **5,499 detections across 101
  species are known-false**, a labelled negative corpus. Scored on it, the ≥3-species rule removes
  **zero** false confirmations (81 before, 81 after) while suppressing 250 rows; ≥2 species removes
  12 rows and **no** species, for 15.6 % of all rows suppressed.
  The reason is structural, and it kills the idea rather than merely failing to support it: of 5,022
  detection-producing windows, **4,639 are single-species**, and the multi-species windows are
  uniformly LOW-confidence. Co-occurrence therefore only ever targets rows the confidence threshold
  has already excluded. It cannot discriminate among the high-confidence detections, which are the
  only ones that reach a person.
  The n=12 that motivated it came from a single hour — the same sample-size error §10g named
  ("sampling uniformly to evaluate a detector measures the silence"), committed again despite being
  flagged in the entry above. **The lesson worth keeping is the meta one: a hypothesis logged with
  its own sample-size caveat still needs the measurement before it earns any weight.**
  **What the negative corpus can and cannot do.** It bounds false positives and nothing else: it
  contains no true positives, so a rule that rejects everything scores perfectly on it. It is a VETO
  on rules that let junk through, never a target to tune toward — tuning to it alone reproduces the
  §4 failure mode where a species that cannot be reported is worse than a false positive.
- Verification-as-a-feature: the differentiator competitors lack. Human-in-the-loop confirm/correct that feeds a defensible local dataset.
- Regional expansion = new probe per region (filter dataset to local species, retrain probe; embedding model never changes).

## 9. Changelog
- (2026-08-10) **Dashboard CSS sweep, and three defects it uncovered** (§11j). Removing the
  card-stack verification UI in §11p left its stylesheet behind: 163 lines styling elements
  that no longer exist. Deleted, and the stylesheet now has **zero unused class selectors**.
  Three things fell out of doing it. The card's `.v-credit a` was the only anchor rule in
  the file, so deleting it would have left every link on the page rendering in the browser's
  default blue on a warm near-black ground — including the xeno-canto credit, where the
  licence requires the attribution to be *legible*, not merely present; there is now one
  global link rule. The life list's two-column grid scrolled the whole page sideways on a
  phone (grid items default to `min-width: auto`, and "European Herring Gull" does not fit
  half a 390px viewport); fixed, and the name **wraps** rather than truncating, because a
  list reading "Europea…" beside "Common…" is not a list. And the species-count headline
  rendered both "the station says zero" and "the station did not answer" as an em dash while
  the panel directly beneath it asserted "No species confirmed yet." — the page contradicted
  itself, in exactly the way §2d exists to prevent. Both now say which one it is.
- (2026-08-09) **Reference recordings, and thresholds learned from verdicts** (§11q).
  Identification becomes comparison: when a detection proposes a species, the station
  fetches a known-good recording of it from xeno-canto and offers it beside the clip, so
  the question changes from "name this bird" — unanswerable for this user — to "does this
  sound like that", which anyone can answer and which teaches by repetition. Cached on disk
  following `PhotoCache`, filtered to grade A and to Denmark first then its neighbours,
  because dialects vary and a reference that does not match the local population teaches
  the wrong comparison. The API key is **an ordinary setting**, entered once from the
  dashboard: not a build secret, not a repo secret, never a build argument, and never
  echoed back — `/api/settings` reports whether a key is set, not what it is. The station
  **ships with no key**, and that path is the one that was built and tested first: `no_key`
  is a distinct state from "the lookup failed" and from "the archive has no recording",
  all three are said in their own words, and verification is fully usable without any of
  them (§2d).
  Per-species thresholds are now **learned from verdicts** and feed the existing
  `effectiveThreshold` machinery, replacing the global default for a species exactly as a
  manual override does and ranking below one. There is **no global "trust above X" and
  none may be added** — the known-negative corpus peaks at 0.98 and is entirely false
  positives. A threshold needs examples on both sides, and **rejections carry more
  information than confirmations** because they pin the boundary from underneath, so the
  rule prefers the most conservative line admitting no known false positive. Exemption from
  routine verification requires **both** a learned threshold and plausibility; lifers,
  implausible records, species with no learned threshold, and a deterministic 1-in-10 audit
  sample are always asked about. Calibrated species are marked as such throughout the UI,
  because an exemption that looks like a tuned threshold is worse than no exemption.
- (2026-08-09) **Verification is triage with a two-part verdict; schema v7** (§11p). The
  station could not be verified by a non-expert, which made its output unverifiable in
  practice: the flow asked "is this a Blackbird?" of someone who can reliably tell a bird
  from a bike brake and cannot name species by ear. So the verdict splits in two. *"Is
  there really an animal here?"* is answerable every time and is where nearly all the value
  is, because the false-positive mass is the actual problem; *"is it this species?"* often
  is not, and **"something real, but I don't know what" is now a first-class stored
  answer** rather than being flattened into a rejection — §2d aimed straight at the one
  table the life list is built from. The list is tiered to match (`machine` /
  `bird` / `species` / `rejected`), and only a species identification is a life tick.
  The flow itself is a **list you choose from**, not a queue that advances into you:
  ordered by stakes (possible new species, then implausible-for-here, then boundary cases),
  grouped by species, resumable, and nothing auto-advances into the next species — finishing
  one has to feel like finishing. **Verdicts are stored per detection id, never per
  `bout_id`**: a bout is a read-time projection of `bout_gap_s` and its id moves when that
  setting moves, so a bout that later splits leaves both halves verified and two that merge
  leave a genuinely part-decided bout, displayed as exactly that. Bulk accept exists but is
  refused server-side for a species not yet on the life list. Also added `GET
  /api/data/export`, which downloads `station.db` — the API has had a DELETE that wipes
  everything since the beginning and no way to take a copy first, and the phone cannot be
  reached from a laptop.
  **A fresh install lands directly on schema v7.** The station has been off and nothing
  since `build-1` was installed, so all of this arrives on an empty database and the
  `verifications` table was given its correct shape rather than having columns bolted onto
  the old one. The migration discipline is unchanged and still governs everything future —
  §11k's rule that every migration from v5 onward must preserve `pinnedDetectionIds()` — it
  simply is not load-bearing for this particular install. The v7 step still migrates old
  verdicts rather than dropping them, because they are human decisions.
- (2026-08-09) **The day list is rolled up by species, and the bout becomes the unit of
  judgement** (§11o). Two changes that turned out to be one. PR 1 left a mismatch:
  `countBouts` merges detections within `bout_gap_s` (60 s) while `BoutRecorder` closes a
  recording after 4 s of silence, so a bird calling every twenty seconds is **one bout and
  three clips**. Resolved in favour of the evidence unit — the bout stays the thing being
  judged and now owns an *ordered list* of clips, played back to back as one listening
  experience with the seam shown rather than the dropped silence reinserted. Pinning and
  pruning were re-checked against that: pinning a detection now pins every clip of its
  bout, because half a bout is evidence that starts mid-phrase. A bout with no clips is
  five different situations and `audio.state` names them — `recorded` / `partial` /
  `pending` / `none` / `unavailable` — because `pending` is a state that did not exist
  before bout clips and is the one most easily rendered as "no audio" when it means "not
  finished yet" (§2d). On top of that the day list is now one row per species per day,
  which is how a day list is actually kept: bouts as the headline number (a detection count
  mostly measures how long something sang near the mic, and neither number is a bird
  count), with peak confidence and how many bouts cleared `effectiveThreshold` carried into
  the row so "37 bouts" cannot read as fact when all 37 are marginal. Each row carries a
  six-period activity strip **anchored to sunrise and sunset rather than the clock** —
  Copenhagen sunrise moves 04:26→08:37 across the year, so fixed bins would smear one dawn
  chorus across different columns and destroy the pattern; solar times are computed
  on-device from the station's coordinates, no network and no dependency.
- (2026-08-09) **Clips are bouts now, with pre-roll and post-roll** (§11m). The station
  stored exactly the 3.0 s model window. Three seconds is what BirdNET needs and it is not
  what a person needs: identification by ear runs on rhythm, repetition and phrase
  structure, so a single chirp cut from the middle of a phrase is ambiguous even to an
  expert — and the person this station is for cannot identify species by ear at all, which
  is the whole point. A continuously singing bird also produced a new 3 s file every 1.5 s
  hop: a dozen fragments of one event, none of them the event. `BoutRecorder` now keeps a
  ring buffer off `AudioCapture`'s continuous PCM tap so audio from *before* the trigger
  can be written (detections fire mid-phrase and the opening notes are often the
  diagnostic part), keeps the recording open while detections keep arriving so one bout is
  one file, and closes it a few seconds after the last one. Because a clip runs on past its
  trigger it cannot be written when the detection is scored, so the row goes in with no
  clip and is updated when the audio is complete — the deferral is the whole reason this is
  stateful, and the consistency rule that falls out of it is that audio is staged as
  `.part` and renamed into place before any row names it, so a row can never reference a
  file that was never finished. Schema v6 adds `clip_start_ms` (additive only; the v3/v4
  `DELETE FROM detections` pattern is not repeated) so the API can say where inside a bout
  clip a detection actually sits, `null` rather than `0` when that is unknown. One file is
  now shared by many rows, which quietly broke three storage paths that counted rows —
  pruning deleted a file while nulling one row and left the rest pointing at nothing — all
  now keyed on distinct `clip_path`. Modelled against the committed corpus the cap moves
  8 GB → 16 GB; the arithmetic and its assumptions are in the constant's comment.
- (2026-08-08) **The dashboard is served from storage and updates itself; the APK gets a
  release pipeline** (§11l). Two problems with the same root: the station phone is going
  outside permanently, and nothing in the cloud can reach it — it sits behind NAT on a home
  LAN, so it must *pull* every change and can never be pushed to. Serving `www/index.html`
  out of the APK meant a one-line UI edit cost a rebuild and a physical reinstall, so the
  dashboard now lives in `getExternalFilesDir("dashboard")` alongside `models` and `clips`,
  seeded from the bundled assets on first run and replaceable by `POST
  /api/dashboard/update`, which fetches both files from a **compile-time-pinned**
  `raw.githubusercontent.com` URL. Pinned rather than parameterised because that *is* the
  security model for an unauthenticated endpoint on a LAN whose API already accepts an
  unauthenticated `DELETE /api/data`: with no URL parameter, the worst a hostile device can
  do is make the station re-download our own dashboard. Both files are downloaded whole,
  validated (non-empty, correct length, and containing markup the real file always has —
  the check that rejects a 404 page arriving with a plausible status) and staged before
  either is renamed into place; any failure writes nothing and reports *which* failure it
  was, per §2d. Serving falls back to the bundled asset whenever the stored file is
  missing, so a botched update cannot leave a phone with no adb and no dashboard.
  Separately, `.github/workflows/release.yml` now builds `:station` on every merge to
  `main` (as `build-<run number>`) and on `v*` tags, and attaches the APK to a release,
  because the install path is now "open the release page in the phone's browser and tap the
  file". The APK's filename and the release body both name the key that signed it, because
  an APK signed with a different key than the one on the phone cannot install over the top,
  and the only remedy is an uninstall, which deletes `station.db` and the ~67 MB of BirdNET
  models — restorable only over adb from a computer, which is the exact thing this whole
  change exists to stop needing. **Only a build signed with the configured keystore is
  installable as an update**: the `signingConfig` falls back to the debug config when no
  keystore secret is set, but on a CI runner that means a randomly generated per-run key,
  so a `debugkey` release is build verification and nothing more. This was first written
  down as though the fallback were installable; it is not, and the release body now leads
  with that.
- (2026-08-03) **§11 written: the bird station's full architecture documented.** The
  station (`recorder/station`, built 2026-08-02 onward) had been accumulating design
  decisions and measurements in this changelog and in §8 without a home describing the
  system itself — capture, curation, the life list, the dashboard. §11 now covers all of
  it; the entries below (2026-08-02/03) are the individual findings that fed it.
- (2026-08-03) **Verification interface: a card-stack, one species at a time** (§11h).
  Five states (setup/running/done/empty/unreachable); setup states the queue size before
  committing and offers a session cap of 10/25/all counting cards *presented*, not
  resolved. Rejecting a species removes it from the species list and day rollups but
  leaves its detection rows visible, greyed, in the feed — the signal that a per-species
  threshold override is due. The client-side spectrogram reuses every constant from
  `Spectrogram.kt` so a card looks like the live view; the one documented divergence is
  that a 3 s clip has no 30 s of history, so its percentile floor is taken over the whole
  clip. Build stamp moved into the header as an identity marker, not a Settings metric.
- (2026-08-03) **The life list, and a local-occurrence prior that replaces the regional
  checklist gate** (§11d, §11h). `species_status` / `verifications` / `species_prior`
  schema (v5). A species reaches `confirmed` only through a human verdict — no score
  promotes one, and nothing is grandfathered. Pinning (`lifer_detection_id`/
  `best_detection_id`) now covers rows as well as clip files, closing the retention hazard
  below. BirdNET's own location/week meta-model replaces DOF-checklist set-membership for
  the confirm-threshold penalty, run on-device for the station's coordinates across all 48
  weeks; the penalty is log-scaled because priors span five orders of magnitude. Measured
  on the 19 h known-negative corpus: flat threshold + bout rule left 81 false rows/6
  species; + prior threshold, 50/4.
- (2026-08-03) **Curation: bouts replace windows, and the clip floor splits from the row
  floor** (§11f, §11g). `repeat_count` now counts detections separated by >60 s as
  distinct bouts rather than raw rows — 102→81 false rows / 9→6 false species on the 19 h
  negative corpus. Clip retention (0.50) is now a separate, higher bar than row retention
  (0.10): one floor governing both had the station writing 5,022 clips in 19 h (≈667
  GB/year against ≈908 MB/year of rows), so the 8 GB cap held four days of audio; split,
  the same cap holds ~83 days with zero rows dropped. `daySummaries` was found skipping
  the repeat check and reporting a different confirmed-species count than `/api/species`
  from the same table in the same second — fixed to share the bout logic. The §8
  co-occurrence hypothesis was scored on the same corpus and **refuted**: it removes zero
  false confirmations, because multi-species windows are already low-confidence and only
  overlap rows the threshold had already excluded.
- (2026-08-03) **Dashboard restructured to five tabs around the life list, spectrogram
  moved from polled images to a streamed WebSocket, and the design system made
  enforceable** (§11j). Live / List / Verify / Days / Settings, because the life list is
  the spine, not a bolt-on. The spectrogram now streams one 265-byte binary column per FFT
  frame over `/api/spectrogram` (2048-pt Hann, 256 log bins 200 Hz–12 kHz, 30 cols/s target)
  from a continuous capture tap, rather than rebuilding a spectrogram client-side from a
  polled 3 s WAV — removes the latency, tearing, resolution ceiling and jitter that
  approach had. Every design-system value (`.claude/skills/biomon-ui/SKILL.md`) is now a
  CSS custom property in `tokens.css`, so a hardcoded colour is visibly wrong rather than
  merely undisciplined.
- (2026-08-02, **the repeat rule was measuring the wrong thing**, §11f) **Station curation: ≥2 detections becomes ≥2 bouts >60 s apart.** 55 min of balcony audio, 135 rows, 35 species, 11 confirmed. Of 13 rows over threshold the ≥2-detections rule rejected **2**, and passed a **19-consecutive-window** *Porzana porzana* (Spotted Crake) run that is one ~30 s noise event — a species that calls April–June at night, scored at 0.918 in August in Amager. The mechanism is arithmetic, not bad luck: at a 3.0 s window / **1.5 s hop** consecutive windows share half their audio, and **30 of 47 inter-detection gaps were exactly 1.5 s**. The rule was counting how long a sound lasted. It now requires two bouts separated by **> 60 s**, which is a claim about the animal (it called, stopped, and called again) rather than about the analysis window. §3b's persistence hypothesis is downgraded accordingly — persistence is **necessary, not sufficient**. Second finding from the same hour, logged as a hypothesis in §8 and deliberately **not** built: 12 windows returned multiple species and one returned four (Goldcrest + Spotted Crake + Willow Tit + Coot in the same 3 s), which no balcony can produce.
- (2026-08-02, **§2d again**, §11d) **The regional gate was removing the tell, not the error.** `regional` flags **455 of 6522** BirdNET classes because the DOF list is a *national checklist including every vagrant ever recorded* — so Dark-eyed Junco (12 rows), Sandhill Crane and White-throated Sparrow all read as "Danish species", while the gate's write-path `continue` silently deletes the obviously-absurd classes that would have told an observer the model was guessing. Membership is the wrong instrument; it answers "has this ever occurred in Denmark" when the question is "how likely is it here, this week". Being replaced by BirdNET's own location/week occurrence meta-model — a probability, not a set. Also note the gate contradicted the rule stated in `Model.kt` and `build_station_species.py` ("never drop a class, down-weight it"); the code and its own documentation had diverged.
- (2026-08-02, **retention hazard found before it cost anything**, §11g) **`onUpgrade` has issued an unconditional `DELETE FROM detections` in two of four schema versions**, and `pruneToCapBytes` deletes clip FILES while keeping rows (nulling `clip_path`), with `pruned_total` hardcoded to 0 in `/api/health` so it is invisible. Neither is a bug today; both are fatal to a life list, where the audio behind a lifer is unrecoverable. Consequence for the life-list schema: pinning must cover **rows as well as clips**, migrations must exclude pinned rows, and pruning must skip them.
- (2026-07-30) **First interval-stills capture, and it reported 0 insects when at least three are plainly visible (§6d).** 1,026 images at 1.97 fps. Three constants that read as being about insects turned out to be about the camera: blob area limits (the board is **4.65×** the corpus area, so 8..2500 px meant "nothing bigger than a fifth of the corpus maximum"), the rolling-median window (`sample_every=30` is 240 s at 5.97 Hz and **730 s** at 1.97 Hz, longer than the whole capture), and the crop context floor. Each fix derives the constant from the capture and reproduces the corpus value exactly - `240726_1` re-ran to 2,410 blobs byte-for-byte. Also **the mount sagged 11.7 px**, which put **94.6 % of 20,232 blobs on the board's two support wires**; the corpus never showed this because at 1080p the wires were a pixel wide and `MORPH_OPEN` erased them - **the bug appeared because the sensor got better**. Window fix plus phase-correlation registration take 20,232 blobs to 3,610. The count stays 0 in all three variants: every real visit lasted **one frame**, so `n>=3` removes them, and the classifier calls all 14 candidates junk at 41-92 % - **`none_dirt` 87 % on a sharply focused fly**. Framing (1.9×-10× context) and pixel scale (1×-4.5× downsample) both swept and refuted; a control on the corpus's own 63 confirmed crops returns 54 insect, so the probe is sound and this is **domain mismatch**, measured at last rather than anticipated.
- (2026-07-30, **measures a caveat that was open since §6b**) **The sub-0.8 s blind spot is not a minority case - in `300726_1` it is every case.** The fps knee test could not see visits under 0.8 s because the persistence filter had already deleted them. At 12 MP a single frame is unambiguous enough to adjudicate by eye, and every one of the 14 candidates is single-frame. One candidate persisted TWO frames and so **passed** the rate-derived duration test, failing only the sample count: `MIN_SAMPLES=3` is the half of the persistence rule that stayed in the camera’s terms, invisible at 5.97 Hz where it coincides with the derived value and an override at every lower rate (derived n is **1** at both 3.0 and 1.97 Hz). The open decision — the observer’s, per §2b — is whether it should follow the capture mode, with the price tag in §6d.
- (2026-07-30, **rule earning its keep**) §2d again, twice in one session: "20,232 blobs" was a drifting mount reported as activity, and "0 insects" was two filters reported as an empty board. Both runs exited 0.
- (2026-07-30) **Live-ID curation: 51 species -> 7.** The app showed 51 species in a 42-minute recording where ~8 were real, the genuine birds buried among one-off junk at 9.0-9.5. Measured first (`tools/live_threshold_sweep.py`): at 9.0 with no repeat rule, 51 species; at **11.0 with >= 2 windows, 7** - Greenfinch, Great Tit, Blue Tit, House-Martin, Magpie, Carrion Crow, Goldfinch, and none of the Cory's Shearwater / Eagle Owl / Tundra Swan. **11.5 was over-tuned**: it leaves 3 and discards 4 real birds, so the defaults come from the table, not the guess. Also fixed a section 4 violation: the live path filtered as it WROTE, baking the threshold into the file. It now stores everything above a 9.0 retention floor (5 candidates/window) and filters at read time, so a slider re-filters instantly with no re-analysis. Feed and summary share the settings and both order by TIME, newest first. "possibly" dropped - with 51 species the hedge applied to everything and so meant nothing.
- (2026-07-30, **null result**) **Seasonal plausibility filter built, and it changed nothing.** GBIF month-faceted Danish records give real phenology per species (Fieldfare 0.94 % of records in July, Lapland Longspur 0.00 %, Great Tit 4.14 %). Alone it removes 7 of 51; **on top of the threshold it removes 0**, because everything it catches is already a single low hit. Kept for the case it was built for - a winter thrush scoring 12 - and explicitly not counted as one of the fixes. Cannot help with Crested Tit, Goldcrest or Eagle Owl: all resident in Denmark, implausible in central Copenhagen for habitat reasons, a prior we do not have.
- (2026-07-30, **new rule 2d**) **Distinguish "the call failed" from "the answer is no."** Earned twice: 429 rate-limiting reported as "no photo on Commons" for 529 species (both API calls were correct - 1,076 unpaced requests, and a burst returns 200 nine times then 429), and a dropped rotation matrix reported as "1 insect" while monitoring 60 % of the board. Both are partial failures producing confident, plausible output, which is more dangerous than a crash because nothing prompts you to disbelieve it.
- (2026-07-30) **BirdNET returns as a consensus layer, desktop-only** (`biomon/consensus.py`). Set membership over species, never arithmetic over scores - a BirdNET confidence and a Perch logit are different quantities (section 4). On `300726_0`: **3 confirmed by both** (Greenfinch, Great Tit, Blue Tit), 1 Perch-only (Carrion Crow), 3 BirdNET-only (Rock Pigeon, Chiffchaff, Goldcrest). **On-device rejected on thermal grounds, not size** - BirdNET's model is 50 MB against Perch's 196 MB, so it is the *easier* of the two to run, but a second pass per segment roughly doubles heat on a phone that thermally cut out the day before, to confirm a list already down to 7. Trap caught while wiring it: `ingest_birds` stores BirdNET's COMMON name in `taxon_pred` while Perch stores the scientific name, so joining on `taxon_pred` reports perfect disagreement between two models that agree. Consensus normalises onto scientific names.
- (2026-07-30) **30-second segments broke the desktop pipeline's subprocess model.** One process per capture was fine at 20-minute segments and pathological at 30 s: 85 captures for one 42-minute session, each paying a fresh TensorFlow import and Perch load - roughly 15 minutes of startup for 42 minutes of audio. `spine.run_audio_perch` now hands the whole job list to one subprocess and the model loads once (`audio_pipeline --batch`). BirdNET's path has the same shape and has NOT been batched.
- (2026-07-30, **OPEN DECISION**) **The phone and the desktop now analyse different numbers of windows.** The app's tail-window fix gives it 588 windows on `300726_0` against the desktop's 503 (**+17 %**), because the desktop still drops each segment's sub-5-second remainder. At a >= 2-window rule this changes the answer: phone 7 species, desktop 4, and all three differing species (House-Martin, Magpie, Goldfinch) have exactly ONE desktop window over 11.0 where two are needed. So the 7-vs-4 gap is the TAIL FIX, not fp16 vs fp32. **This corrects an earlier judgement** - the tail fix was called not worth porting at 0.17 % of a 25-minute capture, which was right for 25-minute captures and wrong for 30-second ones, where the remainder is proportionally 10x larger and the min-count rule amplifies it. Porting means a corpus re-run (section 2b), so it is left as a decision rather than done silently.
- (2026-07-29) **Capture rate DECIDED: 3 fps interval stills** (§6). Adopted for a thermal reason, not a data one. Catches **100 %** of 59 confirmed visits ≥1× and **96.9 %** ≥3×, and 333 ms stays inside the tracker's `DT = 0.6 s`. Two caveats carried forward explicitly: the **sub-0.8 s blind spot is unmeasured** (the tracker never emitted a visit shorter than 0.8 s, so their frequency is unknown and closing it needs a deliberate high-rate capture with the filter relaxed), and **the corpus cannot see below ~6 fps** (`STEP=5`), so 10 fps is extrapolation. The thermal saving itself is still unmeasured.
- (2026-07-29) **Persistence filter now derives from the capture rate** — and the derivation is the point. Measured duration is not presence: a visit of true length *L* sampled at rate *r* spans on average *L − 1/r*, so a fixed `dur ≥ 0.8 s` silently tightens as the rate falls, and at 3 fps would have made the pipeline **discard its own detections**. The rule is now stated once about the animal (`MIN_PRESENCE_S = 0.968`) and both thresholds follow the rate. **Verified identical on continuous footage**: `tools/verify_filter_retune.py` re-links every existing capture's `blobs.csv` and diffs old vs new candidate sets — **3,183 candidates, 17 captures, zero difference**, so §2b is satisfied and no stored result moved.
- (2026-07-29) **Thermally-killed recording recovered** (§6c). `290726_2` was 4.5 GB of `mdat` with no `moov`; rebuilt with `untrunc` against a same-camera reference (32,158 frames, ~19 min). Three traps, worth remembering in order of nastiness: guessed timestamps made **OpenCV seek to the wrong frames** (so the classifier would have cropped the wrong moments — fixed by re-muxing to CFR); the reference's **GPS is inherited** (so `lat`/`lon` are left null rather than placing an afternoon session at the morning jetty); and rebuilding from elementary streams **drops the rotation matrix**, which clipped the portrait crop and left the paper mask covering **215 k px instead of 369 k — 60 % of the board** while the run completed and looked plausible. That third one is the lesson: a partial recovery that succeeds quietly is how a corpus acquires a session meaning "we looked at 60 % of the board" while reading as "few insects here". **Compare a recovered capture's mask area against a healthy one before trusting it.** Result after the fix: **3 insects, 376 blobs** (vs 1 insect, 104 blobs while broken). Also: the capture ran at **28.14 fps vs 29.87** un-throttled — the camera was throttling before it died, a thermal signal visible in metadata.
- (2026-07-29) **The hot afternoon was NOT the richest footage.** 3 insects in 19 min = **9.5/h, 95 % CI [2.0, 27.7]** — against 12.8/h [8.1, 19.2] for the same site that morning. With 3 events the interval is far too wide to rank, so the honest statement is that it is **indistinguishable**, not lower. What *is* clear: **376 blobs in 19 min of full sun vs 65,954 in 25 min of moving shadow** — direct corroboration of §3c's finding that shadow costs compute, not accuracy.
- (2026-07-29) **fps knee test answered — 3 fps interval stills recommended** (§6b). Became urgent for a thermal reason, not a data one: the phone hit thermal cutoff after ~30 min in direct sun, leaving `290726_2` as **4.5 GB of `mdat` with no `moov` box — an unplayable recording**. Answered analytically from residence times rather than by `ffmpeg` decimation, because splicing discontinuous frames would break the rolling-median background at every join and manufacture detections. Residence over 59 confirmed visits: median **3.20 s**, Q1 1.75, Q3 14.95, max 71. At 3 fps, **100 %** of confirmed visits are caught ≥1× and **96.9 %** ≥3×, and 333 ms stays inside the linker's `DT=0.6 s`. **1 fps is structurally broken** — at 1.0 s no two captures ever link, so every capture becomes a one-blob track and the `n≥3` filter discards all of them. Two censoring facts bound the answer: the tracker only ever emitted visits of **≥3 detections and ≥0.8 s**, so the corpus contains **no short visits at all** and their true frequency is unknown; and frames are processed every 5th, so ~6 fps is the finest resolution available and 10 fps is extrapolation. Residence is an **underestimate**, mainly because a settled insect ends one track and starts another (15 % of visits are in such groups, 105 s of presence uncounted) — so the catch rates are conservative. The `dur≥0.8 s` filter must drop to ~`2/rate` for interval capture. **Thermal saving itself is not measured.**
- (2026-07-29, **corpus-wide discontinuity — §2b**) **Danish bird list rebuilt from DOF's official checklist: 161 → 506 classes.** The seed list failed in the least visible way a filter can — it simply never contained *Tringa glareola* (Wood Sandpiper), a regular Danish passage migrant, so Perch **could never report it** and no output ever hinted at the absence. Source is now "The Danish List", Danish Rarities Committee, Sept 2025 (AviList taxonomy), Appendix 1 (Categories A/B/C) ∩ Perch classes; D/E/F escapes excluded. **ADDED 345, DROPPED 0.** A naive binomial intersection would have deleted *Corvus cornix* (Hooded Crow) — AviList ranks it a subspecies, Perch a species — so the builder expands abbreviated subspecies and reports anything dropped. Only 3 plausible Danish species have no Perch class (*Chlamydotis macqueenii*, *Grus virgo*, *Setophaga aestiva*), all extreme vagrants. **All 7 Perch audio sessions re-run**; any comparison spanning 2026-07-29 must use the new values.
  - Effect on the existing corpus, measured (`tools/list_rebuild_report.py`): **rows 73,461 → 104,669** (+43 %, almost all between the 5.0 ingest floor and threshold), and at threshold **exactly ONE species newly reportable — *Emberiza hortulana* (Ortolan Bunting), 210726_0, 2 windows, max 11.92**, which the safety valve had already been flagging as an out-of-list high scorer. **Nothing lost.**
  - The other 5 newly-passing species in `290726_1` are **not** the rebuild — they were already in the old filter and appear because the Merlin recording was added to that session. Reported separately rather than folded into the rebuild's credit. Four of them (*Chroicocephalus ridibundus* Black-headed Gull, *Corvus cornix* Hooded Crow, *Sterna paradisaea* Arctic Tern, *Branta canadensis* Canada Goose) are also on Merlin's own list — independent corroboration on the same audio.
  - **The rebuild bought reach, not results.** 345 extra classes produced one extra detection and no false-positive flood at threshold 11.0. That is the outcome §4 predicts: rare species belong *in* the list, held back by threshold, not absent from it.
- (2026-07-29) **Live species candidates BUILT and device-verified** (§10g). fp16 FULL Perch, display only, operating point 9.0, top-3, on completed segments. The architecture changed on measurement: §10f's "fp16 = max |Δ| 2.16" **does not reproduce** — the full model scores **top-1 100 % / max |Δ| 0.19** on the targeted set, an order of magnitude better than the embedder+head chain and with no fitted head to go stale. Contained so a live score cannot become a record: `<name>.live.json`, `score_type perch_fp16_live`, `archival:false`, and `import_recordings.py` skips `*.live.json` explicitly (tested adversarially). Memory behaves: **peak 1.30 GB PSS during inference, back to 160 MB within 20 s**; 60 windows in 29.5 s while recording continued. Fixed a shutdown race found only on device (cancel() is cooperative, so the last segment had not yet been submitted when the service asked whether it could stop). The first run demonstrated the caveat unprompted — recorded indoors at a desk, it offered **Tawny Owl at 2 pm**.
- (2026-07-29) **Three-way cross-check at the jetty** (§3d): observer, Merlin and Perch on the same birds at the same moment. All but one observer claim corroborated; the exception is *Phalacrocorax carbo* (Great Cormorant), near-silent away from colonies — an expected false negative of an audio method. **Found a real list gap**: *Tringa glareola* (Wood Sandpiper) and *Himantopus himantopus* (Black-winged Stilt) are in Perch but missing from `denmark_birds.json`, so they can never be reported. Fixing it requires a **re-run**, not an `UPDATE`, because out-of-list classes are never stored.
- (2026-07-29) **FLAC verified on hardware — and it was broken.** Two real bugs the unverified code shipped with: the csd-0 container header was assembled wrongly (Android returns a complete metadata-block sequence, not a bare STREAMINFO), producing files no decoder would open; and `total_samples` was left 0, which ffmpeg tolerates but **libsndfile does not** — so files decoded fine in ffmpeg and blew up on ingest through librosa. Both fixed and verified end to end (ffprobe, soundfile, librosa). Vindicates testing before trusting: the audio was always intact, the container was not.
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

## 11. Bird Station (Android, always-on)

A second, separate APK (`:station`, `dk.biomon.station`) from the Field Recorder App in
§10. §10's app is *carried and watched* — picked up, pointed at something, put away.
This one is *bolted to a wall and must never stop*: capture → on-device BirdNET →
curation → SQLite → serve, running continuously and answered from any device on the LAN.
The two apps share no code (each forked file names its origin — `AudioCapture.kt` from
`AudioEngine.kt`, `PhotoCache.kt` from `SpeciesImageFetcher.kt`) because their lifecycles
are opposite and a shared library module would mean editing a working `:app` to serve a
requirement it doesn't have.

`recorder/station/API.md` is the data contract — **written before either side was built**,
so the Android service and the dashboard (`assets/www/index.html`) build against the
schema rather than against each other. It is versioned (`schema: 1`) and is the
authoritative reference for every HTTP endpoint and JSON shape below; this section covers
the architecture and the decisions behind it.

### 11a. Device and the three-layer OxygenOS survival

**Target device: OnePlus 5T, Android 10, API 29** (a 2017 Snapdragon 835). `minSdk = 29`
is not arbitrary — background microphone access on Android 10 *requires* a foreground
service declaring `foregroundServiceType="microphone"`, which only exists from API 29.

An always-on station has one hard requirement — it does not die — and OxygenOS is known
to defeat any single mitigation on its own. Three independent layers, per
`StationService`'s class doc:

1. **Foreground service** (`type="microphone"`) + a **partial wake lock** held for the
   service's entire lifetime.
2. **`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`**, prompted once from `MainActivity` — OxygenOS's
   own battery manager can still kill an unwhitelisted foreground service even with (1) in
   place. This is the one step that needs a human, and `MainActivity` keeps asking until
   it's granted.
3. **`Watchdog`**: a periodic `AlarmManager` check (`setExactAndAllowWhileIdle`, so Doze
   cannot defer it for hours) running **outside** the service process. Every `onWindow`
   call stamps a heartbeat; if it's older than 5 minutes when the watchdog fires (every 10
   minutes), the service is assumed dead and explicitly restarted — not just re-armed and
   hoped for. `BootReceiver` covers the case the whole process is gone: it restarts the
   service and re-arms the watchdog on `BOOT_COMPLETED`, `MY_PACKAGE_REPLACED` and OEM
   quick-boot, since a station that needs a human to reopen the app after every power
   blip is not "always on".

`MainActivity` itself is deliberately thin — "a glance, not a dashboard": it requests the
two permissions the service can't request for itself, polls its own `/api/health` over
loopback to confirm the service is alive, and shows the LAN URL (`http://<ip>:8848`) to
open elsewhere. The real UI is the phone's own web server.

### 11b. Audio capture and on-device BirdNET inference

Forked from `AudioCapture` (§10) with the same discipline — `AudioSource.UNPROCESSED`
requested first, `AGC`/`NS`/`AEC` explicitly disabled where the platform allows it — and
the same graceful degradation: if the hardware refuses `UNPROCESSED` it falls back to
`VOICE_RECOGNITION` (the Pixel 9a precedent, §10d-bis) and, if it refuses 48 kHz
entirely, walks down `[48000, 44100, 32000, 22050, 16000]` and linearly resamples back up
to 48 kHz before the model ever sees it — **BirdNET has no fallback for a wrong sample
rate, it just quietly mis-scores everything**.

- **Window 3.0 s, hop 1.5 s** — BirdNET's own training configuration (`birdnetlib`'s
  `main.py`, read from source, not guessed). 50% overlap.
- **5.0 s warm-up discard**, inference path only. `AudioRecord`'s first buffers after
  `startRecording()` are not trustworthy, and on a device where `UNPROCESSED` is actually
  honoured there is no AGC masking it — found directly when a freshly cleared station's
  very first stored detection was a Great Spotted Woodpecker at 0.675, two seconds after
  boot, in a closed lounge. The **spectrogram tap is deliberately not gated** by the same
  discard: a display blank for five seconds after start looks exactly like the failure
  it exists to catch, and its own noise floor needs that history anyway.
- **Inference runs synchronously on the capture thread.** BirdNET's ~100–200 ms per
  window is well inside the 1.5 s hop budget, so a second thread would add complexity for
  no latency win. A **separate, continuous PCM tap** (`onPcm`) feeds the spectrogram from
  the same thread — necessary because the inference windows overlap 50% and drawing them
  directly would draw half of all audio twice and tear (§11e).
- **The model runs as raw TFLite, not `birdnetlib`/Python.** `BirdNet.kt` calls
  TensorFlow Lite's Java `Interpreter` directly against **`BirdNET_GLOBAL_6K_V2.4_Model_FP32.tflite`**
  (~52 MB, 4 threads), with input/output shapes and the score conversion
  **read from `birdnetlib`'s own source** rather than guessed: input `float32 [1, 144000]`
  (3.0 s @ 48 kHz, [-1, 1]), output `float32 [1, 6522]` raw logits, scored with a standard
  sigmoid clipped to `[-15, 15]` before exponentiating — verified equal to `birdnetlib`'s
  `flat_sigmoid(x, sensitivity=-1)` at its default sensitivity. The output width is
  asserted against `N_CLASSES_EXPECTED = 6522` at load time and the service **refuses to
  run** on a mismatch, rather than silently reproduce the §10f class-index bug.
- **Neither model file ships in the APK.** The FP32 acoustic model and the optional 14.8 MB
  location/week meta-model (§11d) are `adb push`ed into the app's external files dir —
  the desktop pipeline this project already trusts already has the identical file, so
  reusing it over USB is simpler and more honest than a second download path, and needs
  no network dependency on the station itself. A missing model fails loudly
  (`IllegalStateException` naming the exact path) rather than a bare native crash.
- **Thermal response is the station's own, distinct from the OS's.** Every window checks
  `PowerManager.currentThermalStatus`; at `MODERATE` or above, every other window is
  skipped rather than scored (`windowsSkippedThermal`, exposed in `/api/health`) — a
  deliberate degrade, not a die.

### 11c. Non-bird class exclusion

BirdNET's 6522 outputs include **11 acoustic-context classes that are not birds** — `Dog,
Engine, Environmental, Fireworks, Gun, Human non-vocal, Human vocal, Human whistle, Noise,
Power tools, Siren`. `analysis/tools/build_station_species.py` hardcodes this set **by
exact label text**, not by a word-count heuristic: a naive "binomial = two space-separated
words" rule misclassifies `"Human vocal"` and `"Power tools"` as species (same shape as
`"Turdus merula"`), which would let human speech reach the dashboard filed as an
unlabelled bird — precisely the false-positive class this exclusion exists to stop. The
builder's own verification pass re-checks this against the model's label file after
writing the asset.

These 11 classes are **tagged (`taxon.group = "non_taxon"`), never removed from the
6522-wide index** — deleting them would shift every later index, which is exactly the
§10f bug this project already retracted findings over once. Exclusion happens at two
points, deliberately redundant:

1. **Write time** (`StationService.onWindow`): `if (sp.group == "non_taxon") continue` —
   these rows are never inserted at all.
2. **Read time**, in every query that lists or aggregates detections (`listDetections`,
   `daySummaries`, `pendingForPublisher`): filtered again by `taxon_key`, not the stored
   `taxon_group` column, because a row written before the species table was itself
   corrected has the wrong group baked in permanently — a key never changes retroactively,
   a stored group can be wrong forever. Belt-and-suspenders, stated in code as such.

### 11d. Regional and seasonal plausibility — a hard drop, then a graded bar

**Two different mechanisms, easy to conflate, deliberately kept apart:**

**(1) The regional hard drop, at write time, unchanged since the station's first
commit.** `station_species.json`'s `regional` boolean — DOF's official Danish checklist
(Appendix 1, Categories A/B/C) intersected with BirdNET's classes, with the same
AviList abbreviated-subspecies expansion `species_lists/build_lists.py` uses (so
`Corvus cornix`/Hooded Crow, ranked a subspecies by AviList, isn't silently dropped) —
gates `StationService.onWindow` directly: `if (!sp.regional) continue`. **A species
absent from the Danish checklist is never written at all, full stop** — not stored as a
low-confidence candidate, not shown anywhere. A Compact Weaver or Flammulated Owl scored
in Copenhagen is never real, and this is the one place in the whole curation system that
drops rather than down-weights. (Contrast the non-taxon classes above, which are also
dropped — the same treatment, for the same reason: neither is a *detection*.)

**(2) The confirm-threshold penalty, at read time — replaced 2026-08-03.** Everything
that *does* get written is still only a **candidate** until it clears
`effectiveThreshold()`. The original design (built with the station's first commit) added
flat penalties: **`REGIONAL_PENALTY = 0.20`** if a taxon somehow reached this stage
un-regional (defensive; case (1) already excludes it) and **`SEASONAL_PENALTY = 0.15`**
if the detection month fell outside a species' plausible GBIF-derived window (the exact
**+15%** figure) — both additive to `displayThreshold`, capped at `MAX_EFFECTIVE_THRESHOLD
= 0.99` so a penalty can never make a species literally undetectable (§4's "never drop a
class, down-weight it," applied a second time, at the threshold rather than the class
list).

That original design was found to be measuring the wrong question (§9, 2026-08-02): DOF's
list is a **national checklist including every vagrant ever recorded** — 455 of 6522
BirdNET classes pass as "Danish" — so it rated Dark-eyed Junco and Wood Pigeon
identically while quietly deleting only the classes so absurd an observer would have
noticed the model was guessing. It answered "has this ever occurred in Denmark" where the
real question is "how likely is this here, this week."

**Replacement: BirdNET's own location/week meta-model** (`MetaModel.kt`,
`BirdNET_GLOBAL_6K_V2.4_MData_Model_V2_FP16.tflite`, 14.8 MB, also `adb push`ed and
optional). Input `[lat, lon, week 1–48]`, output raw (un-sigmoided) occurrence values
for the same 6522-class index — run once at startup for all 48 weeks at the station's
coordinates (`PriorBuilder`, ~48 cheap inferences, only non-zero priors stored) and
looked up per detection against the **week the bird was actually heard in**. Measured at
Copenhagen, week 29: Wood Pigeon 0.99, Blackbird 0.72, Magpie 0.53, Tawny Owl 0.038,
Spotted Crake 0.0086, Dark-eyed Junco 0.00008 — **five orders of magnitude of range**, so
the penalty is applied **on a log scale** (`PRIOR_FULL = 0.05`, `PRIOR_PENALTY_PER_DECADE
= 0.12` per factor-of-ten below it) rather than linearly, which would read the last three
species as indistinguishably "about zero" when they are a plausible bird, an implausible
one, and a near-impossible one. Same non-negotiable rule as before: it down-weights, it
never drops, and a genuinely strong detection of a rare species can still surface.

**When the meta-model is present, it replaces the regional/seasonal booleans for
threshold purposes entirely.** When it's absent (a station whose model file was never
pushed), `effectiveThreshold` falls back to the original `REGIONAL_PENALTY`/
`SEASONAL_PENALTY` addition — a station without the newer file gets the weaker original
filter, not none at all. `regional`/`in_season`/the prior lookup are all captured **once,
at detection time, against that detection's own calendar month/week** (`Taxon`'s
`monthFractions` doc, `Database.withCuration`'s prior lookup) — a July detection is judged
by July's plausibility forever, never re-evaluated against whatever month the dashboard
happens to be opened in.

### 11e. Per-species threshold overrides

`species_settings` (`taxon_key → threshold_override`) is a human's precise, standing
correction for one species — "this Ruddy Shelduck call was actually my daughter's voice,
raise the bar." Set via `POST /api/species/{key}/threshold`, three body shapes: `{delta}`
bumps the *current baseline* (override if one exists, else the global default) and clamps
to `[0,1]` — this is what the feed's **"False positive? +5%" / "+10%"** buttons send, so
repeated bumps are monotonic; `{threshold}` sets an absolute value; `{threshold: null}`
clears it. **An override replaces the entire region/season/prior stack for that species —
it does not stack with it.** Once a person has given a precise number, that number is
authoritative over every heuristic. Like every other curation setting, this is read-time
only: it takes effect on the next query, nothing is reprocessed.

### 11f. Curation: confirmed vs. candidate, and the bout-rule correction

Two thresholds govern what's stored vs. what's shown, and the gap between them is
deliberate: `retentionFloor` (default **0.10**) decides what's **written** — this is the
one write-time setting, and lowering it later only affects future detections, since rows
below the old floor were never stored. `displayThreshold` (default **0.65**, BirdNET-Pi's
common operating point — stated explicitly as *a starting point, not a measurement*, since
no per-station calibration table exists yet) plus the effective-threshold stack above
decides what's **confirmed**. Every candidate stays queryable at `state=candidate`.

**The repeat rule originally counted raw detections** (`repeatRequired = 2` within
`repeatWindowMin = 30` minutes) and broke on real audio: a **19-consecutive-window**
*Porzana porzana* (Spotted Crake) run, scored 0.918 in August on a Copenhagen balcony (a
species that calls April–June, at night) — one continuous ~30 s noise event, not 19
independent hearings. **30 of 47 inter-detection gaps were exactly 1.5 s**, i.e. the hop:
the rule was measuring how long a sound lasted, divided by the hop interval.

**Fixed to count bouts, not windows.** `boutGapSeconds` (default **60**) — two detections
of the same species belong to the same bout unless more than 60 s of silence separates
them; `repeat_count` in the API now reports bout count, keeping its name because its
*meaning* ("how many times did I hear this") is unchanged even though the old
implementation was answering a different question. Measured on **19 h of known-negative
audio** (station run overnight in a lounge with no reachable bird sound, so every one of
5,499 detections across 101 species is a labelled false positive — a corpus that bounds
false positives and nothing else, never a target to tune toward):

| stage | false rows | false species |
|---|---|---|
| flat threshold + raw-count repeat rule | 102 | 9 |
| + bout rule (>60 s gap) | 81 | 6 |
| + prior threshold (§11d) | 50 | 4 |

The bout rule alone is a 21% cut — **necessary, nowhere near sufficient**: Spotted Crake
survives it because over 19 h it genuinely fired in 151 separate bouts (an intermittent
source, not a continuous one), and only its markedly nocturnal hourly profile (peaking at
11pm) versus the display's daytime pattern would flag it further. It's the prior
threshold that removes it.

**A same-table consistency bug was caught in the process**: `daySummaries` used to skip
the repeat check as a "cheap approximation," so on a freshly cleared station one 0.675
row with no repeat made `/api/days` report "1 detection, 1 species" while `/api/species`
reported 0 confirmed **from the same table in the same second** — the §2d failure mode
again, two endpoints describing one day and disagreeing. Both now share the same bout
logic.

**The co-occurrence hypothesis (§8) was tested on this corpus and REFUTED.** The idea —
suppress any window where ≥3 distinct species fire at once, since that's more plausibly
one broadband event exciting several classes than three animals calling in the same three
seconds — removes **zero** false confirmations here (81 before, 81 after) while
suppressing 250 candidate rows. Of 5,022 detection-producing windows, 4,639 are
single-species, and the multi-species ones are uniformly low-confidence: the rule can only
ever discard rows the confidence threshold had already excluded, so it does no work at the
threshold that actually reaches a person. Logged and killed, not merely left unbuilt.

### 11g. Storage: two floors three orders of magnitude apart, and an 8 GB cap

`CLIP_CAP_BYTES = 8 GB`. `pruneToCapBytes` runs every 10 minutes, deleting the **oldest
clip files** first until under cap and **nulling `clip_path` on the row** — the detection
row itself is never deleted by pruning (API.md: `clip` may be `null` for "a candidate
whose clip was pruned").

**One floor governing both costs was a real bug, not a hypothetical one.** A detection
row is ~358 bytes; the 3 s WAV clip beside it is ~288,000 bytes — three orders of
magnitude apart. At a single `retentionFloor = 0.10` gating both, the station wrote
**5,022 clips in 19 hours** (≈667 GB/year against ≈908 MB/year of rows), so the 8 GB cap
held **four days** of audio. Fix: a separate **`clipFloor`, default 0.50** — rows still
retain at 0.10 (where the sub-threshold tail the noise research in §11f depends on stays
intact, and where a slider can still re-filter it), but only windows scoring ≥0.50 get a
clip written at all. Same cap now holds **~83 days**, with zero rows dropped to get there.
Whether *anything* in a window clears the clip floor is decided once per window, not per
hit — there's only one clip, so if anything in the window is worth keeping, the window is.

**Pinning protects what pruning and clearing must never touch.** `species_status`'s
`lifer_detection_id` (the detection that first confirmed a species) and `best_detection_id`
(the strongest evidence on file for it) are exempt from `pruneToCapBytes` and from
`clearAll`'s default clip deletion — `pinnedDetectionIds()` is consulted by both.
This is a **live hazard already found**, not defensive caution: `onUpgrade` has issued an
unconditional `DELETE FROM detections` in **two of the database's five schema versions**,
and `pruneToCapBytes` was independently found to delete clip files while leaving rows
behind. Neither was a bug on the day it was written — the life list didn't exist yet — but
both are fatal to it now, since the audio behind a confirmed lifer is unrecoverable. The
rule going forward, stated in the code as the single most dangerous habit in the file's
history: **every migration from schema v5 onward must exclude `pinnedDetectionIds()`.**

### 11h. The life list, and verification as the only gate

Three tables, schema v5: `species_status`, `verifications`, `species_prior` (§11d).

**A species becomes `confirmed` only through `recordVerification`, from a human `"yes"`
verdict — no score, however high, promotes one.** The list starts **empty**; nothing is
grandfathered from existing detections, and candidates accumulate only from new
detections going forward (`noteDetection`, called on every insert, tracks a running
`best_detection_id` by confidence but never changes `status`). This is the design, not an
incidental gap: gating the list on a human decision is what turns verification from a
chore into something with a visible reward, and the dashboard shows the stake above the
Confirm/Reject buttons ("adds X to your life list — #11") for exactly that reason.

**A rejection is a stronger statement than any score** — the observer listened and said
no — so `recordVerification(verdict="no")` marks the *species* rejected (only while it has
never been confirmed; one bad clip can't retract an already-earned lifer) and rejected
species are excluded from every "what was here" aggregate: the species list, the day
rollups, the top-species panel. Their detection **rows are deliberately not hidden** from
the raw feed — rendered at reduced opacity — because seeing the model keep producing a
rejected call is exactly the signal that a per-species threshold override (§11e) is due;
hiding it would delete the evidence that something is still wrong. The first confirmed
detection this station ever produced was a Tawny Owl that turned out to be a child in the
background — the mechanism earned its keep immediately.

**The Verify tab is a card-stack, one species at a time**, backed by `GET
/api/verify/queue`: one card per distinct scientific name (`distinctBy`), ordered by
local-occurrence prior ascending then confidence descending — the rarest, highest-stakes
calls surface first, while attention is fresh. A card requires the row to still have a
clip (nothing can be verified by ear without audio, and the clip floor means not every row
has one). A setup screen states the queue size **before** committing and offers a session
cap (10/25/all, default 10) that counts cards **presented, not resolved**, so skipping a
card can't silently extend a session past what it promised.

The card's spectrogram is computed **client-side in the browser**, from the 3 s clip, with
every constant lifted directly from `Spectrogram.kt` (§11i) — 2048-point Hann, 256
log-spaced bins 200 Hz–12 kHz, per-bin max, a fixed 35 dB window over a 20th-percentile
floor — so a verification card looks like an instance of the same instrument as the live
view, not a different one. One deliberate, documented divergence: the station's live floor
subtracts a rolling **30 s** history, and a 3 s clip has no 30 s of context, so the
percentile is taken over the whole clip instead. Still not per-frame normalisation, which
is the thing that must never happen (§11i).

### 11i. Publisher interface and the offline queue

```kotlin
interface Publisher {
    val name: String
    suspend fun publish(ids: List<Long>): Result<Unit>
}
```

Every detection lands in a SQLite outbox (`outbox_delivery`, via `Database.insert` then
`markDelivered` per publisher) **before any publisher runs**. `LocalPublisher` is the only
implementation today, and its "publish" is a no-op in substance — the detection is already
durable the moment `insert` returns, so "delivery" is just recording that fact. The queue
exists now, while nothing is remote, precisely so that adding a remote publisher later is
a new `Publisher` implementation reading `pendingForPublisher("your-name")` on a retry
loop, plus a token — **not** a change to the write path, the schema, or the capture/
inference loop. The seam is the point, not the implementation.

### 11j. Dashboard: five tabs, SSE feed, and a streamed spectrogram

Restructured (2026-08-03) from a detection feed with tabs bolted on into **Live / List
(life list) / Verify / Days / Settings**, because the life list is treated as the
product's spine rather than a feature beside it. Every colour, type, space, radius and the
spectrogram ramp are CSS custom properties sourced from `.claude/skills/biomon-ui/
SKILL.md` (`tokens.css`), so a hardcoded hex is now visibly wrong rather than merely
undisciplined; a subtle grain overlay (2.5% turbulence) keeps dark flat OLED fills from
reading as plasticky.

- **Live feed via SSE** (`GET /api/events`): `hello` fires immediately with `server_ms` so
  the client anchors its `since_ms` cursor to the **server's** clock, never its own — phone
  and browser drift independently, and a skewed client clock would silently skip or
  repeat detections; `detection` per new row; `status` (the full `/api/health` body) every
  15 s; a `: keepalive` comment every 15 s to stop intermediaries timing the connection
  out. The dashboard also polls `/api/detections?since_ms=` as a fallback, since SSE
  survives a phone hotspot noticeably worse than a plain GET.
- **The live spectrogram is streamed, not polled.** The first version rebuilt it in JS
  from a 3 s WAV polled once a hop — latency, tearing, a hard resolution ceiling, periodic
  jitter. It was replaced with a dedicated WebSocket (`/api/spectrogram`) over which the
  phone streams one **265-byte binary column** per FFT frame at a **30 columns/s** target:
  byte 0 message type, bytes 1–8 a little-endian `float64` timestamp in epoch seconds,
  bytes 9–264 256 magnitude bytes. Fed from `AudioCapture`'s separate continuous PCM tap
  (§11b) rather than the 50%-overlapping inference windows, so nothing is drawn twice.
  Column timestamps derive from the **sample counter against a wall-clock anchor**, not
  the clock at emit time — BirdNET runs synchronously on the same capture thread, so an
  emit-time stamp would encode its own ~190 ms stall as jitter in a display whose only job
  is to look continuous. **Backpressure drops the oldest queued frame on overflow** rather
  than buffering: a late column is not late data, it is *wrong* data, since the client
  always draws the newest column at the right edge as though it were now.
- **256 log-spaced bins, 200 Hz–12 kHz** (`Spectrogram.kt`) — log-spaced because a linear
  axis to 22 kHz spent half its height on 11–22 kHz, where a Copenhagen balcony has
  nothing, and crushed every bird into the bottom strip. Within a bin range the **max**, not
  the mean, is taken — a bird call is a narrow peak, and averaging it with the silence
  beside it low-passes exactly the thing being looked for.
- **Adaptive noise floor, never per-frame normalisation.** Per-frame min/max stretches a
  quiet frame's noise to full brightness, which is why an earlier version was a solid
  purple wash of traffic rumble. Instead each of the 256 bins keeps a rolling **30 s**
  history and subtracts its own **20th-percentile**, so a steady sound (traffic, wind, a
  fridge two floors down) *is* its own percentile and subtracts to zero. Made cheap on a
  2017 Snapdragon 835 (already spending ~190 ms of every 1.5 s hop inside BirdNET, §11n) by
  an **incremental per-bin 1 dB histogram** (2 array writes/bin/frame, not a 230k-element
  sort 30×/second) with the percentile itself walked only **twice a second**, and the
  *applied* floor **gliding** toward each new target between refreshes rather than
  stepping — a step every 15 frames would draw a visible seam across the whole strip twice
  a second, which the eye catches instantly.
- **Palette: a custom five-stop amber ramp, not matplotlib's magma.** `#100D0B → #3B2416 →
  #8A4A1E → #E8A33D → #FFF3D6`, defined as `--spec-00`…`--spec-100` in `tokens.css` and
  interpolated into a 256-entry LUT client-side via `getComputedStyle`, so the palette
  stays single-sourced with the rest of the design system rather than duplicated as a JS
  constant. (Worth being precise about: this is the project's own "ember" ramp, built to
  match the dashboard's design tokens — it is visually magma-*like* in that both run
  dark→warm→pale, but it is not the standard scientific magma colormap.)
- **Species photos** (`PhotoCache.kt`, forked from §10's fetcher) — same two governing
  lessons: **batch** (MediaWiki accepts up to 50 titles/request, so ~11 requests covers the
  whole species list) and **never conflate a transport failure with "no photo"** (§2d) —
  the API's `photo` field is only ever `null` for "not available right now," and the cache
  tracks the real reason internally. Runs on a 10-minute tick (first fire delayed 30 s to
  let initial detections land), gated on validated internet connectivity, against
  **confirmed** taxa only.
- **Per-species "False positive? +5% / +10%" bump buttons** in the feed (§11e).
- **Settings' "Clear all"** previews before it destroys: `GET /api/data` returns
  detection/clip/confirmed-species/pinned-clip counts; `DELETE /api/data` executes and
  reports what it *actually* deleted (row/clip counts, whether the life list survived) —
  the earlier version reported only `"Cleared."` even when it had left photos and orphaned
  clips behind, which is how it earned the description "it didn't clear everything." The
  life list is kept by default (only `candidate` species purged; `confirmed`/`rejected` are
  human decisions and survive) unless the caller explicitly opts in with
  `?life_list=true` — destroying it is a separate, deliberate act.
- **Photo lightbox** for full-size viewing from any thumbnail. Bound to `.thumb img`, which
  is every species thumbnail the page renders — the feed, the day list, the life list and
  the triage row all use the same component, so there is one selector rather than a list of
  places that has to be kept in step.
- **One global link rule.** Links are `--ink-soft` and underlined. This has to be global,
  not per-component: an unstyled anchor falls back to the browser's `#0000EE`, which is
  unreadable on this ground and is the one colour in the app nobody chose. It matters most
  for the xeno-canto attribution (§11q), where the licence requires the credit to be
  legible. Navigation never earns `--alive`, `--ember` or `--signal`.
- **Build stamp in the header**, beside the station name, not tucked into Settings — it
  answers "is this the build I just installed," not "what's the device's health," which are
  different questions. (§11k)

### 11k. Schema, versioning, and build identity

`station.db` (`SQLiteOpenHelper`, current version **7**): `detections`, `species_settings`,
`outbox_delivery`, `species_status`, `verifications`, `species_prior` — the same six tables
as v5. v6 added `detections.clip_start_ms` (§11m); v7 rebuilt `verifications` around the
two-part verdict (§11p). Nothing is keyed on `bout_id`: a bout is a read-time projection
that moves when `bout_gap_s` moves, so verdicts key on detection ids (§11p). A fresh
install lands directly on **v7**. `onUpgrade`'s history is worth reading in
the source (`Database.kt`) precisely because two of its early versions issued an
unconditional `DELETE FROM detections` before the life list existed to protect — left in
place rather than rewritten, since a device that already ran them can't un-run them, with
the v5-onward pinning rule (§11g) as the fix that actually matters going forward. That rule
governs every future migration: **`Database.kt:214`, preserve `pinnedDetectionIds()`, never
copy the unconditional delete.**

**A build stamp exists because `versionName` couldn't answer the only question that
actually got asked.** `versionName` stayed `"0.1.0"` across six installs in one session
while the running code changed underneath each time — real, costly confusion (four
backend builds shipped against an older dashboard with nothing on screen to tell them
apart). `build.gradle.kts` resolves the short git commit and a dirty-tree flag at
*configuration* time (`git rev-parse --short=8 HEAD`, `git status --porcelain`), failing
soft to `"nogit"` if there's no repository, and stamps both plus the build timestamp into
`BuildConfig` — surfaced in `/api/health` (`app.commit`, `app.built_at`) and the dashboard
header. A trailing `+` on the commit means the tree was dirty at build time, because
"which commit" is a lie if uncommitted edits went into the APK.

**No new Gradle dependencies** (`build.gradle.kts`'s explicit rule): everything the module
uses is already in the cache `:app` populated, which is why the HTTP server is a
hand-written `ServerSocket` implementation (one thread per connection — adequate for "a
LAN appliance serving a handful of concurrent viewers plus long-lived SSE subscribers")
rather than a pulled-in NanoHTTPD or Ktor. A dependency that won't resolve offline is a
build that fails on the day the wifi is down.

### 11l. The dashboard is a file on the phone, and it updates itself

The station is bolted outside and is meant to be touched as rarely as possible. Everything
after deployment happens either from a personal phone on the same wifi or from a machine
that has never seen the station — and **nothing outside the LAN can reach it**, because it
is behind NAT. That single constraint decides the shape of everything below: the station
**pulls**, it is never pushed to, and no part of this may assume adb, USB or a laptop.

**Where the dashboard lives.** `index.html` and `tokens.css` are served from
`getExternalFilesDir("dashboard")` — the same pattern as `models`, `clips` and
`species_photos` — not from the APK's `assets`. Serving them from the APK meant the
dashboard's iteration speed was pinned to the APK's: a one-line CSS change cost a rebuild
and a physical reinstall of the whole application. The assets stay in the APK as the
**first-run seed** (copied into place at service start, before the HTTP server can answer
anything) and as the **recovery path**: `DashboardStore.read` falls back to the bundled
asset whenever the stored file is missing or unreadable, so the worst case is serving the
UI that shipped with the build, never serving nothing. On a phone with no adb, "no
dashboard" is unrecoverable, and that is the failure the fallback exists to make
impossible.

**`POST /api/dashboard/update`.** Fetches both files over HTTPS from
`raw.githubusercontent.com` and replaces the stored copies. Four decisions in it are
load-bearing:

- **The source URL is a `buildConfigField`, not a parameter.** This is the whole security
  model. The endpoint is unauthenticated, matching the rest of the API — `/api/data`
  already accepts an unauthenticated `DELETE` that wipes every detection and clip, so the
  threat model is already "a trusted LAN", and a token guarding the stylesheet beside an
  open endpoint that destroys the data would be theatre. Because the URL is pinned, the
  worst a hostile device on the network can do is make the station re-download our own
  dashboard from our own repository. A URL parameter "for flexibility" would convert that
  into arbitrary script execution in the browser of whoever opens the dashboard next, and
  *that* would need a token. Point it at a fork by rebuilding.
- **Download everything, then write.** Both files land in memory, are validated, are
  staged as `.part` files, and only then get renamed over the live ones. Streaming
  straight to `index.html` means a dropped wifi association halfway through leaves the
  station serving half a document. Staging both before renaming either also prevents the
  narrower version of the same failure — a new `index.html` beside the old `tokens.css`.
- **Validation is what distinguishes "downloaded the dashboard" from "downloaded
  something".** Non-empty; length matching the declared `Content-Length` (a short read is
  not an exception, it is a silent truncation); and containing a marker the real file
  always has — `id="panel-live"`, `:root`. Status codes alone are not enough: a GitHub 404
  body, a CDN error page and a captive-portal login page can all arrive looking fine to
  code that only checks for 200.
- **§2d, applied directly.** A network error, a non-200 and a failed validation are three
  different sentences, reported as three different `error` values with a human-readable
  `reason`, per file, and the response says plainly that nothing was overwritten. The
  Settings button surfaces the reason verbatim; "update failed" would send someone to
  debug the wrong thing, which is exactly the cost §2d was written down to stop paying.

**Build identity now has two halves.** §11k's build stamp answered "is the phone running
what I just built?" — but the dashboard now updates independently of the APK, so the
commit no longer identifies the UI on screen. `/api/health` gained a `dashboard` block
(`stored`, `updated_at_ms`, `source`) and the header shows the dashboard's own timestamp
beside the commit, reading `ui bundled` when nothing has ever been pulled.

**Releases** (`.github/workflows/release.yml`). The install path is now "open the release
page in the phone's browser and tap the APK", so CI has to produce an APK that actually
installs. It builds on every merge to `main`, publishing `build-<run number>` the way the
insect repo's workflow does, and separately on a `v*` tag for real versions —
run-numbered builds cannot overwrite a version release. `versionCode` comes from the CI run
number rather than being hardcoded to `1`, or every update after the first is refused as a
downgrade.

**Only a release signed with the configured keystore is installable as an update, and the
debug fallback is not a substitute for one.** This was initially written down wrongly and
is worth stating precisely, because the failure it invites is a wipe. Gradle's debug
signing config reads `~/.android/debug.keystore` from the machine doing the building — a
stable file locally, which is why local debug builds install over each other, but a file a
CI runner does not have at all, so AGP generates one with a fresh random key per run. A
CI `debugkey` APK therefore installs over neither the phone's current build nor the
previous CI build. The fallback earns its place only by keeping `assembleRelease` from
emitting an *unsigned* APK, which cannot be installed even once; the artifact is build
verification. Which key signed a build decides whether the next install is free or costs an
uninstall — and an uninstall deletes `station.db` and the two BirdNET models (~67 MB,
gitignored, restorable only over adb from a computer) — so the key is in the APK's
filename and leads the release body, and Gradle rather than the workflow decides it, so
there is only one copy of that decision. The workflow degrades to deriving the label from
whether the secret was set, with a warning, rather than failing a job whose compile already
succeeded.

The label is still only a claim about which *config* was selected, though, and the thing
that actually decides an install is the certificate in the file. So the workflow reads it
back out of the finished APK with `apksigner` and publishes the **SHA-256 fingerprint and
DN** in the release body, in the same lower-case colon-free form `keytool` can be coaxed
into printing for the local keystore. That turns "is this the key the phone trusts?" from
a thing you assume into a thing you compare. Like the label, it is best-effort: a missing
`apksigner` reports `unavailable` and does not fail a build that already compiled —
absence means "not verified here", never "verified fine". The keystore to upload as a secret is the **existing
`~/.android/debug.keystore`** from the machine that first built the app; supplying a newly
generated one instead would force exactly one wipe.

**Cross-origin.** The dashboard resolves a configurable station base (`?station=`, then
`localStorage`, then `""` for same-origin) and prefixes every request with it — including
the spectrogram WebSocket, which used to derive its host from the serving origin, and the
server-relative `clip.url` / `photo.url` the API returns, which the browser would otherwise
resolve against whatever is serving the page. `writeCors` also had to learn `DELETE`, since
`/api/data` is DELETE-only and cross-origin "Clear all" was dying at the preflight. With no
`?station=` parameter the base is `""` and the on-phone dashboard behaves exactly as
before; this exists so the UI can be iterated on from a laptop against a real station or
the mock.

### 11m. Bout clips: what gets recorded, and why it is not a window

**The window is the model's unit, not the listener's.** BirdNET scores 3.0 s and the clip
was 3.0 s, which was never a decision so much as an absence of one. Song identification
depends on rhythm, repetition and phrase structure; three seconds is often a single chirp,
and a single chirp is ambiguous to an expert. The station's user is explicitly not an
expert — he can tell a bird from a bike brake and cannot name species by ear — so a clip
that only an expert could use is a clip that cannot be verified at all.

Three consequences, each of which forces a piece of `BoutRecorder`:

- **Pre-roll.** Detections fire mid-phrase and the opening notes are frequently the
  diagnostic part, so the clip must begin before the trigger. A ring buffer fed from
  `AudioCapture.onPcm` — the continuous tap that already exists for the spectrogram, every
  sample exactly once, before windowing — holds the recent past so it can be written
  retroactively. The ring is exactly the pre-roll plus the model window (8 s, 768 kB at
  48 kHz mono 16-bit) because the whole ring is dumped when a clip opens and that is
  precisely the audio wanted.
- **Post-roll forces a deferred write.** The future cannot be recorded, so the clip is
  finished seconds after the detection that triggered it and the rows are updated
  afterwards. This is the only place in the station where a row is written incomplete and
  filled in later, and it is unavoidable.
- **One clip per bout.** The recording stays open while detections keep arriving, so a
  bird singing for twenty seconds produces one twenty-second clip instead of fourteen
  overlapping fragments — fewer files *and* better ones. A hard 60 s cap bounds it; a bird
  can sing for ten minutes and nobody listens to a ten-minute WAV.

**The audio-continuity gap is deliberately not `boutGapSeconds`.** That setting (60 s)
answers "are these the same piece of evidence?" and continues to do exactly that in
`countBouts`. Whether to record the silence *between* two calls is a different question,
and answering it with 60 s was measured against the committed corpus to produce 35% more
bytes than a 4 s gap, all of it silence, in clips a person then has to sit through.
Adjacent windows of one continuous song are 1.5 s apart, far inside either value, so the
case that motivated bout clips is covered identically. Two questions, two constants.

**Consistency was the hard part, and the rule is one-way.** Audio streams to
`<name>.wav.part`; the header is rewritten with the true length, the file is renamed into
place, and only then do the rows learn the path. Every abnormal exit therefore lands on one
of two states — a finished clip with rows pointing at it, or an orphan `.part` that no row
references and that the next start deletes. Service shutdown finishes the bout short rather
than discarding it (four seconds less audio beats no audio). A failed write abandons the
clip and leaves the rows with `clip_path` null, which the API already documents, rather
than leaving a truncated file that would play as a fragment and look like success (§2d).
The same principle put `clips_failed` in `/api/health` and a banner on the dashboard: a
station that has silently stopped keeping audio otherwise looks exactly like a station on a
quiet day.

**Sharing one file broke three storage paths that counted rows.** A bout clip is referenced
by every detection in it, including detections of other species heard in the same passage.
`clipsBytesTotal` summed per row and so multiplied a shared file's bytes by its row count,
driving the cap to prune audio that was never over it; `clipsCount` reported a dozen clips
where there was one; and `pruneToCapBytes` deleted a file while nulling only one of the
rows naming it, leaving the rest advertising a clip that 404s. All three are now keyed on
distinct `clip_path`, and a file is pinned if *any* of its rows is pinned — the lifer is
the audio, not the row that happens to name it.

**Storage was modelled, not guessed.** The 19 h corpus is the only measured clip-rate
evidence available: 266 windows cleared the 0.50 clip floor, ~96 MB/day as 3 s files, which
8 GB held for ~85 days. The same triggers as bout clips are 202 files averaging ~12 s,
~306 MB/day, which 8 GB would hold for only ~27 days. Two assumptions are stated in the
constant because they move the number: that corpus is indoor and known-negative so its
triggers are isolated, making it the *worst* case for bout merging and a conservative basis
for sizing; and the phone reports >100 GB free, so the cap is a cap and not a reservation.
16 GB gives ~54 days at the modelled worst case, comfortably more than the few weeks a
verification backlog actually needs.

### 11o. The bout as the unit of judgement, and the day list as a species list

**§11m created a mismatch and this resolves it.** `countBouts` groups detections separated
by less than `bout_gap_s` (60 s); `BoutRecorder` closes a recording 4 s after the last
detection. Both are right — they answer different questions, which is exactly why they were
given different constants — but together they mean a bird calling every twenty seconds is
**one bout and three clips**. The verification design assumed one bout, one clip, one
judgement, and that no longer holds.

Resolved in favour of the evidence unit. **The bout stays the thing being judged**, and it
owns an ordered list of clips rather than a single path. Playback runs them back to back as
one listening experience. The silence between clips is *not* reinserted — it was
deliberately never recorded — but the seam is drawn, one bar per file with a real gap
between them, so the jump reads as a seam rather than as a glitch or as the bird changing.

**Five answers, not two.** An empty clip list means five different things and collapsing
them into "no audio" is §2d in its most concrete form:

| `audio.state` | what happened |
|---|---|
| `recorded` | every detection meant to have audio has it |
| `partial` | pruning takes whole files, so a multi-clip bout can lose its beginning and keep its end |
| `pending` | the station may still be writing it — **this is not "none"** |
| `none` | nothing here ever cleared the clip floor |
| `unavailable` | recorded and gone: pruned, or the write failed |

`pending` is the state that did not exist before bout clips. It is the one a UI renders as
"no audio" by accident, and the resulting bug looks like a fault in the recorder rather
than in the display — so the API names it and the dashboard prints "recording still being
written" against it. Pruned and failed are deliberately not separated: nothing on the row
records which happened, and `storage.clips_failed` answers the actionable half.

**Pinning had to follow the bout.** `pinnedClipPaths` now expands each pinned detection to
its whole bout and pins every clip in it. Pinning only the file the pinned row happens to
sit in would let the cap delete the rest of the same bout, leaving a lifer's evidence
starting halfway through a phrase — audible and wrong, which is worse than obviously
missing.

**The day list is a species list.** One row per species per day, which is how a day list is
kept, instead of five hundred magpie rows. Expanding a row reveals its bouts; expanding is
also where the audio is, because a bout is several files.

- **Bouts are the headline number, and neither number is a bird count.** A detection count
  mostly measures how long something sang near the microphone: one magpie on the railing
  for ten minutes outscores five magpies passing through. Both are shown and both are
  labelled, so neither can be read as a count of birds.
- **The evidence travels with the count.** "Magpie — 37 bouts" reads as fact even when all
  37 are marginal, and the committed corpus is 5,499 rows of exactly that. The row carries
  peak confidence, the threshold it had to clear, and how many bouts actually cleared it.
- **The activity strip is anchored to the sun, not the clock.** Copenhagen sunrise moves
  from about 04:26 in June to 08:37 in December — over four hours — so fixed clock bins
  smear one dawn chorus across different columns through the year and destroy the pattern
  the strip exists to show. `Solar.kt` computes sunrise, sunset and civil twilight on-device
  from the station's coordinates (the standard sunrise equation, no network, no dependency;
  verified against published Copenhagen solstice times to within a minute). Six coarse
  periods, full width, no 24-column grid: this is read on a phone, a strip scales to any
  width without horizontal scroll, and six bins cannot imply hourly precision that a handful
  of bouts does not have. When the sun never crosses a threshold the edges are interpolated
  and `solar: false` says so rather than presenting a guess as astronomy.

### 11p. Verification as triage, and the two-part verdict

**The tool was asking a question its user cannot answer.** Stefan can reliably tell a bird
from a bike brake; he cannot identify species by ear. A single yes/no on "is this a
Blackbird?" forced "I don't know" to be recorded as "no", which is the §2d failure aimed at
the one table the life list is built from — and it threw away the answer that carries most
of the value, because the actual problem is the false-positive mass, not the species labels.

**Two questions.**

- *Is there really an animal here?* — answerable every time. `is_genuine`.
- *Is it this species?* — often not. `is_species`, and `unsure` here means **"something
  real, but I don't know what"**: a true, useful answer, explicitly **not** a rejection.

Deliberately **not** called `is_bird`: `taxon.group` exists so non-birds are not a breaking
change (API.md §1) and the station already carries Orthoptera, so the field is named for
the question it asks and the UI phrases it from the group — "is this really an insect?".

**The life list is tiered so it cannot overstate what was supplied**: `machine` (never
looked at), `bird` (a human confirmed something real, not which species), `species` (a
human identified it — the only life tick), `rejected`. If what was actually said was
"that's a bird", the list must not claim a species confirmation.

**Nothing persisted is keyed on `bout_id`.** A bout is a read-time projection of
`bout_gap_s`; move that and bouts merge or split and their ids move with them, so a verdict
stored against one would silently reattach to different audio. Verdicts are written per
detection id — one row per detection in the bout — which yields exactly the right behaviour
when the projection changes: a bout that **splits** leaves both halves verified, and two
that **merge** leave a part-decided bout, reported as `partial` and never rounded to done or
not-done. `bout_id` is display and in-session position only.

**A list, not a queue.** The old flow launched and advanced through items one at a time; a
flow with no visible end does not get started. Now: a list showing what each species would
cost (`12 of 20 left`), ordered by stakes — possible new species first (a wrong 38th magpie
costs nothing, a wrong new species permanently corrupts the list), then implausible for here
and now, then boundary cases near threshold — grouped by species, because a batch of one
species is one mental context and jumping between them forces re-orientation on every item.
Tap a species, do that species, come back. **Nothing auto-advances into the next species**,
and finishing one lands on a stopping point with a single button back to the list.
Resumability is free, because progress is the persisted verdicts rather than session state.

**Bulk accept is server-enforced.** Offered only once a species is on the life list and at
least two of its bouts have been confirmed individually; a first record decided in a swipe
is precisely the judgement that must not be made that way. The client hides the button and
the station refuses the request, because a rule only the client applies is not a rule.

**Ergonomics**: play control and verdict buttons together at the bottom, full width, on the
48 px touch floor — this is used one-handed on a sofa. The three answers are stacked rather
than laid out as a grid of equal tiles, because they are not equivalent choices and a row of
tiles invites tapping the middle one.

**`GET /api/data/export`** was added alongside: the API has had a `DELETE` that wipes
everything from the beginning and no way to take a copy first, and the phone cannot be
reached from a laptop. The export checkpoints the WAL and holds a transaction across the
copy, so it is a consistent snapshot rather than a database missing its most recent writes.

### 11q. Reference audio, and learning where the line is

**The comparison, not the name.** A detection proposing "Eurasian Blackbird" asks a
question this station's user cannot answer. Playing a known Blackbird beside the clip asks
a different question — *does this sound like that* — which anyone can answer, and which
teaches the bird by repetition rather than requiring it to be known already. That is the
whole feature; everything below is the care needed to make it honest.

**The key is a setting, not a secret.** xeno-canto has required an API key since October
2025. It is rate-limit attribution against a public archive, not access to anything of the
owner's, so it is entered once in Settings like any other preference — deliberately not a
build argument, a repo secret, or anything that could reach the repository. It is still
**write-only across the API**: `/api/settings` reports `xeno_canto_key_set`, never the
value, so a screenshot of the settings screen leaks nothing, and it is never written to a
log line.

**The station ships with no key, so the no-key path is the real path.** Four states, each
said as itself:

| state | meaning |
|---|---|
| `no_key` | nothing was attempted. Not a failure, not an absence of recordings |
| `ready` | cached and playable, with recordist, country, quality and source |
| `none` | the archive answered and has nothing for this species and region |
| `failed` | the lookup failed; the reason says which way |

Collapsing them would read as "the archive has nothing for this species", which is one of
four and usually the wrong one. Every non-`ready` state leaves verification fully usable:
the reference is an aid, never a precondition.

**Thresholds learned from verdicts, and no global one.** `speciesThresholdOverrides` and
`effectiveThreshold` already existed as manual settings; they are now fed from verification
data. A learned threshold replaces the global default for its species exactly as a manual
override does, and ranks below one, because a human's explicit number still wins.

There is **no global "trust above X", and adding one would be a mistake with evidence
against it**: the committed known-negative corpus peaks at **0.98** and is entirely false
positives. BirdNET scores are not calibrated probabilities and vary by species, site and
noise, so the only defensible threshold is one measured per species at this location.

**Rejections locate the boundary; confirmations only bound it from above.** Ten
confirmations and no rejections say the line is somewhere below the lowest confirmation and
nothing more. So a species is calibrated only with at least three of each, and the rule
prefers the most conservative line that admits no known false positive, falling back to an
error-minimising cut (ties broken upward — a false accept corrupts the record, a false
reject costs one more listen) only when the classes overlap. "Something real, but I don't
know which" counts on neither side, because it says nothing about *this species'* boundary.

**Exemption needs both halves.** A calibrated threshold says "this score is usually right
for this species here"; plausibility says "this species being here is normal". A confident
score for an implausible species is exactly the case worth attention, so neither alone
exempts. Always verified regardless of score: anything that would be new on the life list,
anything implausible, anything from a species with no learned threshold, and a **1-in-10
random audit** of otherwise-exempt bouts — deterministic on the bout's first detection id,
so it cannot be re-rolled by refreshing, and there to catch drift as noise sources change
and the microphone ages.

**Calibrated is marked as calibrated.** A species heard twice a week is months from having
evidence on both sides, so the triage rows, the species header and a Settings list all
distinguish a learned threshold from a species that has simply never been checked. An
exemption a person cannot see is indistinguishable from a bug, so `bouts_exempt` is
reported on the row rather than quietly missing from the count.

### 11n. Measured performance

- **Inference: ~190 ms per 3.0 s/1.5 s-hop window on the OnePlus 5T**, 4 threads, the
  full 6522-class FP32 model (not a quantised or embedder-only chain — unlike the
  live-display work in §10g, the station always had to run the acoustic model itself,
  since it *is* the detector, not a candidate preview of one). `/api/health`'s
  `inference.duty_pct` is computed directly as `last_inference_ms / (hop_s × 1000)`: at
  190 ms over a 1500 ms hop that's **≈12.7%**, matching the ~12% figure observed in
  practice.
- **End-to-end latency** — sound occurring to it appearing on the dashboard — is bounded
  below by the 3.0 s window itself (a detection can't exist before its window closes) plus
  scheduling/network/DB overhead, landing observationally in the **3–5 s** range. This is
  the cadence implied by the window/hop/inference numbers above, not a separately
  instrumented end-to-end trace.
- **Thermal degrade, not OS throttling**: §11b's every-other-window skip at
  `THERMAL_STATUS_MODERATE`+ is the station's own decision (`thermal.throttled` in
  `/api/health` is explicitly documented as "the station's own decision, not the OS's" —
  the dashboard is told to say so plainly rather than hide it).
- **Spectrogram cost was engineered, not assumed affordable**: the incremental histogram
  and twice-a-second percentile refresh (§11j) exist specifically because a true 20th
  percentile over 900 frames × 256 bins, sorted 30×/second, is ~230k elements/second on a
  chip already spending ~190 ms of every 1.5 s hop inside BirdNET — nowhere close. The FFT
  itself (2048-point, hand-written radix-2, ~11k butterflies/column) is "well under a
  millisecond" per column at 30 columns/s. **Explicitly not yet measured on-device**: the
  spectrogram's own achieved frame rate and thermal cost — `measured_cps` and
  `frames_dropped` are exposed in `/api/health` for exactly that measurement, not yet
  taken as of the streaming rewrite.

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
