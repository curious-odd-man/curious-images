package com.github.curiousoddman.curious_images.domain.dedupe.hasher;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.util.HexFormat;

import static com.github.curiousoddman.curious_images.domain.dedupe.hasher.PixelHasher.sha256;

/**
 * Video counterpart to {@link PixelHasher}: plain SHA-256 of the raw file bytes, no decoding
 * involved — see implementation plan §6 ("exact-duplicate only for v1... a re-encoded/trimmed
 * copy of the same video will not be detected as a duplicate", an accepted limitation).
 * <p>
 * Streamed rather than read fully into memory, since video files can be large — this is I/O-bound
 * and the SHA-256 update cost scales with file size, but never holds more than a small buffer.
 */
@Slf4j
@Component
public class FileHasher {

    private static final int BUFFER_SIZE = 64 * 1024;

    /**
     * @return a result with a non-null {@code fileHash}, or one with a {@code null} fileHash if
     * the file couldn't be read at all (missing, permissions, I/O error mid-read) — same
     * "skip this media for this run, don't fail the whole job" policy as {@link PixelHasher}.
     */
    public MediaHashResult hash(long mediaId, Path file, String extension, long fileSize) {
        try {
            String hash = hashFile(file);
            return new MediaHashResult(mediaId, extension, fileSize, file.toString(), hash);
        } catch (IOException e) {
            log.warn("Failed to hash video file {}", file, e);
            return new MediaHashResult(mediaId, extension, fileSize, file.toString(), null);
        }
    }

    private String hashFile(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            //noinspection StatementWithEmptyBody
            while (digestIn.read(buffer) != -1) {
                // reading drives the digest via DigestInputStream; nothing else to do per-chunk
            }
        }
        return HexFormat.of()
                        .formatHex(digest.digest());
    }

    public record MediaHashResult(long mediaId, String extension, long fileSize, String absolutePath,
                                  String fileHash) {
    }
}
