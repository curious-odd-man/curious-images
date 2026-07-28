# Event Detection

## What it does
Cluster photos by time+location+people into named events like "John's Birthday", without manual
tagging.

## Depends on shared infra
- **Already substantially implemented**: `AlbumGenerationJob.buildEventAlbums()` already does
  time-gap clustering into `EVENT` albums. This feature is about (a) also clustering by
  location/people, not just time, and (b) naming events meaningfully (reuses feature 14's
  `AlbumNamingService` directly — "John's Birthday" requires the same person+context-aware naming
  built there, just with person names as a stronger signal in the prompt).

## New data
None beyond what feature 14 adds (`album.name_source`).

## Backend design
- Extend the event-clustering predicate in `buildEventAlbums()`: currently splits purely on a
  capture_date gap (`aiConfig.getEventGapHours()`). Add a secondary split when GPS distance between
  consecutive photos exceeds a threshold even within the same time gap (two visits same evening,
  different venues, shouldn't merge) — reuses `GeoClusteringService`(§D)'s distance calc.
- For naming: within each event cluster, find the dominant `person_id`s (via `FaceRepository`) and
  pass their names + date + rounded location into `AlbumNamingService` (feature 14) so it can
  produce "John's Birthday" style titles when a birthday-like pattern is detectable (e.g. same
  calendar date recurring yearly for the same dominant person — a nice-to-have refinement, not
  required for a correct v1 which can just say "Get-together with John, Mary — March 2024").
- No new job — this modifies the existing `buildEventAlbums()` method.

## UI
No new UI — same `EVENT` albums, just better boundaries and names, wherever albums are already
rendered.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/AlbumGenerationJob.java` (full file, esp. `buildEventAlbums`)
- `src/main/java/.../config/AiConfig.java`
- `src/main/java/.../persistence/FaceRepository.java`, `PersonRepository.java`
- `14-auto-album-titles.md` (shared naming service — implement that first)
- `06-places-youve-returned-to.md` (§D geo distance calc)

**New:**
- Modifications to `AlbumGenerationJob.buildEventAlbums()` (GPS-aware splitting, person-aware
  naming call)
