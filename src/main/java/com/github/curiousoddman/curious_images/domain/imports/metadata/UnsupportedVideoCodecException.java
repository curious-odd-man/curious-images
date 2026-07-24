package com.github.curiousoddman.curious_images.domain.imports.metadata;

/**
 * Thrown by {@link VideoMetadataExtractor#extract(java.nio.file.Path)} when a video's decoded
 * video or audio codec isn't H.264/AAC — the only pair JavaFX {@code MediaPlayer} can natively
 * decode (see implementation plan "Accepted formats" decision). Caught by {@code ImportJob},
 * which reports the file as skipped with a clear reason rather than importing a media row that
 * the hover-preview/full-screen playback promise couldn't actually honor.
 */
public class UnsupportedVideoCodecException extends Exception {
    public UnsupportedVideoCodecException(String message) {
        super(message);
    }
}
