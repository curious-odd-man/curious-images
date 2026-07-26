-- Rename this file to the next Flyway version in your migrations folder (e.g. V12__...) —
-- V9999 is a placeholder since the actual migrations directory wasn't part of this export.
--
-- Single-row table: only the most recent import job's stats are ever kept. ID is always 1;
-- ImportJobStatsRepository upserts it in place rather than inserting a new row per run.
CREATE TABLE IMPORT_JOB_STATS
(
    ID                           BIGINT PRIMARY KEY,
    JOB_TYPE                     VARCHAR(32)  NOT NULL,
    ROOT_PATHS_JSON              VARCHAR      NOT NULL,
    STATUS                       VARCHAR(32)  NOT NULL,
    STARTED_AT                   TIMESTAMP    NOT NULL,
    FINISHED_AT                  TIMESTAMP,
    PHOTO_IMPORTED_COUNT         BIGINT       NOT NULL,
    PHOTO_UPDATED_COUNT          BIGINT       NOT NULL,
    VIDEO_IMPORTED_COUNT         BIGINT       NOT NULL,
    VIDEO_UPDATED_COUNT          BIGINT       NOT NULL,
    BYTES_IMPORTED               BIGINT       NOT NULL,
    SKIPPED_UNCHANGED_COUNT      BIGINT       NOT NULL,
    UNSUPPORTED_CODEC_COUNT      BIGINT       NOT NULL,
    UNSUPPORTED_EXTENSION_COUNT  BIGINT       NOT NULL,
    FAILURES_JSON                VARCHAR      NOT NULL
);
