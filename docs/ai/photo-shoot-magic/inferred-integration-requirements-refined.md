# Integration Requirements (Refined): Photo Shoot Magic → cImages

## 0. What changed in this revision

The original `integration-requirements.md` was written **implementation-agnostic**
because the target app's platform, stack, and data model were unknown at the time.
That's no longer true: the target app's source (`main.zip`) and `build.gradle` are
now available. This revision:

- Identifies the target app by name and architecture (Section 1).
- Re-maps every FR from the source doc against what the target **already has,
  partially has, or doesn't have at all** (Section 2), citing target classes/tables
  for traceability, the same way the original doc cited Photo Shoot Magic classes.
- Replaces the old generic "Section 7 Open Questions" (platform? file-based vs DB?)
  with **concrete, codebase-grounded questions** — because most of those original
  questions are now answered by the source, but several *new*, more specific
  questions appear once you see the real schema and UI.
- **Does not decide** the open questions below. Several plausible mappings exist
  (e.g., "groups" → the existing but unused `MANUAL` album type, vs. a new table),
  and picking one is a product/architecture decision, not something to infer
  silently from code.

---

## 1. Target System Identification

| | |
|---|---|
| **Name / package** | `cImages` (`com.github.curiousoddman.curious_images`) |
| **Stack** | Spring Boot 4 + JavaFX 25 desktop app (single deployable JAR), not web/mobile |
| **Persistence** | H2 (file-based, PostgreSQL compatibility mode) via **jOOQ**, schema managed by **Flyway** migrations (`V001`–`V012`) |
| **Search/indexing** | Apache Lucene (HNSW vector search) for CLIP/face embeddings |
| **AI pipeline** | ONNX Runtime (GPU) for face detection/embedding (ArcFace), CLIP image embeddings, tag embeddings; face clustering into `person` records |
| **Media types** | Photos (`jpg`, `jpeg`, `png`, `cr2`) **and** videos (`mp4`, `mov`, `m4v`) — see `MediaExtensions` |
| **Background work** | Async job model already exists: `JobManager`/`JobFactory`/`BackgroundJob`, plus a durable retry queue (`pending_action` table, `DurableActionService`) |

This resolves the original doc's top-level open question ("is the host platform
desktop, web, or mobile?" — **Section 7, Q1**): it's a **JavaFX desktop app**, same
category as Photo Shoot Magic, backed by a real relational schema rather than a
single `.psmp` project file.

---

## 2. Source Requirement → Target Reality Mapping

### 2.1 Photo Import (FR-1, FR-2, FR-3, NFR-3)

**Already superseded — target is strictly ahead of the source app:**

- FR-1 (recursive directory scan): exists (`ImportJob`, `FileCollectingVisitor`), and
  the target supports **multiple named import roots simultaneously**
  (`import_root` table, `ImportRootRepository.findAll()`), not a single project root.
- FR-2 (`.jpg`-only, case-sensitive): **not a limitation in the target.** Supported
  extensions are `jpg`, `jpeg`, `png`, `cr2` (photos) and `mp4`, `mov`, `m4v`
  (videos) — case-insensitively — per `MediaExtensions.SUPPORTED_EXTENSIONS`. The
  target also imports **video**, which has no equivalent in Photo Shoot Magic at all.
- FR-3 (unique identity + group membership): identity is `media.id`
  (auto-increment PK), **not file path** — path is a unique column, not the key.
  This directly answers Section 7 Q2 of the original doc.
- NFR-3 (synchronous scan, no progress/cancellation): **already solved** — import
  runs as a tracked async job with per-run stats (`import_job_stats`,
  `import_job_file_issue` tables; `ImportStatsController`, `ImportStatsUpdatedEvent`).

**No action needed here.** Nothing from Photo Shoot Magic's import model should be
ported; the target's import pipeline is more capable in every dimension the source
doc raised.

### 2.2 Grouping / Culling (FR-4 through FR-10)

**Partially present, but structurally different — this is the crux of the integration.**

The target has an `album` concept (`album`, `album_media` tables) that is
superficially similar to Photo Shoot Magic's "group":

- `album.type` is one of `PERSON | EVENT | LOCATION | SIMILARITY | MANUAL`.
- Only `EVENT`, `LOCATION`, and `SIMILARITY` are currently **auto-generated** and
  shown in the UI tree (`TreeManager.ALBUM_TYPE_GROUPS`); `PERSON` albums are
  driven by face clustering (`person` table) and shown separately. **`MANUAL` is
  defined in the schema (`V008__album_schema.sql`) but is not created, populated,
  or surfaced anywhere in the current UI or repositories** — there is no
  "create album", "add to album", or "remove from album" action in the codebase
  today (confirmed: `GridContextMenu` only offers Rotate/Reveal-in-Explorer; no
  album-assignment menu item exists anywhere).
- Album membership today (`album_media`) is a **flat junction table**: one row per
  (album, media) pair, with a `sort_order`. There is **no subgroup/hierarchy
  column or table** — nothing analogous to Photo Shoot Magic's `PhotoGroup.subGroups`
  / recursive `getPhotosRecursive`. A photo *can* belong to multiple albums
  simultaneously today (no uniqueness constraint prevents it beyond the PK on
  `(album_id, media_id)`), so FR-8 (multi-group membership) is schema-compatible;
  FR-9 (nested subgroups) is **not** schema-compatible without a migration.
- There is no `Ungrouped` equivalent, and no equivalent of the fixed
  `Yes/Maybe/Rather No/No` culling taxonomy — the target has no rating/favorite/
  flag field on `media` or `photo` at all (checked `Media.java`, the `photo`/`media`
  Flyway tables, and `RightPanelController`, which shows tags/GPS/EXIF/persons but
  no rating control).
- The target **does** have a separate, more general tagging system
  (`tag_embedding`, `media_tag` with `tag_source = AI | USER`) that already
  distinguishes AI-assigned vs. user-assigned tags — this is a plausible existing
  mechanism for a "rating" concept, but it's a free-form tag vocabulary
  (hundreds of seeded categories/tags in `R__tag_data.sql`), not a fixed 4-value
  culling scale, and there's no UI to add a user tag to a photo today either.

### 2.3 Selection (FR-11 through FR-14)

**Does not exist in the target today.** Grid cells (`GridCellController`) handle a
single primary-button click by invoking `onPhotoClicked` (opens something —
traced to a single-photo detail/viewer path), but there is **no multi-selection
model**: no Ctrl+click toggle, no "selected" CSS class, no shift+click handling
(so the gap the source doc flagged in Photo Shoot Magic — "shift+click not
implemented" — isn't a partial feature to extend here; it's a **from-scratch
build**, since even single-click multi-select via Ctrl doesn't exist yet).
This means FR-11–FR-14 are **net-new work**, not a port of an existing partial
implementation, and they're a **prerequisite** for FR-5/FR-6/FR-7 (assigning a
*selection* of photos to a group), since there's currently no way to select more
than one photo at a time to act on.

### 2.4 Navigation & Viewing (FR-15, FR-16, FR-17)

- FR-15 (tree of groups, recursive photo listing): the target already has an
  analogous, working tree (`TreeManager`, `LibraryTreeNode`, `TreeView`) covering
  Folders / Timeline / Albums / People / Duplicates — so the **pattern** exists,
  but it's driven by flat album membership (2.2), not recursive subgroup lookup.
- FR-16 (continuous zoom slider + Ctrl+scroll, 100–1920px): a `thumbnailSizeSlider`
  already exists in `GridController` and is already wired to cell sizing
  (`bindThumbnailSize`). **Ctrl+scroll-wheel zoom was not found** in the grid
  controller — only the slider. Range/behavior parity with FR-16 needs a look at
  the FXML slider bounds, not assumed to already match 100–1920.
- FR-17 (missing-file-on-disk handled gracefully): not verified in this pass;
  `last_seen_at` on `media` suggests the target already tracks file presence over
  time (likely for rescans), which is a reasonable hook, but the actual
  missing-file-at-render-time behavior wasn't traced.

### 2.5 Export (FR-18)

**Does not exist in the target.** No export menu item, no "write filenames to a
text file" logic, no `Desktop.getDesktop()` usage, found anywhere in the target
source. This is fully net-new if wanted.

### 2.6 Project Lifecycle & Persistence (FR-19–FR-26)

**Not applicable in the target's current form, and shouldn't be ported as
originally written.** The target has **one long-lived library**, not
multiple user-named "projects" backed by separate files:

- There is no `.psmp`-equivalent "project" entity, no "create/open project"
  dialog, no MRU-projects list, no command-line "open with project file" path.
- Multiple **import roots** already provide the closest equivalent to "a photo
  directory the app knows about" (Section 2.1), but that's a single unified
  library spanning all roots, not Photo Shoot Magic's one-project-per-root-folder
  model.
- `user_preferences` (key/value table, `V003__user_preferences.sql`) already
  covers FR-25/FR-26-style app-level settings (window position/size, split-pane
  width) — this is the target's existing equivalent of
  `ApplicationMetadata`/`~/.photo-shoot-magic/metadata.json`, just schema-backed
  instead of a JSON file.
- Persistence itself is Flyway/jOOQ/H2 end-to-end, not Jackson-JSON-to-a-file, so
  FR-21 (index-referenced photo/group serialization) and NFR-1
  (no schema versioning) are **moot** — Flyway *is* the target's schema
  versioning, and photo/album/tag relationships are already normalized
  relational tables, not a hand-rolled JSON index scheme.

**FR-19–FR-24 should not be ported as "create/open/save a project" file
operations.** If any part of "projects" is still wanted (e.g., scoping a working
session to one import root, or a saved/named view), it needs to be re-specified
against albums/import-roots/tree-nodes, not against `.psmp` files.

---

## 3. Revised Functional Scope (pending answers in Section 4)

Given Section 2, the realistic integration scope narrows to:

1. **Selection model** (net new): single-click select/replace, Ctrl+click
   toggle, shift+click range-select (doing this correctly, unlike the source
   app's acknowledged gap), visual "selected" state in the grid.
2. **Manual grouping** (repurposing the dormant `MANUAL` album type, or a new
   construct — see Q1–Q3 below): assign a selection to one or more groups,
   create a new group from a selection, list existing groups in a context menu.
3. **A fixed culling vocabulary** (`Yes`/`Maybe`/`Rather No`/`No` or equivalent):
   needs a defined home — new column(s)? special reserved `MANUAL` albums?
   the existing `media_tag`/`tag_embedding` mechanism? (Q4).
4. **Export** (net new): produce a list of filenames for a group/album/selection.
5. Everything else the source doc specified (import, project files, app metadata,
   recent-projects) is **superseded by existing, more capable target
   functionality** and should be dropped from scope rather than ported.

---

## 4. Open Questions (must be answered before finalizing FRs — not guessed here)

1. **Where do "groups" live?** The schema already has an unused `MANUAL` album
   type (`album.type = 'MANUAL'`) with a flat `album_media` join table. Should
   manual grouping/culling be built as `MANUAL` albums (reusing existing tables,
   accepting flat/non-nested structure), or does this need a distinct new
   concept/table because the semantics (temporary culling workflow) differ from
   the existing auto-generated album types (permanent, AI-curated collections)?
2. **Is nested/hierarchical grouping (source FR-9) actually required?** The
   target's album model is flat today. Adding subgroup support means a schema
   change (parent/child on `album`, and recursive membership queries) that
   ripples through `TreeManager.buildAlbumItems`, `AlbumRepository`, and the tree
   UI. Confirm this is in scope before it's designed, since "flat groups only"
   is a materially smaller change.
3. **Does group/album membership need to stay mutually non-exclusive** (a photo
   in `Yes` and a custom group at once, per source FR-8), and if `MANUAL` albums
   are reused for this, should the existing auto-generated album types
   (`EVENT`/`LOCATION`/`SIMILARITY`/`PERSON`) be allowed to coexist with manual
   ones on the same photo (they already can, schema-wise) — any UI concern with
   mixing AI-curated and user-curated albums in one tree section?
4. **What should the `Yes`/`Maybe`/`Rather No`/`No` culling values actually be
   modeled as?**
   - Four reserved, non-deletable `MANUAL` albums (closest literal port of the
     source), or
   - A dedicated rating/flag field on `media`/`photo` (new column, simpler
     queries, but a schema addition and a new concept alongside the existing
     tag/album systems), or
   - Values in the existing `media_tag`/`tag_embedding` system with
     `tag_source = USER` (reuses existing infrastructure, but that system is a
     free-vocabulary tagger, not a constrained 4-value scale, and there's
     currently no "add a user tag" UI to build on)?
5. **Selection model scope**: since there is no selection mechanism in the target
   at all today (Section 2.3), should this be built as a general-purpose,
   grid-wide feature (usable for future bulk actions beyond grouping — e.g. bulk
   rotate, bulk delete), or scoped narrowly to "selection for group assignment"?
   This affects where the selection state should live (e.g., a shared service
   like `PhotoGridManager` vs. something local to a new grouping feature).
6. **Export target and trigger**: source FR-18 shelled out to
   `Desktop.getDesktop().edit()`. Since the target already has a working desktop
   context-menu pattern (`GridContextMenu`, `ExplorerUtils.revealInExplorer`),
   is "reveal/open in OS default app" still the desired behavior for export, or
   should export write into a location the user picks (`FileChooser`), or
   integrate with clipboard/copy instead?
7. **Command-line / "open project" launch (source FR-24)**: since the target has
   no per-project file to open, is there any remaining desired behavior here
   (e.g., "open cImages and jump straight to a given import root or album via a
   launch argument"), or should this requirement simply be dropped?
8. **Video handling for grouping/culling**: Photo Shoot Magic only ever dealt
   with photos. The target's `media` table unifies photos and videos. Should
   manual groups/culling apply to videos too, or photos only?
9. **RAW (`cr2`) and rating**: given `cr2` is already a supported import
   extension, does culling/export need any RAW-specific handling (e.g., export
   listing the RAW path vs. a derived preview), or is a photo just a photo for
   this feature regardless of extension?

---

## 5. Non-Functional Notes Carried Forward (still applicable)

- **NFR-4 (traceability of state changes)**: the target already has an
  event-driven update pattern (`LibraryUpdatedEvent`, `TreeViewUpdateEvent`,
  `AiPipelineCompleteEvent`) that group/selection/export changes should plug
  into for tree/grid refresh, rather than introducing a parallel notification
  mechanism.
- **NFR-2 (user-facing error messaging)**: the target already uses
  `NotificationsService`/`NotificationMenuItemController` for background-job
  outcomes (see import stats) — any new grouping/export logic should surface
  errors the same way rather than silently logging, consistent with the
  original doc's recommendation to strengthen this over Photo Shoot Magic's
  log-only behavior.

---

*Prepared by direct inspection of the target source (`main.zip`,
package `com.github.curiousoddman.curious_images`) and `build.gradle`, cross-
referenced against `integration-requirements.md`'s citations of Photo Shoot Magic
(`photo-shoot-magic.zip`). Section 4 questions are blocking: several FRs in
Section 3 cannot be finalized (data model, UI surface, or both) until they're
answered.*
