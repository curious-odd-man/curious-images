package com.github.curiousoddman.curious_images.domain.imports;

/**
 * Result of importing a single file, used both to drive the imported/skipped counters in the
 * terminal {@code ENDED} event's description and to assert rescan idempotency in tests (see
 * implementation plan §12, §14).
 */
public enum ImportOutcome {
    /**
     * No PHOTO row existed for this absolute path yet — a new row (and thumbnail, if any) was created.
     */
    IMPORTED,
    /**
     * A PHOTO row existed but its file size changed — metadata and thumbnail were regenerated.
     */
    UPDATED,
    /**
     * A PHOTO row existed with the same file size — only {@code last_seen_at} was touched.
     */
    SKIPPED_UNCHANGED,
    /**
     * A video file was discovered but its video/audio codec isn't H.264/AAC — rejected at import
     * time rather than stored, since the hover-preview/full-screen playback promise can only be
     * kept for what JavaFX {@code MediaPlayer} can natively decode. See implementation plan
     * "Accepted formats" decision and {@code VideoMetadataExtractor}.
     */
    REJECTED_UNSUPPORTED_CODEC
}
