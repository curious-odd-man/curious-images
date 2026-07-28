# Engagement Features — Implementation Plans

Architecture-level plans for the 24 features listed in `engagement-features.md`, grounded in the
actual codebase in `main.zip` (JavaFX + Spring + Postgres/jOOQ + Flyway, existing CLIP embeddings,
ArcFace/RetinaFace face pipeline, Lucene vector index, and a background `JobManager`).

**Read `00-shared-infrastructure.md` first.** It defines five pieces of shared plumbing
(a Discover home screen, a generic `insight_card` model + generation job, an Ollama integration
layer, an extracted geo-clustering service, and reuse rules for the existing CLIP/dedupe infra)
that most of the 24 feature docs build on instead of repeating. Each feature doc's "Depends on
shared infra" section tells you exactly which of those five (§A–§E) it needs.

## Suggested build order

Some features are pure infrastructure that unlocks several others — building these first avoids
rework:

1. `00-shared-infrastructure.md` — read fully before anything else.
2. `organization/13-low-quality-finder.md` — its quality score feeds features 03, 04, 11.
3. `ai-features/16-describe-this-photo.md` — builds the shared Ollama client/config used by
   features 04, 14, 15, 17, 18, 23.
4. `discovery/06-places-youve-returned-to.md` — builds `GeoClusteringService`, used by features
   01, 04, 07, 08, 15, 22, and the geocoding decision it raises affects several others.
5. `ai-features/14-auto-album-titles.md` — builds `AlbumNamingService`, used by feature 15.
6. `s-tier/02-today-i-found.md` and `discovery/08-hidden-patterns.md` — establish the
   `insight_card` table/`InsightGenerator` pattern (§B) most remaining features plug into.
7. Everything else, roughly in the order listed below — most are now a single new
   `InsightGenerator`/service class on top of what's already built.

## All features

**S-tier**
- `s-tier/01-on-this-day.md`
- `s-tier/02-today-i-found.md`
- `s-tier/03-rediscover-forgotten-photos.md`
- `s-tier/04-monthly-story.md`
- `s-tier/05-person-timeline.md`

**Discovery**
- `discovery/06-places-youve-returned-to.md`
- `discovery/07-then-vs-now.md`
- `discovery/08-hidden-patterns.md`

**Organization helpers**
- `organization/09-inbox.md`
- `organization/10-ai-album-suggestions.md`
- `organization/11-similar-cleanup.md`
- `organization/12-screenshot-cleanup.md`
- `organization/13-low-quality-finder.md`

**AI features**
- `ai-features/14-auto-album-titles.md`
- `ai-features/15-event-detection.md`
- `ai-features/16-describe-this-photo.md`
- `ai-features/17-semantic-search-history.md`
- `ai-features/18-ask-your-archive.md`

**Fun features**
- `fun/19-random-memory.md`
- `fun/20-mystery-photo.md`
- `fun/21-this-never-happened-again.md`

**Statistics**
- `statistics/22-statistics-dashboard.md`

**AI-generated stories**
- `stories/23-ai-generated-stories.md`

**Bonus (proposed flagship feature)**
- `bonus/24-archive-explorer.md`

## What's already "for free" in the current codebase

Worth knowing before implementing anything: `AlbumGenerationJob` already does person, event
(time-gap), and location (GPS grid-cell) clustering into albums, and even has a
`buildSimilarityAlbums()` method already sketched but disabled — several features (10, 14, 15) are
extensions of code that already exists rather than net-new subsystems.

## Notes / things that weren't guessed

A few feature docs flag genuine open decisions rather than silently picking one (per your "don't
guess" instruction) — search each doc for these before implementing:
- **Reverse geocoding** (place names from GPS coordinates): needed for readable names in features
  06, 08, 14, 18, 22. No offline geocoding dataset exists in the repo today. Decide whether to
  bundle one, use coordinate-based names as a permanent fallback, or accept LLM-guessed names
  (lower confidence, flagged as such in each doc).
- **Ollama model choice**: default recommendation is `moondream` for bulk photo captioning and
  `llama3.2:3b`/`qwen2.5:7b-instruct` for narrative text (see §C), exposed as a Settings dropdown
  rather than hardcoded — confirm this matches what you already have pulled locally.
- **"Never exported" criterion** (Rediscover Forgotten Photos, feature 03): no export/share feature
  was found in the codebase — flagged to drop that criterion unless one exists elsewhere.
