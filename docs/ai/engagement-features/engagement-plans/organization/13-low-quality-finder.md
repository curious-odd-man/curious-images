# Low-Quality Finder

## What it does
Find blurry, dark/overexposed, or eyes-closed photos, for cleanup.

## Depends on shared infra
- This feature's output (a `quality_score`/flags per media) is a **dependency of several other
  features** (Rediscover Forgotten Photos §03, Monthly Story's "30 best photos" §04, Similar
  Cleanup's "keep best" ranking §11) — build it early and treat it as shared infra in its own
  right, not a leaf feature.
- Existing face pipeline (`RetinaFaceDetector`, `DetectedFace`/`FaceLandmarks`) for "eyes closed"
  detection (landmarks likely already include eye keypoints — confirm in `FaceLandmarks.java`
  before adding new landmark detection).

## New data
- `media_quality` table (or columns on `media` if the project prefers flat schema — check existing
  convention: `ai_*_done` flags suggest columns-on-media is the established style): `blur_score`,
  `exposure_score`, `eyes_closed BOOLEAN`, `quality_score` (composite), `ai_quality_done BOOLEAN`.
  New migration `V018__media_quality.sql`.

## Backend design
- `domain/quality/BlurDetector`: variance-of-Laplacian on the decoded image (OpenCV `Imgproc` is
  already a dependency via `ClipImageEncoder`/face pipeline — reuse `org.opencv.imgproc.Imgproc`
  rather than adding a new image-processing library).
- `domain/quality/ExposureDetector`: histogram-based over/under-exposure check (also plain OpenCV,
  no ML needed).
- `domain/quality/EyesClosedDetector`: reuse `RetinaFaceDetector`'s existing facial landmarks
  (`FaceLandmarks`) if eye-openness can be derived from them (eye-aspect-ratio calculation on
  existing keypoints); only add a dedicated eye-state model if landmarks turn out to be too coarse
  for this — check `FaceLandmarks.java` before deciding either way.
- `domain/quality/QualityAssessmentJob extends BackgroundJob`: runs after import/thumbnail
  generation (same trigger points as `ThumbnailGenerationJob`), computes all three signals per
  media, writes a composite `quality_score`.

## UI
- A "Low Quality" smart-group (same pattern as Inbox/Screenshots) with bulk cleanup actions.
- No new UI is strictly required beyond that — its main value is powering the other features'
  ranking, per the dependency note above.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/RetinaFaceDetector.java`, `model/FaceLandmarks.java`, `domain/ai/DetectedFace.java`
- `src/main/java/.../domain/common/thumbnail/ThumbnailGenerationJob.java` (trigger-point pattern)
- `src/main/java/.../persistence/MediaRepository.java` (existing `ai_*_done` flag convention)
- `03-rediscover-forgotten-photos.md`, `04-monthly-story.md`, `11-similar-cleanup.md` (consumers)

**New:**
- `src/main/resources/db/migration/V018__media_quality.sql`
- `src/main/java/.../domain/quality/BlurDetector.java`
- `src/main/java/.../domain/quality/ExposureDetector.java`
- `src/main/java/.../domain/quality/EyesClosedDetector.java`
- `src/main/java/.../domain/quality/QualityAssessmentJob.java`
- Sidebar smart-group wiring for "Low Quality"
