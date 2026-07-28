# Hidden Patterns

## What it does
Small, surprising statistics surfaced as individual cards, e.g. "You take most photos on
Saturdays," "You photograph sunsets more than sunrises," "August is your busiest month," "Your
most photographed city is X."

## Depends on shared infra
- §B (each pattern becomes an `insight_card` of type `STAT_PATTERN`)
- Shares its underlying aggregate queries with the **Statistics Dashboard** (feature 22) — do not
  compute "photos by day-of-week" or "photos by month" twice; build one
  `domain/statistics/LibraryStatisticsService` that both features call (dashboard renders the full
  set of charts; this feature turns a few of the most *surprising* ones into standalone cards).
- "Sunsets vs sunrises," "most photographed city" need semantic/geo signals CLIP + GPS already
  provide (§E, §D); "most photographed city" needs the same reverse-geocoding decision flagged in
  feature 06 (or fall back to coordinate clusters named by rounded lat/lon if no geocoding DB is
  added).

## New data
None beyond `insight_card`. All source data is derivable from `media` (capture_date, GPS) and
`clip_embedding` (for content classifiers like "contains a sunset").

## Backend design
- `domain/statistics/LibraryStatisticsService` (shared with feature 22): methods like
  `countByDayOfWeek()`, `countByMonth()`, `busiestMonth()`, `topLocations(n)` — plain SQL
  aggregations via jOOQ.
- "Sunset vs sunrise" specifically needs a content classifier, not just EXIF time-of-day (a photo
  taken at 7am isn't necessarily a sunrise photo). Cheapest approach: use `ClipTextEncoder` to embed
  the phrases "a photo of a sunset" / "a photo of a sunrise" and do a nearest-neighbour comparison
  against each photo's CLIP embedding (the same technique the existing semantic search already
  uses for text→image matching) — no new model needed, just two fixed query embeddings compared at
  index time or on demand.
- `domain/insights/HiddenPatternGenerator implements InsightGenerator`: runs the above, and picks
  a handful of the most statistically "surprising" (highest skew from a uniform baseline) as cards,
  rather than emitting all of them as cards (all of them still show up in the Statistics
  Dashboard).

## UI
- Discover cards, one stat per card, simple text + a supporting thumbnail (e.g. a representative
  Saturday photo for the "you shoot most on Saturdays" card).

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/ClipTextEncoder.java`, `ClipImageEncoder.java`
- `src/main/java/.../util/EmbeddingMath.java`
- `src/main/java/.../persistence/MediaRepository.java`
- `00-shared-infrastructure.md` (§B, §D, §E)
- `22-statistics-dashboard.md` (shared `LibraryStatisticsService`)

**New:**
- `src/main/java/.../domain/statistics/LibraryStatisticsService.java` (shared with feature 22)
- `src/main/java/.../domain/insights/HiddenPatternGenerator.java`
