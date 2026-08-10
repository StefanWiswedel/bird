---
name: biomon-ui
description: The Biomon design system. Use this skill whenever building, styling or modifying any Biomon frontend — the bird station dashboard, the life list, the verification interface, the spectrogram, or any future Biomon web UI. Defines the aesthetic direction, colour tokens, typography, spacing, motion rules and component patterns. Read this before writing any HTML, CSS or JS that a human will look at.
---

# Biomon design system

This is a binding specification, not inspiration. Do not invent alternative
palettes, fonts or spacing scales. If something is genuinely not covered here,
extend the system in the same spirit and add it to this file.

## 1. The concept: nocturnal field ledger

Biomon is a listening station on a Copenhagen balcony. It runs all night. It is
a patient instrument, and the thing it produces is a collection — a record of
what was actually there.

The aesthetic direction is **a field notebook read by lamplight, crossed with a
specimen label**. Warm dark ground, typographically serious, data rendered with
the precision of a museum drawer tag. It is *not* a SaaS dashboard, not a
consumer fitness app, and not a "smart home" panel.

Three consequences that should drive every decision:

- **Restraint is the default.** The interface is mostly still and mostly quiet.
  The spectrogram is the only thing that moves continuously. This makes the rare
  moments of motion and colour actually land.
- **Species names are the hero content.** They get the best typography in the
  system. Binomials are always italic, always correct.
- **Scarcity of colour mirrors scarcity of species.** The rare-signal colour
  appears only for lifers and rarities. If it starts showing up on ordinary UI,
  the system is broken.

The one thing a person should remember: **the lifer card** — the moment a new
species is confirmed, rendered as a specimen label with its own spectrogram as
artwork.

## 2. Colour

All colours are CSS custom properties on `:root`. Never hardcode a hex value in
a component.

```css
:root {
  /* Ground — warm near-black, never pure #000, never blue-grey slate */
  --bg:            #100D0B;
  --bg-raised:     #1A1512;
  --bg-sunken:     #0A0807;

  /* Lines and edges — warm, low contrast, barely there */
  --line:          #2B2320;
  --line-strong:   #3D332D;

  /* Text */
  --ink:           #F4EDE2;   /* primary, warm cream */
  --ink-soft:      #A99B8B;   /* secondary */
  --ink-faint:     #6B5F55;   /* tertiary, timestamps, units */

  /* Living — status, confirmed, "listening". Sap green. */
  --alive:         #9CC471;
  --alive-dim:     #4E6339;
  --alive-wash:    rgba(156, 196, 113, 0.12);

  /* Activity — detections, energy, spectrogram. Ember amber. */
  --ember:         #E8A33D;
  --ember-dim:     #6B4A1C;
  --ember-wash:    rgba(232, 163, 61, 0.12);

  /* Rare signal — RESERVED. See rule below. */
  --signal:        #FF6B4A;
  --signal-wash:   rgba(255, 107, 74, 0.14);

  /* Rejected / negative — never harsh red */
  --void:          #7A5C57;
}
```

### The `--signal` rule

`--signal` is used for exactly three things and nothing else:

1. A **lifer** — a species newly confirmed to the life list.
2. A **rarity flag** — a detection whose species has a low local prior.
3. The **confirm** action in the verification interface, because that action is
   what creates lifers.

It must never appear on a button, a link, a badge, an error state, a chart axis
or a heading. Its value is that a person can scan the screen and know instantly
that something unusual happened. Spending it elsewhere destroys the system.

### Status colour mapping

| State | Treatment |
|---|---|
| Candidate (detected, unverified) | No colour. `--ink-soft` text only. Absence of colour *is* the signal that it isn't real yet. |
| Confirmed | `--alive` |
| Rejected | `--void`, row at 45% opacity |
| Lifer | `--signal` |
| Rarity flag | `--signal` outline, never fill |

## 3. Typography

Three families, each with one job. Load from Google Fonts, `display=swap`.

```css
--font-display: 'Fraunces', Georgia, serif;      /* species names, headings */
--font-ui:      'Instrument Sans', sans-serif;   /* body, labels, buttons */
--font-data:    'Martian Mono', monospace;       /* numbers, times, counts */
```

Never use Inter, Roboto, Arial, Helvetica, system-ui or Space Grotesk anywhere.

### Rules

- **Common names**: `--font-display`, weight 600, optical size axis `opsz` set
  high for large sizes. Fraunces variable axes: use `SOFT 0, WONK 1` for
  headings — the wonk gives it the slightly organic, engraved character that
  suits a natural-history project.
- **Binomials**: `--font-display`, italic, `--ink-soft`, one step smaller than
  the common name. Always italic — this is a scientific convention and getting
  it right is part of the credibility of the whole thing.
- **All numerals**: `--font-data` with `font-variant-numeric: tabular-nums`.
  Timestamps, counts, durations, dB values, detection totals. Tabular so
  columns of numbers align and don't jitter as they update.
- **Labels and micro-copy**: `--font-ui`, uppercase, `letter-spacing: 0.08em`,
  `--ink-faint`, 11px. Used sparingly for section eyebrows like `LATEST` or
  `LIFE LIST`.
- **Body**: `--font-ui`, 15px, `line-height: 1.55`.

### Scale

`11 / 13 / 15 / 18 / 22 / 28 / 40 / 56`. Nothing between. 56 is reserved for the
lifer card and the species-count headline.

## 4. Space, shape, depth

- **Spacing scale**: `4 / 8 / 12 / 16 / 24 / 32 / 48 / 64`. Nothing between.
- **Radius**: `--r-sm: 6px`, `--r-md: 12px`, `--r-lg: 18px`. The spectrogram and
  full-bleed media are square — radius 0.
- **Elevation is done with colour and hairlines, not shadow.** A raised surface
  is `--bg-raised` with a `1px solid var(--line)` border. Drop shadows on a dark
  warm ground look muddy. The only exception is the lifer card, which may use a
  soft `--signal` glow.
- **Grain.** Apply a very low-opacity noise texture over the page background
  (SVG `feTurbulence`, opacity 0.025, `pointer-events: none`, fixed position).
  Dark flat fills look plasticky on OLED; the grain reads as paper. This is a
  signature detail — keep it.
- **Touch targets**: minimum 44px. This is used one-handed, outdoors, sometimes
  in the dark, sometimes with a child in the other arm.

## 5. Motion

Restrained by default. Motion budget is spent on two things.

- **The spectrogram scroll** — continuous, 30fps, never stutters. This is the
  app's heartbeat and the proof it's alive.
- **The lifer reveal** — the one orchestrated moment. Staggered entrance:
  spectrogram artwork fades in (400ms), then the common name rises 12px and
  fades (300ms, 120ms delay), then the binomial (300ms, 200ms delay), then the
  specimen-label metadata block (300ms, 280ms delay). Ease `cubic-bezier(0.16,
  1, 0.3, 1)`.

Everything else: new detection rows enter with a 180ms fade + 6px slide from
the top. Tab changes are instant. No skeleton shimmer, no spinners longer than
400ms, no bouncing, no parallax, no page transitions.

Respect `prefers-reduced-motion`: keep the spectrogram (it's information, not
decoration), drop the lifer stagger to a simple fade.

## 6. Components

### Detection row
Height 72px. Left: time in `--font-data`, `--ink-faint`, 13px. Then a 44px
circular species thumbnail with a `--line` ring. Then common name
(`--font-display`, 18px) with binomial beneath (italic, 13px, `--ink-soft`).
Right: confidence tier glyph, then a chevron.

**Never show a raw confidence percentage in the feed.** Use three tiers rendered
as a small three-segment bar: strong / probable / weak. The exact score belongs
in the detail view only, where it can be given context.

**Collapse bouts.** Repeated detections of the same species within a rolling
window are one row with a `×N` count in `--font-data`. Two identical adjacent
rows is a bug.

### Stat tiles
Four tiles, 2×2 on mobile. Number in `--font-data` at 28px `--ink`, label in
uppercase micro-copy beneath. Headline stats are: **species today**, **new
lifers this week**, **most active hour**, **detections today**. Uptime and
device health are diagnostics — they live in Settings, not on the front page.

### Life list entry
Grid, two columns on mobile. Each: species thumbnail, common name, binomial,
and a `--font-data` first-confirmed date rendered like a specimen label
(`2026-08-02 · 21:13`). Confirmed entries in full colour; candidates rendered at
50% opacity with a dashed `--line` border, so an incomplete list visibly *wants*
to be completed.

### Lifer card
Full-bleed. The spectrogram of the exact detection that earned it, rendered
large as the artwork, in the spectrogram palette. Common name at 56px. Binomial
italic beneath. Then a specimen-label metadata block in `--font-data`:
station, coordinates, date, time, score. A hairline `--signal` rule above and
below the label block. This is the emotional peak of the product — give it
room, and never show more than one at a time.

### Verification interface
**Triage, not a card-stack.** The card-stack described here through PR 2 — one
detection at a time, drawn from a single undifferentiated queue — was removed:
an unbounded queue of individually identical decisions is what got abandoned at
item 45. Its CSS was deleted in the 2026-08-10 sweep. Do not rebuild it.

The unit of judgement is the **bout**, not the detection, and the entry point is
a **species list ordered by what is at stake** — possible new species first,
then ones unlikely to be here, then borderline scores. Picking a species is
itself the commitment: the count of bouts is shown before entering, so the size
of the job is known in advance rather than discovered halfway through.

Within a species, one bout at a time with a **two-part verdict**, asked as two
plain questions rather than one compound one: *is this really a bird* (or
insect, or whatever group the taxon belongs to — the copy is group-aware, and
asking "is this a bird?" of a bush-cricket is a bug), then *is it this species*.
"Can't tell" is a first-class answer, not a skip. Buttons are stacked full
width, not a row of equal tiles — the answers are not equivalent and a row
invites tapping the middle one.

Always show the stake with the decision: *"would be new on the life list."*
Verification is a chore unless the reward is visible at the moment of deciding.
Bulk accept unlocks only once the species is on the life list and a couple of
its bouts have been confirmed individually.

Reference audio sits beside the clip where it exists (§11q in DESIGN.md) so the
question is "does this sound like that", which is answerable, rather than "name
this bird", which is not. Its four states — no key configured, ready, none
available, lookup failed — are four different sentences, never one shrug.

### Spectrogram
Square corners, full container width, `--bg-sunken` behind. Palette ramp:

```
0.00  #100D0B   (noise floor — must vanish into the page)
0.25  #3B2416
0.50  #8A4A1E
0.75  #E8A33D
1.00  #FFF3D6
```

A single warm hue family, matching the app. Do not use inferno, viridis, jet or
any rainbow map — they read as borrowed scientific output rather than a
designed instrument. Frequency axis labels in `--font-data`, 11px,
`--ink-faint`, at 1k / 2k / 4k / 8k only.

## 7. Checklist before shipping any screen

- [ ] No hardcoded hex values; all colour via tokens
- [ ] `--signal` used only for lifers, rarity flags, or the confirm action
- [ ] All binomials italic
- [ ] All numerals in `--font-data` with tabular-nums
- [ ] No raw confidence percentages in list views
- [ ] Grain overlay present
- [ ] Touch targets ≥ 44px
- [ ] Legible outdoors: test at minimum brightness and at maximum
- [ ] `prefers-reduced-motion` handled
