# Rediscover Forgotten Photos

## What it does
Surface "hidden gems": photos that are old, visually decent, never favourited/exported, not
duplicates, and rarely viewed.

## Depends on shared infra
- §B (emits `insight_card` type `HIDDEN_GEM`)
- §E (`ClipEmbeddingRepository`/`ClipVectorIndex` for the "visually good" / diversity filter,
  and dedupe tables to exclude anything already flagged as a duplicate)

## New data
- The app currently has no concept of "favourited" or "view count". Both need adding:
  - `media.favorited_at TIMESTAMP NULL` (or a small `media_favorite` table if multi-user support is
    ever wanted — a single nullable column is enough for a single-user desktop app) — new migration
    `V014__media_engagement.sql`.
  - `media.view_count INTEGER NOT NULL DEFAULT 0`, incremented whenever a photo is opened full-
    screen/in the grid detail view (hook into `RightPanelController`/wherever "open photo" already
    fires, e.g. alongside `ThumbnailReadyEventListener`/grid selection handling).
  - "Never exported": check whether an export/share feature exists — grep didn't find one in this
    codebase; if there is genuinely no export feature yet, drop this criterion (log it as a
    follow-up) rather than inventing an unrelated feature to support it.
- "Visually good" needs a quality heuristic. Reuse whatever gets built for **Low-Quality Finder**
  (feature 13) — a `media_quality_score` column/table populated by that feature — and filter
  candidates here to *above* whatever threshold that feature uses for "bad". Do not build a second
  quality classifier.

## Backend design
- `domain/insights/HiddenGemsGenerator implements InsightGenerator`: candidate query =
  `media WHERE favorited_at IS NULL AND view_count < N AND capture_date < now() - 2 years AND id
  NOT IN (duplicate_group_member) AND quality_score > threshold`, then diversify the result using
  CLIP embeddings (greedy farthest-point sampling so the shown set isn't 20 near-identical shots of
  the same subject) before turning the top results into cards.
- Increment `view_count` via `MediaRepository.incrementViewCount(id)` called from the existing
  "open full view" interaction point in `GridCellController`/`RightPanelController`.

## UI
- Rendered as a "Hidden Gems" section/card in the Discover feed (§A), each card opening the normal
  full photo view.
- A small "favourite" toggle (heart icon) needs to be added somewhere reachable (grid context menu
  `GridContextMenu`, or the right panel) purely to populate `favorited_at` — check whether the user
  wants this as a general feature or Rediscover-only before adding UI for it broadly.

## Files to hand to the implementing AI
**Existing (context):**
- `src/main/java/.../persistence/MediaRepository.java`
- `src/main/java/.../persistence/DuplicateGroupRepository.java`
- `src/main/java/.../ui/util/GridContextMenu.java`
- `src/main/java/.../ui/controller/custom/RightPanelController.java`
- `00-shared-infrastructure.md` (§B, §E)
- `13-low-quality-finder.md` (quality score dependency)

**New:**
- `src/main/resources/db/migration/V014__media_engagement.sql`
- `src/main/java/.../domain/insights/HiddenGemsGenerator.java`
- Additions to `MediaRepository` (`incrementViewCount`, `setFavorited`, candidate query)
- Optional: favourite-toggle UI wiring in `GridContextMenu.java`/context menu FXML
