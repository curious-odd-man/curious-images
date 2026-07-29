# Integration Requirements: Photo Shoot Magic → Photo Management App

## 1. Purpose & Scope

This document specifies the requirements for integrating the photo-culling and
grouping functionality of **Photo Shoot Magic** (a standalone JavaFX desktop
app, source reviewed in `photo-shoot-magic.zip`) into an existing photo
management application.

Requirements below were **elicited directly from the source code** (models,
controllers, services, and FXML views) rather than from a separate spec, since
none existed. Each requirement traces back to the class/behavior that
implements it today, so the receiving team can validate against the original
implementation.

> **Assumption / open question:** The prompt did not specify the target photo
> management app's platform (web, mobile, desktop), tech stack, or existing
> data model. The requirements below are written to be **implementation-agnostic**
> (i.e., they describe *behavior*, not JavaFX APIs), so they can be adapted to
> any host application. Platform-specific questions are called out in
> **Section 6 – Open Questions**.

---

## 2. Source Feature Inventory (what the app currently does)

| Area | Source class(es) |
|---|---|
| Project lifecycle (create/open/save/recent) | `MainController`, `ProjectSaveLoadService`, `Project` |
| Photo import from disk | `Core.loadPhotos`, `Photo` |
| Grouping / culling | `PhotoGroup`, `PredefinedGroups`, `Core`, `ImageContextMenuController` |
| Selection | `SelectedPhotos`, `MainController` |
| Grid/thumbnail view + zoom | `MyImageView`, `MainController` |
| Export | `MainController.onExportGroup` |
| App-level metadata (recent projects) | `ApplicationMetadata`, `ApplicationMetadataSaveLoadService` |
| Persistence format | `Project` (JSON via Jackson) |

---

## 3. Functional Requirements

### 3.1 Photo Import
- **FR-1**: The system shall allow a user to select a root directory and
  recursively scan it for photos, adding every discovered photo to the
  full/global photo set (`Core.loadPhotos`, `PredefinedGroups.ALL`).
- **FR-2** *(current limitation — carry forward as a decision point, not a
  requirement to replicate as-is)*: The reference implementation filters
  files by a case-sensitive `.jpg` extension only (`Core.isJpgFile`). Other
  formats (`.jpeg`, `.png`, `.JPG`, RAW, etc.) are silently skipped.
  **Integration should decide whether to keep this restriction or broaden it**
  to match the host app's supported formats.
- **FR-3**: Each imported photo shall be uniquely identified by its file path
  and shall track the set of groups it belongs to (`Photo.path`,
  `Photo.groups`).

### 3.2 Grouping / Culling
- **FR-4**: The system shall provide a fixed set of predefined groups:
  `All`, `Ungrouped`, `User Groups` (a container, not directly assignable),
  `Yes`, `Maybe`, `Rather No`, `No` (`PredefinedGroups`, `Core.getDefaultGroups`).
- **FR-5**: The system shall let a user assign one or more selected photos to
  any predefined group (`Yes`/`Maybe`/`Rather No`/`No`) via a context menu
  (`ImageContextMenuController.onAddToGroup`).
- **FR-6**: The system shall let a user create a new custom ("user") group
  from the current selection. Default naming shall auto-increment
  (`Grp-1`, `Grp-2`, …) based on existing group names matching that pattern
  (`ImageContextMenuController.onCreateNewGroupClick`).
- **FR-7**: The system shall let a user add selected photos to any existing
  custom group, listed by name in the context menu
  (`ImageContextMenuController.getMenu`).
- **FR-8**: A photo shall be able to belong to **multiple groups
  simultaneously** (predefined and/or custom) — group membership is not
  mutually exclusive (`Photo.groups` is a list; `PhotoGroup.addPhoto` appends).
- **FR-9**: Groups shall support a nested/hierarchical structure (subgroups),
  and viewing a parent group shall recursively include photos from all its
  subgroups (`PhotoGroup.subGroups`, `PhotoGroup.getPhotosRecursive`).
- **FR-10**: The system shall automatically maintain an **"Ungrouped"**
  virtual group containing every photo that has no custom-group membership
  (predefined `All`/`Ungrouped` tags don't count). This shall be recalculated
  on every refresh, not persisted (`MainController.refreshUngrouped`,
  `ProjectSaveLoadService.save` explicitly skips saving `Ungrouped`).

### 3.3 Selection
- **FR-11**: The system shall support single-click selection of a photo,
  replacing any prior selection.
- **FR-12**: The system shall support Ctrl+click to toggle a photo in/out of
  a multi-photo selection without clearing existing selections
  (`MainController` mouse handler, `SelectedPhotos.add/remove`).
- **FR-13**: The system shall visually distinguish selected photos (CSS
  "selected" style) in the grid.
- **FR-14**: Right-clicking an unselected photo shall select it (single) before
  opening the context menu; right-clicking an already-selected photo shall
  preserve the existing multi-selection.
- **Note (gap in source)**: Shift+click range-selection is referenced in the UI
  event handler but explicitly **not implemented** (`log.error("Shift down
  detected, but not implemented")`). Decide whether to implement this during
  integration rather than port the gap.

### 3.4 Navigation & Viewing
- **FR-15**: The system shall present groups in a navigable tree (predefined
  groups + nested custom groups), and selecting a tree node shall display all
  photos in that group (recursively including subgroup photos) in a grid/pane
  (`MainController.viewSelectedGroup`, `TreeView<PhotoGroup>`).
- **FR-16**: The system shall support continuous zoom of thumbnail size via a
  slider (range observed: 100–1920 px) and via Ctrl+scroll-wheel over the
  photo grid, with both width and height bound to the same value
  (`MyImageView.fitWidthProperty/fitHeightProperty`, `photoZoomSlider`).
- **FR-17**: Each thumbnail shall display the photo and its filename
  (`MyImageView.assignPhoto`); if the underlying file is missing on disk, the
  system shall handle this gracefully (log only, no crash) rather than fail
  the view.

### 3.5 Export
- **FR-18**: The system shall let a user export a selected group's contents
  as a flat, comma-separated list of filenames (recursively including
  subgroup photos) to a text file, and open it in the OS's default text
  viewer (`MainController.onExportGroup`, `Desktop.getDesktop().edit`).
  **Integration should confirm whether "open in external editor" is
  appropriate for the host platform**, or whether export should instead
  produce a downloadable file / in-app view.

### 3.6 Project Lifecycle & Persistence
- **FR-19**: The system shall support creating a **named project** rooted at
  a chosen photo directory, represented by its own project file
  (`CreateProjectDialog`, extension observed: `.psmp`).
- **FR-20**: The system shall support opening a project from a file, and
  saving the current project (manually via menu, and automatically on
  application exit) (`onMenuItemSaveClick`, `Main.stop` →
  `onApplicationStop`).
- **FR-21**: Project data shall persist: project name, image root path, the
  full list of image paths (as an index-referenced array to avoid path
  duplication), and a map of group name → list of image indices
  (`Project`, `ProjectSaveLoadService.save/load`). The `Ungrouped` group is
  excluded from persistence and recomputed on load.
- **FR-22**: The system shall track and expose a **most-recently-used (MRU)
  project list**, updated (moved to front, de-duplicated) every time a
  project is saved or opened (`ApplicationMetadata.recentProjects`,
  `ProjectSaveLoadService.updateRecentProjects`).
- **FR-23**: On startup, the system shall attempt to auto-load the most
  recent project, failing silently (log only) if it cannot
  (`MainController.initialize`).
- **FR-24**: The system shall support loading a project by passing its file
  path as a command-line/launch argument (`MainController.actOnCommandLineArgs`)
  — relevant only if the host app has an equivalent "open with" or deep-link
  entry point.
- **FR-25**: Application-level metadata (distinct from any single project)
  shall be stored independently of project files, in a per-user location
  (reference implementation: `~/.photo-shoot-magic/metadata.json`)
  (`ApplicationMetadataSaveLoadService`).
- **FR-26**: The window/app title shall reflect the currently open project's
  name (`Main.setWindowTitle`, called from `Core.setProject`).

---

## 4. Data Model Requirements (for the target app's schema)

The integration needs to represent, at minimum:

| Entity | Key attributes (from source) | Notes |
|---|---|---|
| **Photo** | file path (unique id), list of group memberships | No EXIF/rating fields exist today — ratings are modeled entirely as group membership (Yes/Maybe/Rather No/No), not a numeric field. |
| **PhotoGroup** | name, optional sub-groups, optional photos | Groups and subgroups are mutually exclusive containers in practice (a group holds either photos or subgroups, per usage), but the model technically allows both. |
| **Project** | name, root path, ordered image path list, group→indices map | Index-based serialization to avoid duplicating paths in the group map — an implementation optimization, not necessarily required in the target schema (e.g., a relational or document DB would likely reference photos by ID directly instead). |
| **ApplicationMetadata** | recent project list (MRU, deduplicated) | App-scoped, not project-scoped. |

**Integration requirement**: Map the above onto the host app's existing photo
identity model (e.g., asset ID, cloud URI, etc.) rather than file-path
identity, if the host app doesn't use raw filesystem paths as the primary key.

---

## 5. Non-Functional / Technical Observations from Source

- **NFR-1 (Persistence format)**: Reference implementation uses Jackson-serialized
  JSON, pretty-printed, written synchronously to disk on save (`ObjectMapper`,
  `Files.writeString`). No schema versioning is present — integration should
  add a version field if projects need to remain forward-compatible.
- **NFR-2 (Error handling)**: Persistence failures are broadly caught and only
  logged (`@SneakyThrows`, catch-and-log blocks); there is **no user-facing
  error messaging** for failed load/save. This should likely be strengthened
  before integration into a production app.
- **NFR-3 (Concurrency)**: All operations are synchronous on what appears to be
  the UI thread; large directories are scanned via `Files.walk` without
  progress feedback or cancellation. For a production integration, an async/
  background import with progress reporting is recommended, especially for
  large photo libraries.
- **NFR-4 (Logging)**: The source uses method-level tracing annotations
  (`@Loggable`) throughout — an implementation detail that doesn't need to be
  carried over, but indicates the original author valued traceability of
  state-changing operations (save/load/group changes), which the integration
  should preserve via the host app's own logging/telemetry.
- **NFR-5 (Theming)**: Multiple bundled UI themes exist (Cupertino, Dracula,
  Nord, Primer, light/dark variants) — cosmetic, not required for functional
  integration, but signals the original app supported user-selectable themes.

---

## 6. Out of Scope / Not Found in Source

- No AI-based photo quality scoring, duplicate detection, or auto-culling
  logic exists in the source — all grouping is manual/user-driven.
- No cloud sync, sharing, multi-user, or authentication concerns.
- No image editing (crop, rotate, filters) beyond simple thumbnail display.
- No EXIF/metadata extraction (only file path + display name are used).

---

## 7. Open Questions for Integration Planning

1. **Target platform**: Is the host photo management app desktop, web, or
   mobile? This determines whether FR-16 (zoom UX), FR-18 (export via OS file
   handler), and FR-19–24 (file-based project model) can be ported directly
   or need re-architecting (e.g., project = a saved collection/album in a
   database instead of a `.psmp` file).
2. **Photo identity**: Does the host app already assign stable photo IDs
   (vs. this app's reliance on filesystem path as identity)? Needed to adapt
   FR-3, FR-8, and the persistence format in Section 4.
3. **Existing rating/tagging model**: Does the host app already have a
   rating, favoriting, or tagging system this "Yes/Maybe/Rather No/No"
   workflow should map onto, or should it be added as a new, separate feature?
4. **Multi-select range (shift+click)**: Should this be implemented properly
   during integration, given it's an acknowledged gap in the source (Section 3.3)?
5. **Supported file types**: Should the `.jpg`-only import restriction (FR-2)
   be lifted to match whatever formats the host app already supports?
6. **Export behavior**: Should "Export" (FR-18) produce a downloadable file,
   an in-app share sheet, or integrate with the host app's existing export/
   sharing feature, rather than shelling out to the OS's default text editor?

---

*Prepared by analysis of `photo-shoot-magic.zip` (Java/JavaFX source, package
`com.github.curiousoddman.photoshootmagic`). Every functional requirement
above cites the originating class/method for traceability.*
