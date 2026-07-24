package com.github.curiousoddman.curious_images.domain.dedupe;

/**
 * How {@code media_hash.content_hash} was computed — see the column comment in
 * V005__duplicate_schema.sql and implementation plan §6. Comparisons must never cross types: a
 * {@link #PIXEL} hash and a {@link #FILE} hash are never considered a match even if the hex
 * strings happened to collide.
 */
public enum HashType {
    /**
     * Decoded pixel bytes (photos). See {@code PixelHasher}.
     */
    PIXEL,
    /**
     * Plain SHA-256 of the raw file bytes (videos). Exact-duplicate only for v1 — a
     * re-encoded/trimmed copy of the same video is not detected as a duplicate. See
     * {@code FileHasher}.
     */
    FILE
}
