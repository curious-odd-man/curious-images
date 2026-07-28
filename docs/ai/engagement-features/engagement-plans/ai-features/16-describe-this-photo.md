# Describe This Photo

## What it does
A short natural-language caption per photo (via a "tiny local VLM"), useful for search and for
display.

## Depends on shared infra
- §C entirely — this feature *is* `OllamaVisionService`, built exactly as specified in the shared
  doc (default model: `moondream` for library-scale batch captioning).
- Feeds into **Semantic Search History** (feature 17) and **Ask Your Archive** (feature 18): the
  generated caption text should be indexed into Lucene alongside/adjacent to the CLIP vector
  fields, giving keyword-style recall CLIP alone doesn't provide, and giving the LLM in feature 18
  plain-text context to reason over instead of raw embeddings.

## New data
- `media.caption TEXT NULL`, `media.ai_caption_done BOOLEAN NOT NULL DEFAULT false` (same flag
  convention as `ai_clip_embed_done` etc.) — migration `V020__media_caption.sql`.

## Backend design
- `domain/ai/PhotoDescriptionJob extends BackgroundJob`: iterates media where
  `ai_caption_done = false`, reads the thumbnail/full image bytes (reuse
  `ThumbnailCachePaths`/`ThumbnailGenerator` — captioning a resized thumbnail is enough and much
  cheaper than the full-resolution file), calls `OllamaVisionService.describe(imageBytes, prompt)`
  with a fixed prompt like "Describe this photo in one concise sentence," writes `caption`, sets
  the done flag.
- Wire into the same pipeline sequencing as face/CLIP jobs — likely queued after
  `ai_clip_embed_done` becomes true (mirrors how the rest of the AI pipeline stages progress) via
  whatever currently triggers the next pipeline stage (check `AiPipelineJob`/`AiPipelineCompleteEvent`
  for the existing stage-chaining pattern before adding a new one).
- Because this is a batch job over potentially thousands of images and Ollama vision calls are
  comparatively slow even with `moondream`, run it as a low-priority/background-only job (does not
  block import completion) and make it resumable (already true by construction, since it's driven
  off a boolean flag column like every other AI stage here).

## UI
- Show the caption in `RightPanelController`'s detail view for the selected photo.
- Feeds the search index (feature 17) rather than needing its own screen.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../domain/ai/AiPipelineJob.java` (existing pipeline stage-chaining pattern)
- `src/main/java/.../domain/common/thumbnail/ThumbnailCachePaths.java`, `ThumbnailGenerator.java`
- `src/main/java/.../persistence/MediaRepository.java` (existing `ai_*_done` flag convention)
- `src/main/java/.../ui/controller/custom/RightPanelController.java`, `resources/fxml/right_panel.fxml`
- `00-shared-infrastructure.md` (§C)

**New:**
- `src/main/resources/db/migration/V020__media_caption.sql`
- `src/main/java/.../domain/ollama/OllamaClient.java`, `OllamaConfig.java`,
  `OllamaTextService.java`, `OllamaVisionService.java` (shared module from §C — build once here,
  reused by features 14/15/17/18/23)
- `src/main/java/.../domain/ai/PhotoDescriptionJob.java`
- Settings additions for Ollama config (`settings.fxml`/`SettingsController.java`)
- Modifications to `RightPanelController.java`/`right_panel.fxml` to display `caption`
