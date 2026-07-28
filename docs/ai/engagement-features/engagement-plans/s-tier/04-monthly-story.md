# Monthly Story

## What it does
An automatic "journal entry" per month (e.g. "April 2019") combining the 30 best photos, a
timeline, locations visited, people present, and detected events — essentially a richer, curated
alternative to a plain memory feed.

## Depends on shared infra
- §D (`GeoClusteringService`) for the "locations" section
- Existing `PersonRepository`/`PersonClusteringService` for "people"
- Existing `AlbumGenerationJob` EVENT logic for "events" (a month's events = its `EVENT` albums
  whose date range falls in that month — reuse `album`/`album_photo`, don't recompute)
- §C (Ollama text) *optionally*, for a one-paragraph narrative caption — but this feature is fully
  functional without an LLM (see `23-ai-generated-stories.md` for the narrative-only version); treat
  the LLM paragraph here as an enhancement, not a requirement, and reuse the exact same
  `OllamaTextService` call built for that feature rather than a second prompt/implementation.
- "30 best photos" needs the same quality/diversity signal as Rediscover Forgotten Photos —
  reuse the quality score (feature 13) + CLIP diversity sampling (feature 03), don't rebuild.

## New data
No new tables. This is a read/aggregation feature over existing `media`, `album`, `album_photo`,
`person`, `face` data, materialised as one `insight_card` (or a dedicated `monthly_story` view — a
plain query is simpler than a new table since it's cheap to recompute per month on demand) keyed by
year+month.

## Backend design
- `domain/insights/MonthlyStoryService`:
  - `bestPhotos(YearMonth)`: candidate = all media captured in that month, ranked by quality score,
    diversified via CLIP embeddings (same helper method extracted for feature 03 — put the greedy
    diversification routine in a shared `domain/ai/MediaDiversitySelector` utility used by both).
  - `timeline(YearMonth)`: just the ordered capture dates/counts per day (simple `GROUP BY` query).
  - `locations(YearMonth)`: `GeoClusteringService.clusterByProximity` over that month's GPS-tagged
    media.
  - `people(YearMonth)`: distinct `person_id`s appearing in that month's faces, ranked by photo
    count.
  - `events(YearMonth)`: existing `EVENT`-type albums overlapping the month.
  - `narrative(YearMonth)` (optional): one call to `OllamaTextService` with a prompt built from the
    above structured summary (not raw photo data) — e.g. "Summarize a month described by: N photos,
    M events named X/Y/Z, top locations A/B, top people P/Q" → short paragraph.
- Not necessarily driven by `InsightGenerationJob`'s daily cadence — this can be computed lazily
  when the user opens a given month in Discover, then cached (simple in-memory or a
  `monthly_story_cache` table keyed by year_month if recomputation cost matters).

## UI
- New Discover section: a scrollable list of months (most recent first), each opening a dedicated
  `monthly_story.fxml`/`MonthlyStoryController` detail view combining photo grid + timeline strip +
  map + people avatars + narrative text block.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/AlbumGenerationJob.java` (event albums)
- `src/main/java/.../persistence/AlbumRepository.java`, `AlbumPhotoRepository.java`
- `src/main/java/.../persistence/PersonRepository.java`, `FaceRepository.java`
- `src/main/java/.../persistence/MediaRepository.java`
- `00-shared-infrastructure.md` (§C, §D)
- `03-rediscover-forgotten-photos.md`, `13-low-quality-finder.md` (quality/diversity reuse)
- `23-ai-generated-stories.md` (shared Ollama narrative call)

**New:**
- `src/main/java/.../domain/insights/MonthlyStoryService.java`
- `src/main/java/.../domain/ai/MediaDiversitySelector.java` (shared with feature 03)
- `src/main/resources/fxml/monthly_story.fxml` + `MonthlyStoryController.java`
- Optional: `V015__monthly_story_cache.sql` if caching is chosen over on-demand computation
