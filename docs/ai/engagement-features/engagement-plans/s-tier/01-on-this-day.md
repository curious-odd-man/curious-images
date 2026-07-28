# On This Day

## What it does
On app launch (or from the Discover screen), show a slideshow of photos taken on this calendar
day in previous years, in chronological order, with a "10 years ago → today" framing. Falls back
to ±3 days if a given year has too few photos. Bonus: cluster in a child/pet's face across years,
and show a small map of where the user was that day.

## Depends on shared infra
- §A (Discover screen — this is its flagship section, and also the natural place for a "10 years
  ago today" banner shown at launch)
- §B (model each year-group as an `insight_card` of type `ON_THIS_DAY`, `expires_at` = end of day,
  regenerated daily)
- §D (map card reuses `GeoClusteringService`/GPS fields already on `Media`, no new geo work)
- Person clustering (`PersonRepository`/`PersonService`) for the "compare children/pets" variant

## New data
No new tables beyond `insight_card`/`insight_card_media` from §B. Add one query:
`MediaRepository.findByCaptureDateMonthDay(int month, int day, int dayTolerance)` (Postgres
`EXTRACT(MONTH FROM capture_date)`/`EXTRACT(DAY FROM capture_date)` predicate, grouped by year).

## Backend design
- `domain/insights/OnThisDayGenerator implements InsightGenerator`: runs daily (as part of
  `InsightGenerationJob`, §B), queries `findByCaptureDateMonthDay(today.month, today.day, 3)`,
  groups results by year, drops years with < N photos unless widening to ±3 days rescues them,
  and emits one `insight_card` per qualifying year plus one aggregate "compilation" card ordering
  all years oldest→newest for the slideshow.
- `domain/insights/OnThisDaySlideshowService`: builds the ordered `List<Media>` for playback,
  reusing the existing `SlideshowController`/`slideshow.fxml` (add a mode/constructor path that
  accepts a pre-built ordered list rather than only "photos in this folder").
- Face/person overlap across years: query `FaceRepository`/`PersonRepository` for the same
  `person_id` present in ≥2 of the selected years to power the "growing up" comparison card
  (payload: `{"person_id":..., "years":[2016,2019,2023], "media_ids":[...]}`).
- Map card: for the same day's photos with GPS, cluster via `GeoClusteringService` (§D) and render
  markers — no new geocoding service needed, just lat/lon points.

## UI
- New `on_this_day_card.fxml`/`OnThisDayCardController` rendered inside the Discover feed (§A).
- Extend `SlideshowController` to accept an externally supplied ordered media list.
- Small map node: reuse whatever mapping approach is chosen for "Places you've returned to"
  (feature 06) — do not build two different map renderers.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../persistence/MediaRepository.java`
- `src/main/java/.../domain/ai/AlbumGenerationJob.java` (date/gap clustering precedent)
- `src/main/java/.../ui/controller/screen/SlideshowController.java`, `resources/fxml/slideshow.fxml`
- `src/main/java/.../persistence/PersonRepository.java`, `domain/common/thumbnail/PersonService.java`
- `00-shared-infrastructure.md` (§A, §B, §D)

**New:**
- `src/main/resources/db/migration/V013__insight_cards.sql` (shared, created once — see §B)
- `src/main/java/.../domain/insights/InsightGenerator.java`, `InsightCandidate.java` (shared)
- `src/main/java/.../domain/insights/OnThisDayGenerator.java`
- `src/main/java/.../domain/insights/OnThisDaySlideshowService.java`
- `src/main/resources/fxml/on_this_day_card.fxml` + `OnThisDayCardController.java`
