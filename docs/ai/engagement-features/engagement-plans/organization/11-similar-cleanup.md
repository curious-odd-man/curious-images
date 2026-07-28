# Similar Cleanup (near-duplicates)

## What it does
Unlike exact-duplicate detection (already implemented), find *almost*-identical photos — e.g. 98
near-identical burst shots — and offer "keep best?".

## Depends on shared infra
- §E — reuses the existing dedupe subsystem almost entirely:
  `domain/dedupe/DuplicateDetectionJob`, `hasher/PixelHasher`/`PcaHasher`/`InvariantHasher`,
  `DuplicateGroupRepository`, `DuplicatesController`/`duplicates.fxml`,
  `DuplicateResolutionService`. This is explicitly **not** a new algorithm — it's the same
  perceptual-hashing pipeline run with a looser similarity threshold, plus a "pick the best one"
  ranking step the exact-duplicate flow doesn't need (exact duplicates are indistinguishable, so
  there's nothing to rank).

## New data
- Check current `duplicate_group`/`duplicate_group_member` schema (`V005__duplicate_schema.sql`)
  for whether it already stores a similarity score/threshold per group. If it only supports exact
  matches, add a `similarity_score DOUBLE` column and a `group_type` discriminator
  (`EXACT`/`NEAR`) so both flows share storage rather than duplicating the whole schema.

## Backend design
- Add a configurable near-duplicate threshold to whichever hasher currently drives
  `DuplicateDetectionJob` (`PixelHasher`/`PcaHasher` — check which is used for the "exact" pass and
  whether its distance metric already supports a tunable cutoff, or whether a Hamming-distance
  cutoff needs adding to `InvariantHasher`/`ReflectiveInvariantHasher`).
- `domain/dedupe/NearDuplicateRankingService`: within a near-duplicate group, pick the "best" photo
  using signals from **Low-Quality Finder** (feature 13)'s quality score (sharper/better exposed
  wins) — reuse that scorer rather than building a second one.
- Likely just an extra mode/parameter on the existing `DuplicateDetectionJob` rather than a
  parallel job class, if its structure allows a threshold parameter; only fork a new job class if
  the existing one's assumptions (exact-match early exits, etc.) make that impractical — inspect
  `DuplicateDetectionJob.java` directly before deciding.

## UI
- Reuse `DuplicatesController`/`duplicates.fxml`/`DuplicateCellController` with a "Near Duplicates"
  tab/filter alongside the existing exact-duplicates view, showing the ranked "recommended keep"
  photo highlighted per group.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/dedupe/DuplicateDetectionJob.java` (full file — critical to read before
  deciding fork-vs-extend)
- `src/main/java/.../domain/dedupe/hasher/*.java` (all four hasher classes)
- `src/main/java/.../domain/dedupe/DuplicateResolutionService.java`
- `src/main/java/.../persistence/DuplicateGroupRepository.java`
- `src/main/resources/db/migration/V005__duplicate_schema.sql`
- `src/main/java/.../ui/controller/screen/DuplicatesController.java`, `resources/fxml/duplicates.fxml`
- `13-low-quality-finder.md` (shared quality scorer)

**New:**
- Possible migration adding `similarity_score`/`group_type` to duplicate schema
- `src/main/java/.../domain/dedupe/NearDuplicateRankingService.java`
- Modifications to `DuplicateDetectionJob.java` (or a new sibling job) + `DuplicatesController.java`/
  `duplicates.fxml` (near-duplicates tab)
