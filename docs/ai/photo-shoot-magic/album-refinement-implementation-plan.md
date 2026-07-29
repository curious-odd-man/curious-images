# Implementation Plan: User-Defined Albums with Refinement

Grounded in the current codebase (`curious_images`, JavaFX + Spring + jOOQ +
Postgres/Flyway). Decisions below reflect answers already given; anything
still open is called out explicitly at its point of use.

## Baseline decisions (confirmed)

- **New tables**, not an extension of `album`/`album_media`. The existing
  `album` table (types `PERSON | EVENT | LOCATION | SIMILARITY`) stays
  exactly as-is and continues to back read-only, AI-generated albums. A
  parallel `custom_album` / `custom_album_photo` / `scene_group` set of
  tables backs this feature. (The `MANUAL` value mentioned in the `album`
  table's comment is superseded by this and can be dropped from that
  comment in passing — it was never implemented.)
- Only **photos** (`media` rows with a `photo` subtype row) can be added to
  a custom album. Videos are filtered out at the "add to album" step and
  never enter `custom_album_photo`.
- Scene grouping / perceptual hashing is a **new subsystem**, independent of
  the existing exact-duplicate pipeline (`media_hash`, `DuplicateGroup*`,
  `domain/dedupe`) and independent of the never-implemented
  `AlbumGenerationJob.buildSimilarityAlbums()` (CLIP-based). No code is
  shared between them beyond generic utilities (thread pool helpers, etc.).
- Grouping/clustering runs as a **background job** (`BackgroundJob` /
  `JobManager`, same family as `DuplicateDetectionJob`,
  `AlbumGenerationJob`), never inline on the FX thread.
- "Add to album" is a new **context-menu entry** on `GridContextMenu`
  (both `show(...)` for a single photo and `showBulk(...)` for a
  multi-selection), not a File-menu item.
- Custom albums appear as a new **"Custom" root** under the existing
  "Albums" tree section, parallel to Event/Location/Similarity, built via
  the same `AlbumTypeGroup`-style pattern in `TreeManager`. Inline "+" (new
  album) and double-click-to-rename are added to this root **and
  retrofitted onto the existing Event/Location/Similarity groups** for
  consistency (rename only makes sense for groups where the name isn't
  derived data — see Phase 3 for how this is scoped per node type).
- The four uploaded FXML mockups (`unrefined_view`, `triage_grid`,
  `triage_grid_dragging`, `refined_view`) are adapted into real,
  controller-bound FXML — reusing existing pieces (`GridController` /
  `GridCellController` / `photo_cell.fxml` / `GridContextMenu` /
  `styles.css` conventions) wherever the mockup content matches what the
  grid already does, rather than reimplementing photo tiles from scratch.
- The custom-album view (refined/unrefined toggle + triage) is injected
  into `contentStack` the same way `personDetailContainer` is today: a
  `UiElement<CustomAlbumController>` managed by `LibraryViewManager`,
  loaded once via `FxmlLoader.loadFxmlAndAttachToParent`.

---

## Phase 0 — Schema

New Flyway migration `V013__custom_album_schema.sql`:

```sql
CREATE TABLE custom_album
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(256) NOT NULL,
    cover_photo_id BIGINT REFERENCES media (id),
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP
);

CREATE TABLE scene_group
(
    id              BIGSERIAL PRIMARY KEY,
    custom_album_id BIGINT NOT NULL REFERENCES custom_album (id) ON DELETE CASCADE
);
CREATE INDEX idx_scene_group_album ON scene_group (custom_album_id);

CREATE TABLE custom_album_photo
(
    custom_album_id BIGINT  NOT NULL REFERENCES custom_album (id) ON DELETE CASCADE,
    photo_id        BIGINT  NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    state           SMALLINT NOT NULL DEFAULT 0, -- -2 No, -1 RatherNo, 0 Unassigned, 1 RatherYes, 2 Yes
    scene_group_id  BIGINT  REFERENCES scene_group (id) ON DELETE SET NULL,
    added_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (custom_album_id, photo_id)
);
CREATE INDEX idx_custom_album_photo_album ON custom_album_photo (custom_album_id);
CREATE INDEX idx_custom_album_photo_scene_group ON custom_album_photo (scene_group_id);
CREATE INDEX idx_custom_album_photo_state ON custom_album_photo (custom_album_id, state);
```

Notes:
- `scene_group_id` nullable is **only** a transitional/implementation
  detail while a photo is queued for clustering; per §4.4 of the spec every
  photo ends up in a group (its own singleton if nothing matches), so after
  the grouping job runs there should be no nulls left for photos that have
  been processed. Keep it nullable regardless, to represent "not yet
  clustered."
- No `sort_order` column — the spec doesn't call for manual photo ordering
  within a custom album (unlike the AI-generated `album_media.sort_order`).
  Add later if needed.
- Regenerate jOOQ sources (`./gradlew generateJooq` or whatever task
  `build.gradle` wires up) so `Tables.CUSTOM_ALBUM`,
  `Tables.CUSTOM_ALBUM_PHOTO`, `Tables.SCENE_GROUP` and their `*Record`
  classes exist before Phase 1.

**Open question for you:** should perceptual-hash thresholds
(timestamp/geo proximity) be stored per-app (a new row in
`user_preferences` / `AiConfig`) or hardcoded constants with a later
settings-screen hook? Spec says "configurable... TBD during
implementation" — plan below assumes a config object read from
`AiConfig`/`AiSettingsService` (mirrors how AI thresholds already work
there), but confirm before Phase 2.

---

## Phase 1 — Domain model & repositories

Mirrors the existing `AlbumRepository` / `AlbumPhotoRepository` split.

1. `model/CustomAlbum.java` — thin wrapper if needed, or use
   `CustomAlbumRecord` directly (existing code sometimes uses raw jOOQ
   records, e.g. `AlbumRecord`, `PersonRecord` — follow that precedent).
2. `persistence/CustomAlbumRepository.java`
   - `insert(name, now)`, `rename(id, newName, now)`, `delete(id)`,
     `findAll()`, `findById(id)`, `updateCoverPhoto(id, photoId)`.
3. `persistence/CustomAlbumPhotoRepository.java`
   - `insertQuery(albumId, photoId, now)` (unexecuted `Query`, batched —
     same convention as `AlbumPhotoRepository.insertQuery`), default
     `state = 0`, `scene_group_id = null`.
   - `findByAlbumId(albumId)` → list of `(photoId, state, sceneGroupId)`.
   - `findUnassignedByAlbumGroupedByScene(albumId)` — for triage mode.
   - `updateState(albumId, photoId, newState)`.
   - `updateStateBulk(albumId, Set<photoId>, newState)` — for group bulk
     actions and the triage "mark remaining RatherNo" behavior.
   - `updateStateForGroupExcept(albumId, sceneGroupId, Set<keepPhotoIds>, newState)`
     — implements "mark all in group as No except selected."
   - `countYes(albumId)` — drives the refined-view lock check (§7).
   - `findFirstYesByAddedOrder(albumId)` — drives the cover-photo fallback
     (§6) when no cover has been explicitly set.
4. `persistence/SceneGroupRepository.java`
   - `insert(customAlbumId)` → id.
   - `assignPhotoToGroup(customAlbumId, photoId, sceneGroupId)` (updates
     `custom_album_photo.scene_group_id`).
   - `findGroupsWithUnassignedPhotos(customAlbumId)` — triage queue order.

Also add `NodePayload.CustomAlbumPayload(long customAlbumId)` alongside the
existing `AlbumPayload`, and a new `LibraryTreeNode.NodeType` pair:
`ALBUM_CUSTOM_ROOT`, `ALBUM_CUSTOM` (icon: e.g.
`MaterialDesignF.FOLDER_STAR` or similar — pick something visually
distinct from the AI album icons).

---

## Phase 2 — Scene grouping engine (new subsystem)

New package `domain/scenegroup/`:

1. `PerceptualHasher.java` — computes a perceptual hash (e.g. dHash/pHash)
   from a decoded thumbnail/preview image. Reuse the existing thumbnail
   pipeline (`domain/common/thumbnail`) for the source image rather than
   re-decoding full-resolution files — check
   `ThumbnailGenerationJob`/`PhotoPreview` for what's already generated and
   available per photo.
2. `HammingDistance.java` — small utility, compares two hash values.
3. `SceneCandidateMatcher.java` — implements §4.1: given a photo's
   timestamp + (optional) GPS, and the set of already-processed photos in
   the album, returns *candidate* photos within the configured
   timestamp/geo thresholds. Falls back to timestamp-only when either
   photo lacks geolocation, per spec.
4. `SceneGroupingService.java` — orchestrates §4.2/§4.3:
   - For each new (`scene_group_id IS NULL`) photo in a custom album, find
     candidates via `SceneCandidateMatcher`, confirm via perceptual-hash
     distance under threshold, and:
     - if a matching existing group is found, assign to it
       (`SceneGroupRepository.assignPhotoToGroup`);
     - else if a matching *ungrouped* candidate photo is found, create a
       new `scene_group` and assign both;
     - else create a new singleton `scene_group` for the photo alone.
   - **Never reassigns** a photo that already has a `scene_group_id`
     (assignment stability, §4.3) — the incremental job only ever looks at
     null-group photos as the "new" side of a comparison, never as
     candidates to move.
5. `SceneGroupingJob.java extends BackgroundJob` — same shape as
   `DuplicateDetectionJob`/`AlbumGenerationJob`: takes an album id (or a
   batch of newly-added photo ids), publishes progress via the existing
   `publishStarted/publishEnded/publishFailed` hooks so it shows up in the
   existing bottom progress bar (`backgroundProgressContainer` in
   `library.fxml`) automatically.
6. Wire triggering: whenever photos are added to a custom album (Phase 4),
   enqueue `SceneGroupingJob` via the existing `JobManager`/`JobFactory`
   instead of running clustering inline.
7. Publish a new `CustomAlbumUpdatedEvent` (mirrors
   `AiPipelineCompleteEvent`) when the job finishes, so the custom-album
   view (Phase 5) and the tree (photo counts, if you choose to show them)
   can refresh live if the album happens to be open while grouping runs.

Config: add threshold fields (timestamp window, geo radius, perceptual-hash
distance cutoff) to wherever Phase-0's open question is resolved
(`AiConfig` extension is the more consistent choice given precedent).

---

## Phase 3 — Tree UI: Custom root, "+", inline rename

1. **`TreeManager`**
   - Add a `CustomAlbumRepository`-backed `buildCustomAlbumItems()` next to
     `buildAlbumItems()`, returning `TreeItem<LibraryTreeNode>` with
     `NodeType.ALBUM_CUSTOM` / `NodePayload.CustomAlbumPayload`.
   - Add a `"Custom"` `TreeItem` (`NodeType.ALBUM_CUSTOM_ROOT`, `null`
     payload) as a sibling under the existing `albumsRoot`, alongside
     Event/Location/Similarity — i.e. extend `ALBUM_TYPE_GROUPS`-equivalent
     wiring in `onLibraryDataUpdated` to also attach the custom-album
     subtree under `albumsRoot`.
   - Extend `TreeViewUpdatePayload` (new file, same pattern as
     `PersonRename`/`PersonDelete`) with:
     `AlbumCreate(long albumId, String name)`,
     `AlbumRename(long albumId, String newName)`,
     `AlbumDelete(long albumId)`.
   - Extend `TreeManager.onTreeUpdateEvent` to also scan the
     `ALBUM_CUSTOM_ROOT` section (and, since rename is being retrofitted
     onto Event/Location/Similarity too, those groups' sections) for the
     matching node and apply create/rename/delete, exactly like
     `findPersonNode`.

2. **Inline "+" button on group roots.** There's no existing pattern for
   this, so: extend `LibraryTreeCell` to detect `NodeType` values that are
   "addable roots" (`ALBUM_CUSTOM_ROOT` for now — this is naturally *not*
   applicable to Event/Location/Similarity since those are AI-generated,
   not user-created, so "+" stays custom-only even though rename is
   shared) and render a small `Button` (`+`) as part of the cell's
   `graphic` (an `HBox` containing the existing `FontIcon` + label +
   trailing button, right-aligned). Button `onAction` opens a simple
   `TextInputDialog` ("New album name"), and on confirm calls
   `CustomAlbumRepository.insert(...)` then publishes
   `TreeViewUpdateEvent(AlbumCreate(...))`.

3. **Double-click rename**, generalized to Custom, Event, Location, and
   Similarity leaf nodes (per your answer). In `LibraryTreeCell`, override
   `startEdit()`/handle a mouse click-count check (JavaFX `TreeCell`
   supports `setEditable`/`startEdit`, but given the custom graphic
   approach above, simplest is: track `getClickCount() == 2` on the cell's
   `MouseEvent` and swap the `Label` for a `TextField` in place, matching
   the existing non-editable-by-default rest of the tree). On commit:
   - `ALBUM_CUSTOM` → `CustomAlbumRepository.rename(...)`.
   - `ALBUM_EVENT`/`ALBUM_LOCATION`/`ALBUM_SIMILARITY` → add a `rename`
     method to the existing `AlbumRepository` (new — it doesn't have one
     today) since spec's exclusion of undo/history doesn't preclude a
     plain rename of AI-album *labels* if you want that; **flag to
     reconsider**: renaming an AI-generated album's name may be
     surprising since it'll be silently overwritten on the next
     `AiPipelineCompleteEvent` regeneration — confirm whether that's
     acceptable or whether AI albums should be excluded from the
     rename-everywhere requirement after all.
   - Publish the corresponding `TreeViewUpdateEvent`.

4. Delete: spec doesn't ask for it and you didn't request it — left out of
   this plan; flag as a natural follow-up (right-click "Delete album" on
   `ALBUM_CUSTOM`, cascades via `ON DELETE CASCADE` already in the schema).

---

## Phase 4 — "Add photos to existing album" via context menu

1. **`GridContextMenu`**
   - Add `MenuItem addToAlbum` (with a submenu or a picker dialog — given
     an open-ended list of custom albums, a small popover/dialog listing
     existing custom albums plus a "New album..." entry is cleaner than a
     `Menu` with one item per album) to both `show(Media, ...)` and
     `showBulk(Set<Long>, ...)`.
   - New `ui/util/AlbumPickerDialog.java` (same family as
     `MetadataEditDialogs`): lists `CustomAlbumRepository.findAll()`
     names, radio/list selection + "New album..." text field, returns
     `Optional<Long>` (existing album id) or triggers create-then-return.
   - On confirm:
     - Single-photo path: if `media.isVideo()`, disable/hide the menu item
       entirely (or show it but no-op with a toast — **prefer disabling**,
       cleaner) since videos are never added.
     - Bulk path: filter `mediaIds` down to photo-only before insert
       (fetch via `MediaRepository`, keep only `Media.isPhoto()`); if the
       filtered set is empty, don't offer the dialog at all (grey out the
       menu item, similar to how `showBulk` already no-ops on
       `mediaIds.isEmpty()`).
   - New `domain/customalbum/CustomAlbumPhotoAdditionService.java`:
     `addPhotos(albumId, Set<photoId>)` → batches
     `CustomAlbumPhotoRepository.insertQuery(...)` (existing
     `QueryBuffer` utility, same as `DuplicateDetectionJob` uses), then
     enqueues `SceneGroupingJob` for the album via `JobManager`.
   - Wire the menu item's action to run this off the FX thread
     (`runOnDaemonThread`, consistent with every other context-menu action
     in this file).

2. Since "only already-imported photos" was confirmed, there's no new
   file-picker/import path to build here — this menu only ever operates on
   `Media`/`mediaIds` already present in the grid.

---

## Phase 5 — Custom album panel in `contentStack`

1. New FXML `custom_album.fxml`, adapted from the four mockups:
   - A top toggle bar (`Refined` / `Unrefined` — reuse `.view-toggle` /
     `.view-toggle:selected` styles from the uploaded `styles.css`) plus a
     "Triage" button.
   - Refined view: reuses the existing photo-grid components
     (`GridController`/`photo_grid.fxml` machinery) filtered to
     `state = Yes`, since it's visually just a normal grid.
   - Unrefined view: a scene-group-sectioned layout (per
     `unrefined_view.fxml` mockup) — grouped `TitledPane`/`VBox` sections
     per `scene_group`, each containing a small grid of `photo_cell.fxml`
     tiles with a state border color (`.state-no` etc. from `styles.css`)
     and a state badge, plus the two bulk-action buttons
     (`.bulk-action-button`) described in §7 of the spec.
2. New `ui/controller/screen/CustomAlbumController.java`, mirroring
   `PersonDetailController`'s shape: `loadAlbum(long albumId)`, holds
   references to `CustomAlbumPhotoRepository`, refreshes on
   `CustomAlbumUpdatedEvent`.
3. **`LibraryViewManager`**: add `customAlbum` field +
   `showCustomAlbum(long albumId, ...)` method, following the exact
   `showPersonDetail` pattern (lazy `loadFxmlAndAttachToParent`, `show(...)`
   toggling visibility across `uiElements`). Add
   `customAlbumContainer` `AnchorPane` to `library.fxml` next to
   `personDetailContainer`.
4. **`LibraryController.onTreeSelectionChanged`**: add a
   `case CustomAlbumPayload cap -> ...showCustomAlbum(cap.customAlbumId(), ...)`
   branch alongside the existing `AlbumPayload`/`PersonPayload` cases.
5. **View-mode lock (spec §7):**
   - On load and on every state-change event affecting this album, call
     `CustomAlbumPhotoRepository.countYes(albumId)`.
   - If `0`, force/keep Unrefined selected and disable the Refined toggle
     button; if the currently-active view is Refined when the count drops
     to 0 (last `Yes` changed away), flip to Unrefined immediately — do
     this reactively off the same repository call after any state update,
     not just on initial load.

---

## Phase 6 — State editing in unrefined view

1. Add a 5-state control per photo tile (buttons or slider — mockup
   (`unrefined_view.fxml`) should indicate which; if it shows discrete
   buttons, implement as 5 small `ToggleButton`s bound to
   `.state-badge-*` styles) plus keyboard shortcuts (e.g. `1`–`5` or arrow
   combinations — **confirm exact key bindings**, not specified in the
   spec or your answers).
2. Wire both paths to
   `CustomAlbumPhotoRepository.updateState(albumId, photoId, newState)`,
   then refresh that tile's border/badge in place (no full reload) and
   re-check the refined-view lock (Phase 5, item 5).
3. Bulk actions per scene-group section:
   - "Set group to state X" → `updateStateBulk(albumId, allPhotoIdsInGroup, X)`.
   - "Mark all No except selected" → `updateStateForGroupExcept(albumId, groupId, selectedIds, No)`.
   - Both are explicit buttons per §7 — no auto-trigger, matching spec.

---

## Phase 7 — Triage mode

1. New FXML `triage_grid.fxml`/drag-state variant adapted from the two
   uploaded mockups, loaded as a modal `Stage` (consistent with how
   `onRescanMenuClicked` opens `RE_SCAN_MODAL` — triage is a focused,
   full-attention flow, so a modal stage fits better than another
   `contentStack` layer; **confirm this matches your intent**, since it's
   a UX choice not pinned down in your answers).
2. `ui/controller/screen/TriageController.java`:
   - `loadNextGroup(albumId)` — pulls the next scene group with
     `Unassigned` photos via
     `SceneGroupRepository.findGroupsWithUnassignedPhotos`.
   - Renders that group's photos in a grid (`triage_grid.fxml`).
3. **Drag interaction** (`triage_grid_dragging.fxml`):
   - On drag start of a tile, show the four directional drop targets
     (`.drag-target` / `.drag-target-active-*` styles already provided)
     positioned top-left/bottom-left/top-right/bottom-right.
   - Standard JavaFX `setOnDragDetected` / `Dragboard` +
     `setOnDragOver`/`setOnDragDropped` on each target zone, mapping:
     top-left → RatherNo, bottom-left → No, top-right → RatherYes,
     bottom-right → Yes (per spec §5).
4. **Keyboard shortcuts** as an alternative to dragging — same four states,
   bound at the grid/scene level (**exact keys TBD**, same open point as
   Phase 6).
5. **Group completion (§5):** once any photo in the current group is set
   to `Yes` (via drag or shortcut), show a "Next group" action; on
   advancing, bulk-update remaining `Unassigned` photos in that group to
   `RatherNo` via `updateStateBulk`.
6. **Auto-advance:** after a group is completed/skipped, call
   `loadNextGroup` again; if none remain, close the modal (or show a
   "Triage complete" state) and publish `CustomAlbumUpdatedEvent` so the
   unrefined view (if left open behind it) refreshes.

---

## Phase 8 — Cover photo (§6)

1. `CustomAlbum.coverPhotoId` starts `NULL`.
2. Tree/thumbnail rendering for `ALBUM_CUSTOM` nodes (and anywhere else
   custom-album thumbnails surface, e.g. a future albums-overview grid):
   `coverPhotoId != null ? that photo : findFirstYesByAddedOrder(albumId).orElse(placeholder)`.
3. Explicit "Set as cover" action (context menu on a photo *within* the
   custom-album view only, not the global `GridContextMenu`, since cover
   photo is meaningless outside an album context) →
   `CustomAlbumRepository.updateCoverPhoto(albumId, photoId)`.

---

## Phase 9 — Config / settings surface

If Phase 0's open question resolves to "configurable via
`AiConfig`/`AiSettingsService`," add corresponding fields to
`SettingsController`/`settings.fxml` for the timestamp/geo/perceptual-hash
thresholds, following whatever pattern existing AI thresholds already use
there.

---

## Suggested build order

1. Phase 0 (schema + jOOQ regen) — nothing else compiles without it.
2. Phase 1 (repositories) — needed by everything downstream.
3. Phase 4 (add-to-album menu + addition service) — smallest end-to-end
   slice you can manually verify (rows land in `custom_album_photo`).
4. Phase 3 (tree: Custom root, +, rename) — lets you create/see albums
   without needing the grouping engine yet.
5. Phase 2 (scene grouping engine + job) — can be developed/tested against
   the data Phase 3/4 already produce.
6. Phase 5 + 6 (custom album panel, unrefined/refined views, state
   editing) — now meaningful since photos have states and groups.
7. Phase 7 (triage mode) — layers on top of everything above.
8. Phase 8 (cover photo), Phase 9 (config surface) — polish, can slot in
   whenever convenient.

## Still-open items to confirm before/while implementing

- Threshold storage location (Phase 0/9): `AiConfig` extension assumed.
- Whether AI-generated album groups (Event/Location/Similarity) should
  really support rename given they're regenerated on every AI pipeline run
  (Phase 3, item 3) — or whether "apply to other albums" should be scoped
  to *Custom only* for rename, with your original ask reinterpreted as "the
  same tree mechanics (icons, layout, `AlbumTypeGroup`-style code)" rather
  than literally enabling rename on generated albums.
- Exact keyboard shortcuts for the 5 states (Phases 6 and 7).
- Whether triage mode is a modal `Stage` or another `contentStack` layer
  (Phase 7, item 1).
- Whether the "Add to album" dialog should support creating a new album
  inline (assumed yes, since otherwise the user must cancel, go create one
  via "+", then retry).
