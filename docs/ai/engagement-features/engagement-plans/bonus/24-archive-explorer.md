# Archive Explorer (bonus: semantic "Did You Know?" feed)

## What it does
The doc's proposed flagship feature: a launch-time card feed of *semantic*, not calendar-based,
discoveries mined from the whole archive — "you photographed this person every Christmas for 12
years," "these two photos were taken 11 years apart in almost the same place," "this object
disappeared from your photos after 2017," "you own 428 photos of bicycles." Effectively unbounded
in variety, generated offline from CLIP embeddings + faces + timestamps + GPS + EXIF.

## Depends on shared infra
This feature is best understood as **the umbrella that most other Discover-feed features already
are instances of** — On This Day, Then vs Now, This Never Happened Again, Hidden Patterns, Rediscover
Forgotten Photos, Places You've Returned To, and Today I Found all already emit `insight_card` rows
via the exact `InsightGenerator` pattern (§B) this feature calls for. Rather than building a second,
parallel "explorer" pipeline, **Archive Explorer is the generator registry itself**, plus a handful
of genuinely new pattern types not covered by any single feature above:
- "Photographed every year at the same time" (recurring annual pattern — a generalization of the
  Christmas example) — new `RecurringAnnualPatternGenerator`, built on the same
  time-gap/person-cooccurrence tools as features 06/15.
- "X photos of {object/category}" — CLIP zero-shot classification (reuse
  `ClipZeroShotClassifier` from features 08/12) against a bank of common object/category prompts
  ("bicycle," "dog," "beach," "cake," ...), counted per category.
- "This object disappeared from your photos after {year}" — track a category's CLIP-zero-shot
  presence count per year, flag categories with high early-year counts and zero counts in the last
  N years.
- "You unknowingly recreated this photo after N years" — same mechanism as Then Vs Now (feature
  07), just without requiring GPS proximity (pure CLIP similarity across the whole library, more
  expensive — reuse `RareSubjectDetectionJob`'s "run occasionally, not every launch" cadence).

## New data
None beyond `insight_card` (§B) — every new generator here is just another `InsightGenerator`
implementation writing into the same table.

## Backend design
- `domain/insights/InsightGenerationJob` (§B) becomes the single place all generators are
  registered (Spring `List<InsightGenerator>` injection) — On This Day, Today I Found's selector,
  Hidden Patterns, This Never Happened Again, Then Vs Now, Places You've Returned To, and this
  feature's new generators all plug into the same list.
- New generators specific to this feature:
  - `domain/insights/RecurringAnnualPatternGenerator`
  - `domain/insights/CategoryCountGenerator` (uses `ClipZeroShotClassifier` over a configurable
    prompt bank — store the bank as a simple config list, not hardcoded, so it's easy to extend)
  - `domain/insights/DisappearedObjectGenerator`
  - `domain/insights/RecreatedPhotoGenerator` (library-wide version of Then Vs Now)
- Ranking across this much larger and more heterogeneous candidate pool is where
  `InsightRankingService` (§B) earns its keep — cap how many cards of the same generator type can
  be shown per week so one prolific generator (e.g. `CategoryCountGenerator`, which can emit dozens
  of "N photos of X" cards) doesn't crowd out everything else.

## UI
- This *is* the main Discover feed (§A) — no separate screen. Today I Found (feature 02) is simply
  "show the single best card from this feed on launch."

## Files to hand to the implementing AI
**Existing (context):**
- All of `00-shared-infrastructure.md` (§A, §B, §C, §D, §E)
- `02-today-i-found.md`, `06-places-youve-returned-to.md`, `07-then-vs-now.md`,
  `08-hidden-patterns.md`, `12-screenshot-cleanup.md`, `21-this-never-happened-again.md` — read all
  of these first; this feature is largely wiring them together plus four new generators.

**New:**
- `src/main/java/.../domain/insights/RecurringAnnualPatternGenerator.java`
- `src/main/java/.../domain/insights/CategoryCountGenerator.java`
- `src/main/java/.../domain/insights/DisappearedObjectGenerator.java`
- `src/main/java/.../domain/insights/RecreatedPhotoGenerator.java`
- `src/main/java/.../domain/insights/InsightRankingService.java` (shared, if not already built
  while implementing feature 02 — finalize its per-type rate limiting here)
