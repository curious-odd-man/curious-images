package com.github.curiousoddman.curious_images.domain.imports.metadata;

import java.time.LocalDateTime;

/**
 * Result of {@link VideoMetadataExtractor#extract(java.nio.file.Path)}. Mirrors {@link
 * ExtractedMetadata}, but only carries what's genuinely video-specific plus the handful of
 * cross-type fields ({@code width}/{@code height}/{@code captureDate}) that get written into the
 * shared {@code MEDIA} row — see implementation plan §1/§2.
 *
 * @param width             frame width in pixels, or {@code null} if FFprobe couldn't determine it
 * @param height            frame height in pixels, or {@code null} likewise
 * @param durationMs        total duration in milliseconds, or {@code null} if unknown
 * @param codec             the decoded video codec name as reported by FFprobe (e.g. {@code
 *                          "h264"}), lower-cased; used for the accept/reject check in {@link
 *                          VideoMetadataExtractor#extract}
 * @param audioCodec        the decoded audio codec name (e.g. {@code "aac"}), lower-cased, or
 *                          {@code null} if the file has no audio stream
 * @param frameRate         average frame rate in frames/second, or {@code null} if unknown
 * @param rotationDegrees   clockwise display rotation (0/90/180/270) taken from the container's
 *                          rotation metadata (e.g. QuickTime {@code tkhd} matrix); {@code 0} when
 *                          absent or unrecognized
 * @param captureDate       best-effort capture timestamp — container creation-date metadata if
 *                          present, otherwise filesystem mtime; never {@code null} in practice
 * @param captureDateSource which source {@code captureDate} came from
 * @param gpsLat            latitude in decimal degrees parsed from a QuickTime/ISO6709 location
 *                          tag (e.g. {@code com.apple.quicktime.location.ISO6709}), or {@code
 *                          null} if the container has none
 * @param gpsLon            longitude in decimal degrees, or {@code null} likewise
 * @param gpsAltitude       altitude in meters, or {@code null} if the tag didn't include one
 */
public record ExtractedVideoMetadata(Integer width, Integer height,
                                     Long durationMs, String codec, String audioCodec, Double frameRate,
                                     int rotationDegrees,
                                     LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                     Double gpsLat, Double gpsLon, Double gpsAltitude) {
}
