package com.github.curiousoddman.curious_images.domain.imports.metadata;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Probes a video file's duration, codec, frame rate, container rotation, creation-date, and GPS
 * (when present) via FFprobe/FFmpeg (through the JavaCV wrapper) — the video counterpart to
 * {@link PhotoMetadataExtractor}. See implementation plan §2/§9 for the rationale.
 * <p>
 * Also enforces the "what JavaFX {@code MediaPlayer} can natively decode" codec restriction
 * (H.264 video + AAC audio only, see implementation plan "Accepted formats" decision) by throwing
 * {@link UnsupportedVideoCodecException} — this is what keeps the hover-preview/full-screen
 * playback promise honest, since videos never go through a proxy-transcode step.
 */
@Slf4j
@Component
public class VideoMetadataExtractor {

    private static final Set<String> ACCEPTED_VIDEO_CODECS = Set.of("h264", "avc", "avc1");
    private static final Set<String> ACCEPTED_AUDIO_CODECS = Set.of("aac");

    /**
     * QuickTime/ISO 6709 location tag, e.g. {@code +37.3230-122.0322+000.000/} — sign-prefixed
     * lat/lon (and optional altitude), no separators. Groups: lat, lon, altitude (optional).
     */
    private static final Pattern ISO6709_PATTERN =
            Pattern.compile("([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)([+-]\\d+(?:\\.\\d+)?)?/?");

    private static final DateTimeFormatter CREATION_TIME_FORMAT = DateTimeFormatter.ISO_DATE_TIME;

    /**
     * @param file absolute path to the video file on disk
     * @return probed metadata
     * @throws UnsupportedVideoCodecException if the file's video/audio codec isn't H.264/AAC —
     *                                        caller should reject the import for this file rather
     *                                        than store a media row that can't be hover-played
     * @throws IOException                    if FFmpeg couldn't even open/parse the container
     */
    public ExtractedVideoMetadata extract(Path file) throws UnsupportedVideoCodecException, IOException {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(file.toString());
        try {
            grabber.start();

            String videoCodec = normalizeCodec(grabber.getVideoCodecName());
            String audioCodec = normalizeCodec(grabber.getAudioCodecName());

            validateCodecs(file, videoCodec, audioCodec);

            Integer width  = grabber.getImageWidth() > 0 ? grabber.getImageWidth() : null;
            Integer height = grabber.getImageHeight() > 0 ? grabber.getImageHeight() : null;
            Long    durationMs = grabber.getLengthInTime() > 0
                    ? grabber.getLengthInTime() / 1000L // JavaCV reports microseconds
                    : null;
            Double frameRate = grabber.getVideoFrameRate() > 0 ? grabber.getVideoFrameRate() : null;
            int    rotation  = extractRotationDegrees(grabber);

            Map<String, String> metadata = grabber.getMetadata();
            CaptureDateAndSource captureDateAndSource = extractCaptureDate(metadata, file);
            Gps                  gps                  = extractGps(metadata);

            return new ExtractedVideoMetadata(width, height, durationMs, videoCodec, audioCodec, frameRate,
                    rotation, captureDateAndSource.date(), captureDateAndSource.source(),
                    gps.lat(), gps.lon(), gps.altitude());
        } catch (org.bytedeco.javacv.FrameGrabber.Exception e) {
            throw new IOException("FFmpeg could not open/parse " + file, e);
        } finally {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception e) {
                log.debug("Failed to release FFmpeg grabber for {}", file, e);
            }
        }
    }

    private void validateCodecs(Path file, String videoCodec, String audioCodec) throws UnsupportedVideoCodecException {
        if (videoCodec == null || !ACCEPTED_VIDEO_CODECS.contains(videoCodec)) {
            throw new UnsupportedVideoCodecException(
                    "Unsupported video codec '%s' in %s — only H.264 is supported for hover/full-screen playback"
                            .formatted(videoCodec, file));
        }
        // A video-only file (no audio stream at all) is fine — only reject a *present* but
        // non-AAC audio codec.
        if (audioCodec != null && !ACCEPTED_AUDIO_CODECS.contains(audioCodec)) {
            throw new UnsupportedVideoCodecException(
                    "Unsupported audio codec '%s' in %s — only AAC is supported for hover/full-screen playback"
                            .formatted(audioCodec, file));
        }
    }

    private static String normalizeCodec(String codecName) {
        if (codecName == null || codecName.isBlank()) {
            return null;
        }
        return codecName.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * QuickTime encodes display rotation as a {@code rotate} tag (degrees clockwise) on the video
     * stream, derived from the {@code tkhd} display matrix. Not every container exposes it via
     * JavaCV's per-stream metadata accessor, so absence just means "no rotation" (0), same
     * fallback as {@code PhotoMetadataExtractor}'s orientation handling.
     */
    private int extractRotationDegrees(FFmpegFrameGrabber grabber) {
        String rotate = grabber.getVideoMetadata("rotate");
        if (rotate == null || rotate.isBlank()) {
            return 0;
        }
        try {
            int normalized = ((Integer.parseInt(rotate.strip()) % 360) + 360) % 360;
            return switch (normalized) {
                case 90, 180, 270 -> normalized;
                default -> 0;
            };
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private record CaptureDateAndSource(LocalDateTime date, CaptureDateSource source) {
    }

    /**
     * Priority order: container creation-date tag (MP4 {@code creation_time}, or the QuickTime
     * equivalent JavaCV surfaces under the same key), then filesystem mtime — mirrors
     * {@link PhotoMetadataExtractor}'s EXIF-then-filesystem fallback.
     */
    private CaptureDateAndSource extractCaptureDate(Map<String, String> metadata, Path file) {
        String creationTime = metadata.get("creation_time");
        if (creationTime != null && !creationTime.isBlank()) {
            try {
                OffsetDateTime parsed = OffsetDateTime.parse(creationTime.strip(), CREATION_TIME_FORMAT);
                return new CaptureDateAndSource(
                        parsed.atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime(),
                        CaptureDateSource.CONTAINER_METADATA);
            } catch (Exception e) {
                log.warn("Could not parse video creation_time '{}' for {}", creationTime, file, e);
            }
        }
        return new CaptureDateAndSource(fileSystemDate(file), CaptureDateSource.FILESYSTEM);
    }

    private record Gps(Double lat, Double lon, Double altitude) {
        private static final Gps EMPTY = new Gps(null, null, null);
    }

    /**
     * Parses the QuickTime {@code com.apple.quicktime.location.ISO6709} tag (or the plain
     * {@code location} key some encoders use instead), e.g. {@code +37.3230-122.0322+000.000/}.
     */
    private Gps extractGps(Map<String, String> metadata) {
        String iso6709 = metadata.get("com.apple.quicktime.location.ISO6709");
        if (iso6709 == null || iso6709.isBlank()) {
            iso6709 = metadata.get("location");
        }
        if (iso6709 == null || iso6709.isBlank()) {
            return Gps.EMPTY;
        }
        Matcher matcher = ISO6709_PATTERN.matcher(iso6709.strip());
        if (!matcher.find()) {
            return Gps.EMPTY;
        }
        try {
            double       lat      = Double.parseDouble(matcher.group(1));
            double       lon      = Double.parseDouble(matcher.group(2));
            String       altGroup = matcher.group(3);
            Double       altitude = altGroup == null ? null : Double.parseDouble(altGroup);
            return new Gps(lat, lon, altitude);
        } catch (NumberFormatException e) {
            return Gps.EMPTY;
        }
    }

    private LocalDateTime fileSystemDate(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), ZoneId.systemDefault());
        } catch (IOException e) {
            log.warn("Could not read filesystem modified time for {}", file, e);
            return null;
        }
    }
}
