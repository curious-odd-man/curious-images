-- Schema for user-defined "custom" albums with refinement (see
-- album-refinement-feature-spec.md). This is a deliberately separate set of tables from
-- ALBUM / ALBUM_MEDIA rather than an extension of them:
--   - ALBUM / ALBUM_MEDIA back read-only, AI-generated albums (PERSON | EVENT | LOCATION |
--     SIMILARITY), rebuilt wholesale by AlbumGenerationJob on every AiPipelineCompleteEvent.
--   - CUSTOM_ALBUM / CUSTOM_ALBUM_PHOTO back user-created albums: user-chosen name, contents,
--     and per-photo refinement state, never touched by the AI pipeline.
-- Keeping them separate means a future AI-album rebuild can never clobber user-entered state,
-- and CUSTOM_ALBUM_PHOTO can carry columns (state, scene_group_id) that make no sense on
-- ALBUM_MEDIA's read-only rows.

CREATE TABLE custom_album
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(256) NOT NULL,
    cover_photo_id BIGINT REFERENCES media (id), -- explicit user pick; NULL => fall back to
                                                  -- first Yes photo by added_at, or a placeholder
                                                  -- (see CustomAlbumRepository / spec §6)
    created_at     TIMESTAMP    NOT NULL,
    updated_at     TIMESTAMP
);

-- A cluster of visually-similar photos within one custom album (e.g. a burst of near-identical
-- shots), used to drive triage mode and the scene-grouped unrefined view. Scoped to a single
-- album: the same photo can end up in different scene groups depending which album(s) it's in.
--
-- A photo with no matching candidates still gets its own single-photo SCENE_GROUP row (spec
-- §4.4 singleton rule) rather than being left group-less, so "grouped by scene" layouts never
-- need special-case handling for ungrouped photos. CUSTOM_ALBUM_PHOTO.scene_group_id being NULL
-- therefore only ever means "not yet processed by the scene-grouping job", never "no group".
CREATE TABLE scene_group
(
    id              BIGSERIAL PRIMARY KEY,
    custom_album_id BIGINT NOT NULL REFERENCES custom_album (id) ON DELETE CASCADE
);
CREATE INDEX idx_scene_group_album ON scene_group (custom_album_id);

-- Join of Photo <-> Custom Album, carrying the per-album refinement state and scene-group
-- membership. photo_id references MEDIA directly (not a photo-only table) because MEDIA is
-- already the shared identity for photos and videos in this app; the "photos only, videos
-- skipped" rule from the feature spec is enforced at the point photos are added to an album
-- (CustomAlbumPhotoAdditionService filters to Media.isPhoto()), not via a DB-level type
-- constraint, so this table never actually holds a video's media_id.
--
-- state uses the spec's recommended numeric encoding for cheap threshold/sort queries:
--   -2 No | -1 RatherNo | 0 Unassigned | 1 RatherYes | 2 Yes
-- Default 0 (Unassigned) matches "default on add" in the spec.
CREATE TABLE custom_album_photo
(
    custom_album_id BIGINT   NOT NULL REFERENCES custom_album (id) ON DELETE CASCADE,
    photo_id        BIGINT   NOT NULL REFERENCES media (id) ON DELETE CASCADE,
    state           SMALLINT NOT NULL DEFAULT 0,
    scene_group_id  BIGINT REFERENCES scene_group (id) ON DELETE SET NULL,
    added_at        TIMESTAMP NOT NULL,
    PRIMARY KEY (custom_album_id, photo_id)
);
CREATE INDEX idx_custom_album_photo_album ON custom_album_photo (custom_album_id);
CREATE INDEX idx_custom_album_photo_scene_group ON custom_album_photo (scene_group_id);
-- Composite (not separate single-column) index: every query that filters by state also scopes
-- to one album (e.g. "Unassigned photos in this album", refined-view's Yes count for this
-- album), so a plain idx on state alone would just add write overhead with no read benefit.
CREATE INDEX idx_custom_album_photo_state ON custom_album_photo (custom_album_id, state);
