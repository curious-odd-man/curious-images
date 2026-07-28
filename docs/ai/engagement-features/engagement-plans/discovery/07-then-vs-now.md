# Then vs Now

## What it does
Find photos taken from nearly identical viewpoints years apart — "you took almost this exact shot
9 years ago."

## Depends on shared infra
- §E (`ClipEmbeddingRepository`/`ClipVectorIndex`) for visual similarity
- §D (GPS proximity) as a cheap pre-filter
- §B (emits `insight_card` type `THEN_VS_NOW`)

## New data
None beyond `insight_card`.

## Backend design
This is the one feature genuinely worth a two-stage filter, since brute-force CLIP KNN across the
whole library for every photo is expensive:
1. **Candidate pairing**: for photos with GPS, use `GeoClusteringService`/a tight radius (e.g.
   ~50–100m) to find same-location photo pairs whose capture dates are ≥1 year apart. For photos
   without GPS, skip this feature (don't force a fallback path that would produce weak matches).
2. **Visual confirmation**: among same-place candidate pairs, compute CLIP cosine similarity
   (`EmbeddingMath.dot` on the existing L2-normalised embeddings) and keep only pairs above a high
   threshold (e.g. >0.85) — this is what turns "same beach, different day" into "nearly identical
   framing."
3. `domain/insights/ThenVsNowGenerator implements InsightGenerator`: runs step 1+2, emits one card
   per qualifying pair with both media ids and the year gap.
- Because this is O(pairs within each geo cell), it stays cheap even on large libraries as long as
  step 1 runs first.

## UI
- Discover card showing the two photos side by side (or a slider/fade compare widget) with the
  year gap as the headline ("9 years apart").

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../util/EmbeddingMath.java`
- `src/main/java/.../persistence/ClipEmbeddingRepository.java`
- `src/main/java/.../domain/index/ClipVectorIndex.java`
- `00-shared-infrastructure.md` (§D, §E, §B)
- `06-places-youve-returned-to.md` (shared `GeoClusteringService`)

**New:**
- `src/main/java/.../domain/insights/ThenVsNowGenerator.java`
- `src/main/resources/fxml/then_vs_now_card.fxml` + `ThenVsNowCardController.java` (side-by-side/
  compare-slider widget)
