# Places You've Returned To

## What it does
Cluster GPS coordinates across the whole library and surface places visited repeatedly, e.g. "You've
visited this beach 9 times."

## Depends on shared infra
- §D (`GeoClusteringService`) — this feature *is* the primary consumer that justifies extracting it
  from `AlbumGenerationJob`. Use a coarser cell size than the 1km used for LOCATION albums (e.g.
  ~5–10km, or better: cluster by *visit* — distinct date ranges at a place — rather than by raw
  photo count, so one 3-hour visit with 200 photos doesn't outrank nine separate day-trips)
- §B (emits `insight_card` type `RETURN_VISIT`)

## New data
None beyond `insight_card`. The interesting computation is turning "photos near lat/lon" into
"number of *distinct visits*" — group each place's photos by capture_date gaps (reuse the same
gap-clustering logic already in `AlbumGenerationJob.buildEventAlbums()`, generalized into
`GeoClusteringService` or a sibling `VisitClusteringService` that combines time-gap + spatial
clustering) so "9 times" means 9 separate trips, not 9 photos.

## Backend design
- `domain/geo/GeoClusteringService.clusterByProximity(...)` (§D) groups all GPS-tagged media into
  place cells.
- `domain/insights/ReturnVisitGenerator implements InsightGenerator`: for each place cell with
  media spanning ≥2 distinct visits (time-gap split, threshold configurable, default similar to
  `aiConfig.getEventGapHours()` but at a "day" granularity rather than hours), emit a card:
  "You've visited {place} {N} times" with representative cover photos per visit.
- Place naming: without reverse geocoding, name is just rounded lat/lon (as `LOCATION` albums do
  today). If a nicer name is wanted, this needs either (a) a local reverse-geocoding dataset, or
  (b) an Ollama text call fed the coordinates asking for a best-guess place name — flag as
  optional/lower-confidence, since an LLM can hallucinate place names from bare coordinates. Prefer
  (a) if network/offline constraints allow bundling a small offline geocoding DB; otherwise ship
  with coordinate-based names and revisit later.

## UI
- Discover section "Places You've Returned To": list of place cards, each opening a per-place view
  (grid of that place's photos, could reuse the existing `LOCATION` album detail view if one
  exists, otherwise a generic `GridController` filtered by media id list).
- Optional overview map (all repeat-visit places as pins) — same map component as On This Day / a
  Statistics travel heatmap; build one map widget, reuse everywhere.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/AlbumGenerationJob.java` (`buildLocationAlbums`, `buildEventAlbums`)
- `src/main/java/.../config/AiConfig.java` (existing gap/size thresholds to mirror)
- `src/main/java/.../persistence/MediaRepository.java` (`findAllWithGps`)
- `00-shared-infrastructure.md` (§D, §B)

**New:**
- `src/main/java/.../domain/geo/GeoClusteringService.java` (extracted, shared — see §D)
- `src/main/java/.../domain/geo/VisitClusteringService.java` (time+space combined clustering)
- `src/main/java/.../domain/insights/ReturnVisitGenerator.java`
- Map UI widget (name TBD, e.g. `ui/controller/custom/MapViewController.java` + `map_view.fxml`),
  shared across features 01/06/22
