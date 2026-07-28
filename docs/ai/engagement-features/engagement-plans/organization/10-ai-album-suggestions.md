# AI Album Suggestions

## What it does
"I found 84 photos that probably belong together" — proactively suggest new albums instead of
auto-creating them outright, so the user can accept/reject.

## Depends on shared infra
- Directly builds on the **existing but currently disabled** `AlbumGenerationJob.buildSimilarityAlbums()`
  (present in the code, commented out with a `// TODO: do I need that?`). This feature is
  essentially "turn that TODO into a real, user-facing suggestion flow" rather than silent
  auto-creation.
- §E (`ClipEmbeddingRepository`) — reuses the exact k-means/similarity clustering already sketched
  in `buildSimilarityAlbums()`.

## New data
- New table `album_suggestion` (id, name, cover_media_id, media_ids via
  `album_suggestion_media` join table, created_at, status ENUM('PENDING','ACCEPTED','REJECTED')).
  Kept separate from `album`/`insight_card` because suggestions have a distinct lifecycle
  (accept → becomes a real `album` row; reject → stays hidden, and should bias future suggestions
  away from that exact grouping).

## Backend design
- Extract `buildSimilarityAlbums()`'s clustering logic into
  `domain/ai/SimilarityClusteringService` (shared, testable in isolation from the job).
- `domain/ai/AlbumSuggestionJob extends BackgroundJob`: runs the clustering, filters out clusters
  that substantially overlap an already-accepted or already-rejected suggestion (by Jaccard
  similarity of media id sets), and writes new `album_suggestion` rows.
- `persistence/AlbumSuggestionRepository`: CRUD + `accept(id)` (materializes into `album`/
  `album_photo` via `AlbumRepository.insert` + `AlbumPhotoRepository`) + `reject(id)`.

## UI
- A "Suggestions" panel/screen (or a Discover section) listing pending suggestions as cards with
  Accept/Reject buttons, reusing the grid-cell thumbnail rendering (`GridCellController`) for the
  preview.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/AlbumGenerationJob.java` (full file — the `buildSimilarityAlbums`
  method and surrounding transaction/batching pattern to mirror)
- `src/main/java/.../persistence/AlbumRepository.java`, `AlbumPhotoRepository.java`
- `src/main/java/.../persistence/ClipEmbeddingRepository.java`
- `src/main/java/.../util/async/jobs/BackgroundJob.java`, `JobManager.java`

**New:**
- `src/main/resources/db/migration/V016__album_suggestions.sql`
- `src/main/java/.../domain/ai/SimilarityClusteringService.java`
- `src/main/java/.../domain/ai/AlbumSuggestionJob.java`
- `src/main/java/.../persistence/AlbumSuggestionRepository.java`
- `src/main/resources/fxml/album_suggestions.fxml` + `AlbumSuggestionsController.java`
