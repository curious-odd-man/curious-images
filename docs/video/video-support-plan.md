# Video Support — Implementation Plan (v2)

## Status

| Phase                                                                                 | Status        |
|---------------------------------------------------------------------------------------|---------------|
| 1. Schema migration + jOOQ regen + app compiles/runs (photos-only behavior unchanged) | 🟢 Completed  |
| 2. Video import + browse (extension filtering, metadata, thumbnails, plain grid)      | 🟢 Completed  |
| 3. Hover-preview playback                                                             | 🟢 Completed  |
| 4. Duplicate detection (file-hash, `media_hash`)                                      | 🟢 Completed  |
| 5. AI pipeline (frame sampling + face/CLIP + clustering)                              | 🟢 Completed  |
| 6. Albums + unified search, `MediaItem` abstraction                                   | 🟢 Completed  |

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

## Phase 4 — what landed

- New `FileHasher` (SHA-256, streamed) alongside the existing `PixelHasher` — a small shared
  `MediaExtensions` util (also now used by `ImportJob`, fixing a latent bug where `ImportJob`
  referenced an undefined `union()` helper) decides which hasher a given extension needs.
- `HashType` enum (`PIXEL`/`FILE`) threads through `MediaHashRepository.upsertQuery`,
  `DuplicateDetectionJob`'s grouping key, and the cached-hash reuse path — groups are now scoped
  within the same hash type as an explicit safety rule, not just an accident of disjoint
  extensions.
- `DuplicateDetectionJob` branches per-file: photos hash via decoded pixels (unchanged), videos
  hash via raw file bytes; both share the same cache/resume/threading machinery.
- `DuplicateResolutionService` (and `PhotoFailure`) generalized from `MediaPhotoRecord` to `Media`
  — trash-then-delete only ever needed id/absolute path, so this now actually works for duplicate
  video groups instead of crashing.
- Fixed two more unconditional `.photo()` casts that duplicate video groups would have hit:
  `DuplicatesController.resolveActivePane` and `FolderDuplicatesController.createPhotoTile`.

## Phase 5 — what landed

- `VideoFrameSampler`: samples a handful of frames per video via JavaCV, spaced evenly across the
  10%-90% span of duration (count=3 → 10/50/90%, matching the plan's example), capped down by a
  minimum-interval-seconds setting for short videos. Both knobs are Settings-screen spinners
  ("Video frame sampling" section), live-applied via `AiSettingsService`/`AiConfig`, same pattern
  as the existing performance/album-tuning settings.
- `AiPipelineJob`'s face/CLIP stage now branches on media type: a photo still processes exactly
  one frame; a video samples N frames and runs the *existing* `RetinaFaceDetector`/`ArcFaceEncoder`/
  `ClipImageEncoder` on each — no new AI models, just a loop. Every resulting `face`/
  `clip_embedding` row records `frame_offset_ms`.
- `face` and `clip_embedding` schemas already had `frame_offset_ms` from Phase 1 — no migration
  needed this phase.
- `ClipEmbeddingRepository`/`FaceRepository` updated to key on `(media_id, frame_offset_ms)` /
  accept a frame offset; `FaceThumbnailsRepository` disambiguates thumbnail filenames by frame
  offset so two frames with a face at the same bbox don't collide.
- `ClipVectorIndex` reworked to support multiple Lucene docs per media (one per sampled frame):
  each doc gets a synthetic `row_id` key so upserting one frame never clobbers another; `photo_id`
  stays indexed so per-media bulk delete and search-result mapping still work in one call each.
  `search()` now dedups multiple frame-hits from the same video down to one result, best-scoring
  frame wins (a first slice of implementation plan §7's "collapsed into one search result").
  `FaceVectorIndex` needed **no changes** — it was already keyed by face id, not media id.
- `PersonClusteringService` audited end-to-end: it only ever operates on `FaceRecord.getId()` and
  never looks at which media a face came from, so it already handles "same media, multiple
  embeddings" correctly with zero code changes — confirms the plan's own expectation.
- Found and fixed a real bug this phase would have introduced: `AlbumGenerationJob`'s similarity-
  clustering read every `clip_embedding` row as one point, so a single video with 3 sampled frames
  would've clustered (and tried to insert into `album_photo`) as if it were 3 separate photos. Now
  deduplicates to one representative embedding per media before clustering.
- Generalized `FaceClipProcessingFailed` from `MediaPhotoRecord` to `Media` so a video's
  processing failures surface correctly too.

## Phase 6 — what landed

- **Event/location albums now include videos.** Found and fixed a real pre-existing bug in the
  process: `MediaRepository.findOrderedByCaptureDate`/`findAllWithGps` queried the shared `MEDIA`
  table directly but force-mapped every row into a `MediaPhotoRecord` via jOOQ's `fetchInto` —
  this didn't throw (jOOQ just leaves unmatched POJO fields null), but silently mislabeled every
  video row as a photo and dropped genuine photo-specific columns too. Both now return properly
  merged `List<Media>`, and `AlbumGenerationJob`'s event/location clustering works on `Media`
  throughout.
- **Person pages now render mixed photo+video grids.** `PersonDetailController` generalized from
  `MediaPhotoRecord` to `Media` end-to-end (photo lookup, year-grouping, age-album tree, date
  comparator). This also fixed a latent compile bug from Phase 2: `onAgeAlbumSelected` was still
  passing `List<MediaPhotoRecord>` into `PhotoGridManager.createData`, which Phase 2 had already
  changed to take `List<Media>` — this code path would not have compiled until now.
- **Album/undated browse pages now render mixed grids.** `PhotoGridManager.loadPhotosForAlbum`
  and `loadPhotosUndated` switched from the photo-only `findById`/`findByNullCaptureDate` to the
  generic `findMediaById`/new `findMediaByNullCaptureDate`, consistent with folder/timeline
  browsing (already mixed since Phase 2).
- **Person albums and similarity albums already included videos with zero changes needed** —
  audited `PersonService.getPersonPhotoIds` (already keyed by `FaceRecord.getMediaId()`, type
  agnostic) and confirmed the Phase 5 similarity-album dedup fix already covers it.
- **Unified search**: `@person`/`#tag` filters were already type-agnostic (keyed by media id
  through `FACE`/`MEDIA_TAG`), and Phase 5's `ClipVectorIndex` rework already made semantic search
  span photos and videos with multi-frame-hit collapsing. Added
  `ClipVectorIndex.searchWithFrameOffsets`/`SearchService.semanticSearchWithFrames` so a hit's
  best-matching frame offset is available — not yet wired into the grid as a custom per-result
  thumbnail (that's a separate, larger UI feature: rendering one arbitrary frame bypassing the
  normal per-media thumbnail cache), but the data is there for a future "jump to this moment"
  affordance without another index-format change.
- **`MediaItem` abstraction**: already satisfied by the existing `Media` class (an `Either`-backed
  photo/video union with `isPhoto()`/`isVideo()` and generic accessors), which grid, album,
  search, dedupe, and AI-pipeline code already use uniformly — no parallel `PhotoItem`/`VideoItem`
  type was introduced, since one already existed doing this job since Phase 2. Documented this
  directly on the class.

## Status: all 6 phases of the original plan are now implemented.

## Open items still worth a decision before/while coding

- **Best-matching-frame thumbnail for video search results** (search polish, phase 6 follow-up) —
  `ClipVectorIndex.searchWithFrameOffsets` now surfaces which frame matched, but the grid still
  shows each video's normal cached thumbnail rather than that specific frame; rendering an
  arbitrary matched frame one-off would need a parallel image-loading path bypassing the per-media
  thumbnail cache.
- **`hash_type` on `media_hash`** (dedupe, phase 4) — confirms photos and videos are never
  cross-compared for duplicates; worth double-checking this matches expectations before
  implementation.
- **JavaCV native library footprint** — `javacv-platform` bundles natives for every major OS/arch;
  once a target platform is settled, narrow the classifier list to avoid shipping natives nobody
  needs (see `VIDEO_DEPENDENCY_NOTES.md`).
