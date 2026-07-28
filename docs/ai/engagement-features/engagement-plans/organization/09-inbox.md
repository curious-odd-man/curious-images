# Inbox

## What it does
A view of all photos not yet in any album — a simple organizational triage screen.

## Depends on shared infra
- Existing `album`/`album_photo` tables and `AlbumRepository`/`AlbumPhotoRepository` — this is a
  pure query/UI feature, no new backend subsystem.

## New data
None. Query: media not present in any `album_photo` row (`NOT EXISTS` / `LEFT JOIN ... IS NULL`).

## Backend design
- `AlbumPhotoRepository.findMediaWithoutAlbum()` (or `MediaRepository`, whichever already owns
  album-join queries — check existing query placement conventions before adding).
- No background job needed — this can be a live query each time the screen opens, or refreshed on
  `LibraryUpdatedEvent`/`AiPipelineCompleteEvent` if it's kept as a persistent sidebar count/badge.

## UI
- New `FxmlView.INBOX` → `inbox.fxml`/`InboxController`, reusing the existing `GridController`/
  `photo_grid.fxml` photo grid component (this is exactly the same rendering as the library grid,
  just with a different backing query) — do not build a second grid renderer.
- Add an "Inbox" entry to the sidebar/tree (`TreeManager`/`LibraryTreeNode`) alongside existing
  smart groupings, with an optional badge count.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../persistence/AlbumPhotoRepository.java`, `MediaRepository.java`
- `src/main/java/.../ui/controller/custom/GridController.java`, `resources/fxml/photo_grid.fxml`
- `src/main/java/.../ui/controller/services/TreeManager.java`, `ui/nodes/LibraryTreeNode.java`
- `src/main/java/.../ui/FxmlView.java`

**New:**
- Query addition to `AlbumPhotoRepository`/`MediaRepository` (`findMediaWithoutAlbum`)
- `src/main/resources/fxml/inbox.fxml` + `ui/controller/screen/InboxController.java`
- `FxmlView.INBOX` registry entry + sidebar tree node wiring
