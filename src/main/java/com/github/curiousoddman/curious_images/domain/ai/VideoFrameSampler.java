package com.github.curiousoddman.curious_images.domain.ai;

import lombok.extern.slf4j.Slf4j;
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.springframework.stereotype.Component;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Samples a handful of frames from a video for the AI pipeline (faces + CLIP) — see
 * implementation plan §5. Each sampled frame is treated exactly like a photo would be by the
 * caller; this class only handles picking timestamps and decoding the frames at them.
 * <p>
 * Sampling heuristic: {@code sampleCount} frames spaced evenly across the 10%-90% span of the
 * video's duration (e.g. count=3 gives 10%/50%/90%, matching the plan's example), then capped
 * down by {@code minIntervalSeconds} so a short video doesn't get needlessly close-together
 * samples — both are Settings-screen knobs (see {@code AiConfig}/{@code AiSettingsService}), not
 * hardcoded.
 */
@Slf4j
@Component
public class VideoFrameSampler {

    /**
     * @param videoFile          absolute path to the video file
     * @param sampleCount        configured max number of frames to sample
     * @param minInterval configured minimum spacing between samples, in seconds
     * @return sampled frames in chronological order; empty if the video couldn't be opened at all
     * (never throws for one bad file — same contract as the rest of the video-decode pipeline)
     */
    public List<SampledFrame> sample(Path videoFile, int sampleCount, Duration minInterval) {
        List<SampledFrame> result = new ArrayList<>();
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(videoFile.toString())) {
            grabber.start();
            long       durationMs = grabber.getLengthInTime() / 1000L; // JavaCV reports microseconds
            List<Long> offsets    = computeOffsetsMs(durationMs, sampleCount, minInterval.toMillis());

            try (Java2DFrameConverter converter = new Java2DFrameConverter()) {
                for (long offsetMs : offsets) {
                    try {
                        grabber.setTimestamp(offsetMs * 1000L);
                        Frame frame = grabber.grabImage();
                        if (frame == null) {
                            // Some containers need a first grab to land on a real frame after a seek.
                            frame = grabber.grabImage();
                        }
                        if (frame == null) {
                            log.warn("Unable to decode a frame at {}ms in {}", offsetMs, videoFile);
                            continue;
                        }
                        BufferedImage image = converter.convert(frame);
                        if (image != null) {
                            result.add(new SampledFrame(offsetMs, image));
                        }
                    } catch (Exception e) {
                        log.warn("Failed to grab frame at {}ms from {}", offsetMs, videoFile, e);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to open {} for frame sampling", videoFile, e);
        }
        return result;
    }

    /**
     * @return distinct, chronologically-ordered offsets in milliseconds
     */
    private List<Long> computeOffsetsMs(long durationMs, int configuredCount, long minIntervalMs) {
        if (durationMs <= 0) {
            return List.of(0L);
        }

        int intervalCap = minIntervalMs > 0
                ? (int) Math.max(1, (durationMs / minIntervalMs) + 1)
                : configuredCount;
        int count = Math.max(1, Math.min(Math.max(1, configuredCount), intervalCap));

        if (count == 1) {
            return List.of(durationMs / 2);
        }

        // Evenly spaced across the 10%-90% span, e.g. count=3 -> 10%/50%/90% exactly as the plan
        // describes; count=5 -> 10/30/50/70/90%.
        LinkedHashSet<Long> offsets = new LinkedHashSet<>(count);
        for (int i = 0; i < count; i++) {
            double fraction = 0.1 + (0.8 * i / (count - 1));
            offsets.add((long) (durationMs * fraction));
        }
        return List.copyOf(offsets);
    }

    public record SampledFrame(long offsetMs, BufferedImage image) {
    }
}
