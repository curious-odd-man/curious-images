# Screenshot Cleanup

## What it does
Detect screenshots and surface them for bulk cleanup.

## Depends on shared infra
- §E (CLIP) as the detection signal, and the existing import metadata pipeline for a cheap
  pre-filter.

## New data
- `media.is_screenshot BOOLEAN` (nullable, computed once) — new migration
  `V017__screenshot_flag.sql`, populated by a background job similar in spirit to face/CLIP
  processing flags already on `media` (`ai_face_detect_done` etc. — follow that same
  done/pending-flag convention for `ai_screenshot_detect_done`).

## Backend design
- Cheap heuristics first (near-zero cost, run during import in
  `domain/imports/metadata/PhotoMetadataExtractor`): common screenshot resolutions matching the
  device's known screen sizes, absence of camera EXIF (`camera_make`/`camera_model` both null),
  filename patterns (`Screenshot_*`, `Screen Shot *`) — these alone catch the large majority of
  cases with no ML needed.
- For the remainder, use `ClipTextEncoder` embeddings for prompts like "a screenshot of a phone
  screen" / "a screenshot of a website" compared against each photo's existing CLIP embedding
  (same zero-shot-classification-via-text-embedding trick as feature 08's sunset/sunrise
  detection — reuse the same small utility method rather than writing it twice: a shared
  `domain/ai/ClipZeroShotClassifier.classify(embedding, List<String> candidateLabels)` used by both
  features).
- `domain/ai/ScreenshotDetectionJob extends BackgroundJob`: runs after CLIP embedding is done for a
  batch, applies heuristics then CLIP classification, writes `is_screenshot`.

## UI
- A dedicated "Screenshots" smart-group in the sidebar/tree (same pattern as Inbox, feature 09),
  opening the standard grid filtered to `is_screenshot = true`, with bulk-select/delete actions
  (reusing whatever bulk delete exists elsewhere in `GridContextMenu`).

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/imports/metadata/PhotoMetadataExtractor.java`
- `src/main/java/.../domain/ai/ClipTextEncoder.java`, `ClipImageEncoder.java`
- `src/main/java/.../persistence/MediaRepository.java` (existing `ai_*_done` flag conventions)
- `src/main/java/.../ui/util/GridContextMenu.java`
- `08-hidden-patterns.md` (shared zero-shot classifier utility)

**New:**
- `src/main/resources/db/migration/V017__screenshot_flag.sql`
- `src/main/java/.../domain/ai/ClipZeroShotClassifier.java` (shared with feature 08)
- `src/main/java/.../domain/ai/ScreenshotDetectionJob.java`
- Sidebar smart-group wiring (`TreeManager`/`LibraryTreeNode`) for "Screenshots"
