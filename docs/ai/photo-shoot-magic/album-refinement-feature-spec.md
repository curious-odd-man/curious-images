# Feature Spec: User-Defined Albums with Refinement

## 1. Overview

Users can create albums, add photos to them, and then *refine* the album by
marking each photo with a preference state. The app assists refinement by
automatically clustering visually similar photos ("scene groups") so the user
can quickly compare near-duplicates (e.g. burst shots) and decide which to
keep, either one at a time or via a dedicated **triage mode**.

Only photos in the `Yes` state appear in the **refined view**. All other
states are visible only in the **unrefined view**, where photos are grouped by
scene and visually highlighted according to their state.

---

## 2. Data Model

### Photo
- Existing entity in the app; unchanged by this feature.
- State (below) and scene group membership are **per-album**, not global —
  the same photo can be `Yes` in one album and `No` in another.

### Album
- `id`
- `name`
- `cover_photo_id`: nullable — see §6
- `created_at`, `updated_at`

### AlbumPhoto (join entity: Photo ↔ Album)
- `photo_id`
- `album_id`
- `state`: enum `{No, RatherNo, Unassigned, RatherYes, Yes}`
  - Recommended internal representation: numeric `{-2, -1, 0, 1, 2}` for easy
    threshold/sort logic, mapped to the 5 friendly labels in UI.
  - Default on add: `Unassigned` (`0`).
- `scene_group_id`: nullable reference to a `SceneGroup`. Null = singleton
  (no similar photos found).
- `added_at`

### SceneGroup
- `id`
- `album_id`
- Represents a cluster of photos within one album judged to depict the same
  moment/subject (e.g. a burst of near-identical shots).
- A photo with no matches is **not** placed in a shared group — it is treated
  as its own single-photo group (see §4.4).

---

## 3. Core Workflows

1. **Create album** — user names a new, empty album.
2. **Add photos** — user adds one or more photos. Each new `AlbumPhoto` is
   created with `state = Unassigned`. Scene grouping runs incrementally over
   the new photos (see §4).
3. **Refine** — user reviews photos, typically via triage mode (§5) or
   directly in unrefined view, and assigns states.
4. **View modes** — user switches between refined and unrefined view at will
   (see §7 for lock behavior).
5. **Incremental additions** — user can add more photos to an existing album
   at any time, in either view. New photos enter as `Unassigned` and are
   merged into the existing scene grouping.

---

## 4. Scene Grouping (Automatic Clustering)

### 4.1 Candidate matching (cheap pre-filter)
Two photos are only considered as **candidates** for the same scene if they
are close in:
- **Timestamp** (configurable threshold)
- **Geolocation** (configurable threshold), **if available**

If geolocation is missing for one or both photos, fall back to
**timestamp-only** proximity for candidate matching.

### 4.2 Similarity confirmation
Candidates passing the timestamp/geolocation pre-filter are then compared via
**perceptual hashing** to confirm visual similarity. This keeps the pipeline
on-device/offline-friendly (no embedding model required).

### 4.3 Recomputation strategy
- Full clustering runs once when an album's photo set is first evaluated.
- Subsequent photo additions are processed **incrementally**: new photos are
  matched against existing groups/photos rather than reclustering the whole
  album from scratch.
- **Assignment stability**: once a photo is assigned to a scene group, that
  assignment should not change. (Full recompute each time is acceptable only
  if incremental matching proves impractical to implement — noted as a
  fallback, not the default approach.)

### 4.4 Singletons
Photos with no matching candidates form their own single-photo group. In
grouped-by-scene layouts, each singleton is displayed as its own
single-item group (not collected into a shared "ungrouped" bucket).

### 4.5 Configurability
Timestamp and geolocation proximity thresholds should be configurable
(reasonable defaults to start, adjustable later). Exact default values are
not defined yet — TBD during implementation.

---

## 5. Triage Mode

A dedicated, fast-path review flow for working through `Unassigned` photos.

- **Scope**: shows only `Unassigned` photos, one scene group at a time.
- **Layout**: grid view of all photos in the current scene group.
- **Interaction**: drag-based, full 5-state granularity via directional
  targets:
  - Drag to **top-left** → `RatherNo`
  - Drag to **bottom-left** → `No`
  - Drag to **top-right** → `RatherYes`
  - Drag to **bottom-right** → `Yes`
  - On drag initiation, the four directional targets are visually displayed
    so the mapping is clear to the user.
  - Keyboard shortcuts are also supported as an alternative to dragging.
- **Group completion**: once the user marks a photo `Yes` within the group,
  they may choose to advance to the next group. If they do so, any remaining
  `Unassigned` photos in that group are **automatically set to `RatherNo`**.
- **Auto-advance**: after a group is completed (or skipped forward per
  above), triage automatically advances to the next scene group containing
  `Unassigned` photos, continuing until the album is fully triaged.

---

## 6. Cover Photo

- Cover photo is **user-selected**, not automatic.
- Before the user selects one, the album thumbnail defaults to the **first
  photo marked `Yes`**.
- If no photo is `Yes` yet, the album thumbnail shows a **placeholder**.

---

## 7. View Modes

### Refined view
- Shows only photos with `state = Yes`.
- **Locked** whenever the album has zero `Yes` photos at the current moment.
  The lock is **re-evaluated live**: if the last remaining `Yes` photo is
  changed to another state, refined view re-locks immediately.
- If the album has no `Yes` photos, the **default view is unrefined**.

### Unrefined view
- Shows all photos regardless of state.
- Organized/grouped by scene group (including singleton groups, §4.4).
- Each photo is visually highlighted according to its **state** (color-coding
  per one of the 5 states).

### State editing outside triage
- In unrefined view, individual photos can have their state changed directly
  via keyboard shortcuts and/or a 5-button/slider control.

### Bulk actions on scene groups
Available directly on a scene group (outside of triage), both supported:
- **Set entire group to state X** in one action.
- **Mark all in group as `No` except selected photo(s)**.
- No automatic "representative photo" suggestion is involved — bulk actions
  are always explicit user actions, not auto-triggered by other selections.

---

## 8. Explicitly Out of Scope / Decided Against
- No undo/history tracking for state changes.
- No automatic scene-group representative/best-shot suggestion.
- No global (cross-album) photo state — state is strictly per-album.
