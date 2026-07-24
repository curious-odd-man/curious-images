# Video Support — Implementation Plan (v2)

## Status

| Phase                                                                                 | Status        |
|---------------------------------------------------------------------------------------|---------------|
| 1. Schema migration + jOOQ regen + app compiles/runs (photos-only behavior unchanged) | 🟢 Completed  |
| 2. Video import + browse (extension filtering, metadata, thumbnails, plain grid)      | 🟢 Completed  |
| 3. Hover-preview playback                                                             | 🟢 Completed  |
| 4. Duplicate detection (file-hash, `media_hash`)                                      | ⬜ Not started |
| 5. AI pipeline (frame sampling + face/CLIP + clustering)                              | ⬜ Not started |
| 6. Albums + unified search, `MediaItem` abstraction                                   | ⬜ Not started |

## Phase 2 — what landed

- `VideoMetadataExtractor` (JavaCV/FFmpeg): duration, codec, frame rate, rotation,
  container creation-date, GPS (ISO6709 tag). Rejects non-H.264/AAC files at import time.
- `VideoThumbnailGenerator`: seeked single-frame extraction feeding the existing thumbnail
  resize/cache pipeline.
- `ImportJob` branches image vs. video extensions (mp4/mov/m4v); new
  `ImportOutcome.REJECTED_UNSUPPORTED_CODEC`, counted separately in the scan summary.
- `MediaRepository`: `insertVideo`, `updateVideoMetadataQuery`, `findMediaByFolderId`,
  `findMediaByIdIn`, generic `findExistingMediaSummaryByAbsolutePath` for the cheap-rescan check.
- Grid/browse UI generalized from photo-only to mixed `Media` (several places previously called
  `.photo()` unconditionally on the `Either`-backed `Media` type, which would have thrown for any
  video item — `PhotoGridModel`, `PhotoGridRowController`, `GridCellController`, `GridController`,
  `PhotoGridCallbacks`, `PhotoGridManager`). Play-icon overlay added to `photo_cell.fxml` to
  distinguish video tiles.
- `LibraryController` search path uses the generic `findMediaById`.

**Dependency**: `org.bytedeco:javacv-platform` needs adding to the build (no `pom.xml`/
`build.gradle` was present in the uploaded archive to add it directly) — see
`VIDEO_DEPENDENCY_NOTES.md`.

## Phase 3 — what landed

- **Hover preview**: `GridCellController` now has a `MediaView`/`MediaPlayer` pair. On
  `onMouseEntered` for a video cell, plays the actual file muted + looped, swapping out the
  thumbnail `ImageView`/play-icon overlay for the `MediaView`; on `onMouseExited` (or on recycle
  via `showPlaceholder`/`showEmpty`, or when the owning row itself goes fully empty) the player is
  stopped and disposed. Only one `MediaPlayer` is ever alive at a time, since only one cell can be
  hovered and every recycle path disposes the previous one.
- **Full-screen handoff**: clicking a video tile in the grid (`GridController.onPhotoClicked`) no
  longer opens the in-app slideshow — it launches the OS default player via
  `Desktop.getDesktop().open(file)` (off the FX thread), consistent with videos not needing a
  proxy-transcode step since accepted formats are already restricted to what's natively playable.
  Photo clicks are unaffected.
- No transcode/proxy step needed either way — this was already guaranteed by the "accepted
  formats" decision from the plan (H.264 video + AAC audio only, exactly what JavaFX
  `MediaPlayer`/most OS players handle natively).
- Defensive fix in `SlideshowController`: EXIF-rotation lookup (`media.photo().getOrientation()`)
  is now guarded by `media.isPhoto()`, since arrow-key navigation inside an already-open slideshow
  could in principle land on a video item (its generated thumbnail frame is shown; no crash).

## Open items still worth a decision before/while coding

- **Frame-sampling defaults** (AI pipeline, phase 5) — proposed 10/50/90% heuristic, capped count;
  exposed as a Settings-screen knob from day one rather than a hardcoded guess.
- **`hash_type` on `media_hash`** (dedupe, phase 4) — confirms photos and videos are never
  cross-compared for duplicates; worth double-checking this matches expectations before
  implementation.
- **JavaCV native library footprint** — `javacv-platform` bundles natives for every major OS/arch;
  once a target platform is settled, narrow the classifier list to avoid shipping natives nobody
  needs (see `VIDEO_DEPENDENCY_NOTES.md`).
