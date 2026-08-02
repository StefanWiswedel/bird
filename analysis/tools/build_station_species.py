"""Build the bird station's on-device species asset from BirdNET's own label file.

    python analysis/tools/build_station_species.py

Writes `recorder/station/src/main/assets/station_species.json`: one entry per BirdNET
class, in model-output order, carrying the stable taxon key the HTTP API and the photo
cache use (API.md §1), plus two pieces of ADVISORY metadata.

THE OFF-BY-ONE THIS FILE EXISTS TO PREVENT
------------------------------------------
`BirdNET_GLOBAL_6K_V2.4_Labels.txt` holds exactly 6522 entries and ends WITHOUT a
trailing newline, so `wc -l` reports 6521 and any "count the newlines" reader is one
short. The mirror-image trap has already cost this project once: the on-device spike
read a `labels.csv` with one line MORE than the model had outputs, and every logit was
attributed to the species after the one it belonged to — species-level findings had to
be retracted (DESIGN §10f, 2026-07-29). A label list is not a list of names, it is an
index-to-name map, and it is only correct when its length equals the model's output
width. So this builder asserts N_CLASSES == 6522 and dies rather than emit a shifted
asset, and the verification pass re-reads what it wrote and re-checks the same thing.

WHAT `regional` AND `months` ARE — AND ARE NOT
----------------------------------------------
Both are ADVISORY, applied at READ time only. Neither may ever drop a class, gate a
class out of the model's 6522 outputs, or suppress a detection at inference. The project
rule is never drop a class, down-weight it (DESIGN §2, §10g): a species that cannot be
reported is worse than a false positive, because nothing in the output ever hints that
it is missing — that is exactly how *Tringa glareola* was invisible for months.

And do not oversell what they buy. The seasonal filter measured on real data removed
ZERO detections on top of the confidence threshold. This builder emits the data; it
makes no claim that using it changes an outcome.

SOURCES
-------
- classes:  birdnetlib's shipped BirdNET_GLOBAL_6K_V2.4 label file (the model's own).
- regional: `analysis/biomon/species_lists/dof_checklist_2025.json` — DOF's official
  Danish list, Appendix 1 (Categories A/B/C only; D/E/F are escapes/introductions).
  Matched with the SAME abbreviated-subspecies expansion `species_lists/build_lists.py`
  uses, for the same reason: DOF follows AviList, which ranks Hooded Crow as the
  subspecies *Corvus corone cornix*, while BirdNET has *Corvus cornix* as a species. A
  binomial-only intersection would silently delete the commonest crow in Denmark.
- months:   `analysis/gbif_month_cache.json`, the raw per-month Danish occurrence counts
  `tools/build_season_data.py` fetched from GBIF. Note this asset stores FRACTIONS, not
  the 12-bit plausibility mask the phone app's `species_months.json` stores — the mask
  is a thresholded view of these same numbers, and a station that keeps the fractions
  can change its mind about the threshold without another 500-request GBIF crawl.

Stdlib only, on purpose: TensorFlow is not required (and is currently broken here) —
nothing about mapping index to name needs the model loaded.
"""
import json
import os
import re
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
ANALYSIS = os.path.dirname(HERE)
ROOT = os.path.dirname(ANALYSIS)

LABELS = os.path.join(ANALYSIS, '.venv-bn', 'Lib', 'site-packages', 'birdnetlib',
                      'models', 'analyzer', 'BirdNET_GLOBAL_6K_V2.4_Labels.txt')
DOF = os.path.join(ANALYSIS, 'biomon', 'species_lists', 'dof_checklist_2025.json')
GBIF_CACHE = os.path.join(ANALYSIS, 'gbif_month_cache.json')
DEST = os.path.join(ROOT, 'recorder', 'station', 'src', 'main', 'assets',
                    'station_species.json')

MODEL = 'BirdNET_GLOBAL_6K_V2.4'
N_CLASSES = 6522          # verified against the shipped file; see module docstring

# BirdNET's 6522 outputs are not all taxa. These eleven are acoustic-context classes and
# are emitted with group != "bird" so the dashboard never files a siren (or a human voice)
# under birds. They are NOT removed: dropping them would shift every index after 1949 —
# precisely the §10f bug — and their scores are legitimately useful as a noise signal.
#
# Hardcoded by exact label, not inferred from word count: a naive "binomial = 2
# space-separated words" heuristic misclassifies "Human vocal", "Human whistle" and
# "Power tools" as species (they have exactly 2 tokens, same shape as "Turdus merula"),
# which let human speech reach the dashboard as an unlabelled "bird" — exactly the
# false-positive class this list exists to keep out. Verified against the model's own
# label file: these are the only 11 non-binomial entries in BirdNET_GLOBAL_6K_V2.4.
NON_TAXON_GROUP = 'non_taxon'
NON_TAXON_LABELS = {
    'Dog', 'Engine', 'Environmental', 'Fireworks', 'Gun',
    'Human non-vocal', 'Human vocal', 'Human whistle', 'Noise', 'Power tools', 'Siren',
}


def slug(sci):
    """Stable, filesystem-safe taxon key from a scientific name.

    This is a persisted identifier: it names photo-cache files and appears in API URLs
    (`/api/photo/turdus_merula`), so changing this function invalidates caches and
    breaks links. Change it only deliberately.
    """
    return re.sub(r'_+', '_', re.sub(r'[^a-z0-9]', '_', sci.lower())).strip('_')


def load_labels():
    """(index -> (scientific, common)) straight from the model's own label file."""
    with open(LABELS, 'rb') as f:
        raw = f.read()
    text = raw.decode('utf-8')

    trailing_newline = text.endswith('\n')
    entries = text.split('\n')
    if trailing_newline:                     # tolerate a future re-export that adds one
        entries = entries[:-1]
    entries = [e.rstrip('\r') for e in entries]

    if any(not e.strip() for e in entries):
        raise SystemExit('FATAL: blank line in the label file — indices cannot be trusted')

    # THE assertion. Everything downstream is an index into the model's output vector.
    if len(entries) != N_CLASSES:
        raise SystemExit(
            f'FATAL: {LABELS}\n'
            f'  parsed {len(entries)} labels, expected exactly {N_CLASSES}.\n'
            f'  The model emits [1, {N_CLASSES}] logits and index i MUST be line i.\n'
            f'  A mismatch of even one line renames every class after it — this project\n'
            f'  has already retracted findings to that exact bug (DESIGN §10f).\n'
            f'  Refusing to write an asset that would be silently, confidently wrong.')

    bad = [i for i, e in enumerate(entries) if '_' not in e]
    if bad:
        raise SystemExit(f'FATAL: {len(bad)} label(s) without "_" separator, first at {bad[0]}')

    out = []
    for e in entries:
        sci, _, com = e.partition('_')
        out.append((sci.strip(), com.strip()))
    print(f'labels: {len(out)} entries from {os.path.basename(LABELS)} '
          f'(trailing newline: {trailing_newline})')
    return out


def load_regional():
    """Danish scientific names, with the AviList abbreviated-subspecies expansion.

    Reuses `species_lists/build_lists.py`'s approach verbatim: a three-token subspecies
    `Genus species subspecies` is ALSO tested as `Genus subspecies`, because AviList
    ranks as subspecies several taxa other checklists (and BirdNET) rank as species.
    """
    if not os.path.exists(DOF):
        print(f'  ! no DOF checklist at {DOF} — regional will be false everywhere')
        return set(), set(), {}
    d = json.load(open(DOF, encoding='utf-8'))
    binomials = set(d['binomials'])
    split = {f'{t.split()[0]} {t.split()[2]}' for t in d.get('subspecies', [])
             if len(t.split()) == 3}
    split -= binomials
    print(f'DOF checklist: {len(binomials)} binomials + {len(split)} subspecies '
          f'expansions ({d.get("source", "?")[:60]}…)')
    return binomials, split, d


def load_months():
    """scientific name -> (12 fractions Jan..Dec, total Danish record count).

    Raw counts, not the app's thresholded 12-bit mask. Species with no Danish records
    are absent, and absent means unknown — never means absent from Denmark. `total` is
    carried alongside the fractions so a low-sample species (few Danish records at all)
    can be told apart from one with a genuinely sharp seasonal signal — a species with
    20 total records showing "0% in July" is an artifact of sample size, not evidence
    of implausibility, and the station's read-time filter needs both numbers to tell
    the difference (a fraction alone can't).

    Normalized over records that HAVE a month, not over `total`. GBIF's month facet can
    omit records with no month field — for some species (e.g. Otis tarda) that's a third
    of all Danish records — and dividing by `total` anyway would leave the 12 fractions
    summing to well under 1, silently understating every month. Dividing by the
    month-known subset keeps "12 fractions of a species' Danish records" true by
    construction; the excluded share is reported, not hidden.
    """
    if not os.path.exists(GBIF_CACHE):
        print(f'  ! no GBIF cache at {GBIF_CACHE} — months will be null everywhere')
        return {}
    cache = json.load(open(GBIF_CACHE, encoding='utf-8'))
    out = {}
    for sci, e in cache.items():
        total = e.get('total') or 0
        if total <= 0:
            continue
        counts = {int(k): v for k, v in (e.get('counts') or {}).items()}
        counted = sum(counts.values())
        if counted <= 0:
            continue
        fr = [round(counts.get(m, 0) / counted, 5) for m in range(1, 13)]
        missing_pct = 100 * (total - counted) / total
        if missing_pct >= 1.0:          # a handful of stragglers on every species is
            print(f'  ! {sci}: {total - counted}/{total} records ({missing_pct:.0f}%) '  # normal; only flag when it's a real share
                  f'have no month field; normalized over the {counted} that do')
        out[sci] = (fr, total)
    print(f'GBIF month data: {len(out)} species with Danish records '
          f'(of {len(cache)} cached)')
    return out


def build():
    labels = load_labels()
    binomials, split, dof = load_regional()
    danish = binomials | split
    months_by_sci = load_months()

    species, keys = [], {}
    n_regional = n_split_hit = n_months = n_non_taxon = 0
    for i, (sci, com) in enumerate(labels):
        is_bird = sci not in NON_TAXON_LABELS
        if not is_bird:
            n_non_taxon += 1
        regional = sci in danish
        if regional:
            n_regional += 1
            if sci in split and sci not in binomials:
                n_split_hit += 1
        months_entry = months_by_sci.get(sci)
        months, months_total = months_entry if months_entry else (None, None)
        if months:
            n_months += 1
        k = slug(sci)
        keys.setdefault(k, []).append(i)
        species.append({'i': i, 'sci': sci, 'com': com, 'key': k,
                        'group': 'bird' if is_bird else NON_TAXON_GROUP,
                        'regional': regional, 'months': months, 'months_total': months_total})

    collisions = {k: v for k, v in keys.items() if len(v) > 1}

    doc = {
        'generated': time.strftime('%Y-%m-%dT%H:%M:%S%z'),
        'model': MODEL,
        'n_classes': len(species),
        'labels_source': os.path.relpath(LABELS, ROOT).replace('\\', '/'),
        'regional_source': (dof.get('source', '') if dof else None),
        'months_source': ('GBIF occurrence records, country=DK, faceted by month; '
                          'fraction of that species\' annual Danish records per month'),
        'advisory_note': ('regional and months are ADVISORY, applied at READ time only. '
                          'They must never drop a class or filter model output at '
                          'inference. Never drop a class, down-weight it.'),
        'species': species,
    }

    os.makedirs(os.path.dirname(DEST), exist_ok=True)
    with open(DEST, 'w', encoding='utf-8') as f:
        json.dump(doc, f, ensure_ascii=False, separators=(',', ':'))

    print(f'\nwrote {DEST}  ({os.path.getsize(DEST) / 1024:.0f} kB)')
    print(f'  {len(species)} classes '
          f'({len(species) - n_non_taxon} binomial taxa, {n_non_taxon} non-taxon)')
    print(f'  {n_regional} on the Danish list '
          f'({n_regional - n_split_hit} direct + {n_split_hit} subspecies expansions)')
    print(f'  {n_months} with GBIF month data')
    print(f'  {len(collisions)} key collisions')

    # Gaps stay visible rather than silent — the same reporting build_lists.py does.
    missing = sorted(s for s in binomials
                     if s not in {sp['sci'] for sp in species}
                     and f'?' not in s)
    plausible = [s for s in missing if len(s.split()) == 2 and s.split()[1].islower()]
    print(f'  {len(missing)} DOF binomials have NO BirdNET class '
          f'({len(plausible)} look like real species — reported, not hidden)')
    return doc, collisions, plausible


def verify(doc, collisions):
    """Re-read what was written and re-check the index map end to end."""
    print('\n--- verification (re-read from disk) ---')
    d = json.load(open(DEST, encoding='utf-8'))
    sp = d['species']
    ok = True

    def check(name, cond, detail=''):
        nonlocal ok
        ok = ok and bool(cond)
        print(f'  [{"PASS" if cond else "FAIL"}] {name}{" — " + detail if detail else ""}')

    check(f'{N_CLASSES} entries', len(sp) == N_CLASSES, f'got {len(sp)}')
    check('n_classes field agrees', d['n_classes'] == N_CLASSES)
    check('i == position for every entry', all(e['i'] == n for n, e in enumerate(sp)))
    check('every sci non-empty', all(e['sci'] for e in sp))
    check('every key non-empty and filesystem-safe',
          all(e['key'] and re.fullmatch(r'[a-z0-9_]+', e['key']) for e in sp))

    n_keys = len({e['key'] for e in sp})
    check('keys unique', not collisions,
          f'{n_keys} distinct keys / {len(sp)} entries'
          + ('' if not collisions else '; ' + '; '.join(
              f'{k} <- ' + ' + '.join(sp[i]['sci'] for i in v)
              for k, v in list(collisions.items())[:10])))

    # Round-trip against the source of truth: the asset must reproduce the label file
    # line for line, at the same index. This is the shift check with no wiggle room.
    raw = open(LABELS, encoding='utf-8').read().split('\n')
    check('every entry round-trips to its source line',
          all(f"{e['sci']}_{e['com']}" == raw[n].rstrip('\r') for n, e in enumerate(sp)))

    # Known anchors, hard-coded: a one-line shift moves these and the check fails.
    for idx, sci, com in ((6253, 'Turdus merula', 'Eurasian Blackbird'),
                          (4237, 'Parus major', 'Great Tit'),
                          (1578, 'Corvus cornix', 'Hooded Crow'),
                          (0, 'Abroscopus albogularis', 'Rufous-faced Warbler'),
                          (N_CLASSES - 1, 'Zosterops virens', 'Cape White-eye')):
        e = sp[idx]
        check(f'index {idx} is {sci}', e['sci'] == sci and e['com'] == com,
              f"got {e['sci']}_{e['com']}")

    check('Turdus merula key is turdus_merula',
          sp[6253]['key'] == 'turdus_merula', sp[6253]['key'])
    check('Hooded Crow is regional (AviList subspecies expansion worked)',
          sp[1578]['regional'] is True)
    check('a non-Danish species is not regional',
          sp[0]['regional'] is False, 'Abroscopus albogularis')
    check('months are 12 floats summing to ~1 where present',
          all(len(e['months']) == 12 and abs(sum(e['months']) - 1) < 0.02
              for e in sp if e['months']))
    check('months null where unknown', any(e['months'] is None for e in sp))
    check(f'exactly {len(NON_TAXON_LABELS)} non-taxon classes, matched by exact label',
          sum(1 for e in sp if e['group'] == NON_TAXON_GROUP) == len(NON_TAXON_LABELS))
    check('"Human vocal" is non_taxon, not misfiled as a bird (the 2-word-heuristic bug)',
          next(e for e in sp if e['sci'] == 'Human vocal')['group'] == NON_TAXON_GROUP)
    check('"Power tools" is non_taxon, not misfiled as a bird (the 2-word-heuristic bug)',
          next(e for e in sp if e['sci'] == 'Power tools')['group'] == NON_TAXON_GROUP)
    return ok


def main():
    doc, collisions, missing = build()
    if missing:
        print('\nDOF species with no BirdNET class (first 20):')
        for s in missing[:20]:
            print('   -', s)
    if not verify(doc, collisions):
        print('\nVERIFICATION FAILED — do not ship this asset.')
        return 1
    print('\nall assertions passed.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
