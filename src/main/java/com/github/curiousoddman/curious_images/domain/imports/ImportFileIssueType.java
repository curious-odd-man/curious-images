package com.github.curiousoddman.curious_images.domain.imports;

/**
 * Kind of row in {@code IMPORT_JOB_FILE_ISSUE} — see {@link ImportFileIssue}. Deliberately narrow:
 * "skipped, unchanged" is NOT one of these (it's routine and stays an aggregate count only, see
 * {@code ImportStatsTracker#recordSkippedUnchanged}) — this only covers cases worth a person's
 * attention, one row per file.
 */
public enum ImportFileIssueType {
    /**
     * File was discovered but deliberately not imported — unsupported codec or unsupported
     * extension. Not an error; the reason says which.
     */
    SKIPPED,
    /**
     * File was attempted and an exception was thrown partway through.
     */
    FAILED
}
