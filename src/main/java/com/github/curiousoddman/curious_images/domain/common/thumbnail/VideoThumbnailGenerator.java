package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Extracts a single representative frame from a video file, for feeding into the same
 * {@link ThumbnailGenerator} resize/cache pipeline photos use — see implementation plan §3.
 * Deliberately does not decode the whole file: seeks directly to the target timestamp.
 */
@Slf4j
@Component
public class VideoThumbnailGenerator {

    /**
     * How far into the video to grab the thumbnail frame from, as a fraction of total duration —
     * avoids a black/fade-in first frame while still being cheap to seek to.
     */
    private static final double THUMBNAIL_POSITION_FRACTION = 0.10;
    private static final long   MIN_THUMBNAIL_POSITION_MS   = 1_000L;

    /**
     * @return the decoded frame as a {@link BufferedImage}, or empty if the file couldn't be
     * opened/decoded — never throws for a single bad file (same contract as
     * {@code ImageUtils.imageOrCr2Preview}).
     */
    public Optional<BufferedImage> extractFrame(Path videoFile) {
        FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile.toString());
        try {
            grabber.start();

            long durationMs = grabber.getLengthInTime() / 1000L;
            long targetMs = durationMs > 0
                    ? Math.min(durationMs - 1, Math.max(MIN_THUMBNAIL_POSITION_MS, (long) (durationMs * THUMBNAIL_POSITION_FRACTION)))
                    : MIN_THUMBNAIL_POSITION_MS;

            grabber.setTimestamp(targetMs * 1000L); // grabber timestamps are in microseconds

            Frame frame = grabber.grabImage();
            if (frame == null) {
                // Some containers need a first grab to land on a real frame after a seek.
                frame = grabber.grabImage();
            }
            if (frame == null) {
                log.warn("Unable to decode any frame from {}", videoFile);
                return Optional.empty();
            }

            Java2DFrameConverter converter = new Java2DFrameConverter();
            BufferedImage        image     = converter.convert(frame);
            return Optional.ofNullable(image);
        } catch (Exception e) {
            log.warn("Failed to extract thumbnail frame from {}", videoFile, e);
            return Optional.empty();
        } finally {
            try {
                grabber.stop();
                grabber.release();
            } catch (Exception e) {
                log.debug("Failed to release FFmpeg grabber for {}", videoFile, e);
            }
        }
    }
}
