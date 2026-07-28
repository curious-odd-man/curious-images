# This Never Happened Again

## What it does
Find photos of things unlike everything else in the library — rare one-off subjects (a camel, an
eclipse, a hot air balloon) — genuinely rare memories, surfaced automatically.

## Depends on shared infra
- §E (`ClipEmbeddingRepository`/`ClipVectorIndex`) entirely — this is an **outlier detection**
  problem over the existing CLIP embedding space, not a new content classifier.
- §B (emits `insight_card` type `RARE_SUBJECT`)

## New data
None beyond `insight_card`.

## Backend design
- `domain/insights/RareSubjectGenerator implements InsightGenerator`: for each media's CLIP
  embedding, compute local density — e.g. average cosine similarity to its k nearest neighbours in
  `ClipVectorIndex` (k≈20). Low average similarity ⇒ visually unlike anything else nearby in
  embedding space ⇒ candidate "never happened again" photo.
- This is a genuinely expensive-ish batch computation (a KNN query per photo across possibly tens
  of thousands of embeddings) — run it as a dedicated `BackgroundJob`
  (`RareSubjectDetectionJob`) on a slow cadence (e.g. weekly, or only re-scored for *newly
  imported* media plus a periodic full rescan), not inline in `InsightGenerationJob`'s regular run.
- Exclude near-duplicates/bursts from skewing results (a one-off burst of 40 photos of the same
  rare subject would otherwise reduce each other's "rarity" — dedupe within a candidate's own
  cluster first, or simply cap how many outlier candidates come from the same time/place cluster).

## UI
- Discover card: "This only happened once" style framing, one representative photo per rare
  subject found.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/index/ClipVectorIndex.java`
- `src/main/java/.../persistence/ClipEmbeddingRepository.java`
- `src/main/java/.../util/EmbeddingMath.java`
- `00-shared-infrastructure.md` (§E, §B)

**New:**
- `src/main/java/.../domain/insights/RareSubjectDetectionJob.java`
- `src/main/java/.../domain/insights/RareSubjectGenerator.java`
