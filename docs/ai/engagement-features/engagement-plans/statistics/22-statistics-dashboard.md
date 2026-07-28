# Statistics Dashboard

## What it does
A dashboard of library-wide stats: photos/year, most-photographed people, countries/cities
visited, travel heatmap, day-of-week/hour-of-day distributions, camera usage, longest gap without
a photo.

## Depends on shared infra
- Shares `domain/statistics/LibraryStatisticsService` with **Hidden Patterns** (feature 08) —
  build that service once; this feature is "render every metric it exposes," feature 08 is "turn a
  few surprising ones into standalone cards." Implement whichever of the two is built first as the
  owner of the shared service.
- §D (`GeoClusteringService`) for the travel heatmap.
- Camera usage is a direct read of the already-extracted `camera_make`/`camera_model` fields on
  `Media` — no new extraction needed.

## New data
None. Everything here is an aggregation over existing `media`, `face`/`person`, and GPS columns.
No new tables — if any metric proves expensive to compute live, add a simple cache
(`statistics_cache` key/value or materialized view) rather than bespoke tables per metric.

## Backend design
- `domain/statistics/LibraryStatisticsService` methods (all plain jOOQ aggregations):
  - `photosPerYear()`, `countByDayOfWeek()`, `countByHourOfDay()`, `busiestMonth()`
  - `topPeopleByPhotoCount(n)` (via `FaceRepository`/`PersonRepository`)
  - `topCountries(n)`/`topCities(n)` — again depends on the reverse-geocoding decision from feature
    06; without it, report by rounded GPS cluster instead of named places, and upgrade later
  - `travelHeatmapPoints()` — all GPS points, or `GeoClusteringService`-clustered points if raw
    point counts get too dense to render usefully
  - `cameraUsageBreakdown()` — `GROUP BY camera_make, camera_model`
  - `longestGapWithoutPhoto()` — window function over ordered `capture_date` values
- No background job strictly required if queries are cheap enough to run on dashboard open; add a
  simple cache only if profiling shows it's needed.

## UI
- New `FxmlView.STATISTICS` → `statistics.fxml`/`StatisticsController`, reachable from the Discover
  screen (§A) or its own sidebar entry. Chart rendering: JavaFX's built-in `Chart` classes
  (`BarChart`, `PieChart`, `LineChart`) are sufficient and avoid adding a new charting dependency;
  reach for something heavier only if these prove visually inadequate.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../persistence/MediaRepository.java`
- `src/main/java/.../persistence/FaceRepository.java`, `PersonRepository.java`
- `00-shared-infrastructure.md` (§D)
- `08-hidden-patterns.md` (shared `LibraryStatisticsService` — coordinate implementation order)

**New:**
- `src/main/java/.../domain/statistics/LibraryStatisticsService.java` (shared with feature 08)
- `src/main/resources/fxml/statistics.fxml` + `ui/controller/screen/StatisticsController.java`
- `FxmlView.STATISTICS` registry entry
