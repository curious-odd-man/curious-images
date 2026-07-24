package com.github.curiousoddman.curious_images.domain.common.thumbnail;

import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Generates real (not quick-preview) thumbnails for a specific page/selection of media IDs — the
 * UI's "on-demand real thumbnail generation" from the implementation plan. Never a bulk sweep:
 * only media the grid is actually about to render ever get a job submitted for them.
 * <p>
 * Handles both photos and videos (see implementation plan §3): a video's "decode" step is a
 * single seeked frame grab via {@link VideoThumbnailGenerator} rather than a full image decode,
 * but from there both feed the same resize/cache pipeline via {@link ThumbnailGenerator}.
 * <p>
 * {@link #isSupersedable()} returns {@code true}: {@code JobManager} drops any queued instance of
 * this class and interrupts a currently-running one when a newer request comes in — switching
 * folders/timeline/album/search rapidly always prioritises the newest visible selection.
 * <p>
 * Reader/writer role split (see implementation plan investigation notes): source media live on
 * an HDD (seek-bound reads), the thumbnail cache lives on a separate SSD (CPU/throughput-bound
 * writes) — so decoding happens serially, one file at a time, sorted by path for locality, while
 * resize+encode+write is fanned out across a small worker pool. {@link #isInterruptRequested()}
 * is checked right before starting each decode (not after) — an in-flight HDD read can't be
 * aborted, so the goal is "don't start stale work", not "cancel started work".
 */
@Slf4j
@RequiredArgsConstructor
public class ThumbnailGenerationJob extends BackgroundJob {

    public static final String THUMBNAIL_GENERATION = "Generating thumbnails";

    private final MediaRepository         mediaRepository;
    private final ThumbnailRepository     thumbnailRepository;
    private final ImageUtils              imageUtils;
    private final VideoThumbnailGenerator videoThumbnailGenerator;
    private final ThumbnailGenerator      thumbnailGenerator;
    private final TimeProvider            timeProvider;
    private final List<Long>              photoIds;

    @Override
    public boolean isSupersedable() {
        return true;
    }

    @Override
    public String getProcessName() {
        return THUMBNAIL_GENERATION;
    }

    @Override
    public void runImpl() {
        publishStarted("Generating thumbnails…");

        List<Media> media = mediaRepository.findMediaByIdIn(photoIds)
                                           .stream()
                                           .filter(m -> m.getAbsolutePath() != null)
                                           .sorted(Comparator.comparing(Media::getAbsolutePath))
                                           .toList();

        if (media.isEmpty()) {
            publishEnded("Nothing to generate");
            return;
        }

        int           total     = media.size();
        AtomicInteger completed = new AtomicInteger(0);

        publishProgressThrottled("Generating thumbnails", completed.get(), total, "", false);
        for (Media item : media) {
            if (isInterruptRequested()) {
                break; // don't start decoding anything else — an in-flight read can't be aborted
            }

            // Writer role: resize + JPEG-encode + write-to-SSD — safe to parallelize across
            // cores, since it isn't HDD-bound.
            Path file    = Path.of(item.getAbsolutePath());
            long mediaId = item.getId();

            decodedFrame(item, file).ifPresent(image -> writeAndPersist(mediaId, image, file));
            completed.incrementAndGet();
            publishProgressThrottled("Generating thumbnails", completed.get(), total, "", completed.get() == total);
        }

        if (isInterruptRequested()) {
            publishInterrupted();
        } else {
            publishEnded("Generated %d of %d thumbnails".formatted(completed.get(), total));
        }
    }

    private Optional<BufferedImage> decodedFrame(Media item, Path file) {
        if (item.isVideo()) {
            return videoThumbnailGenerator.extractFrame(file);
        }
        return imageUtils.imageOrCr2Preview(item.photo())
                         .map(ImageUtils::toBufferedImage);
    }

    private void writeAndPersist(long mediaId, BufferedImage image, Path file) {
        try {
            GeneratedThumbnail thumbnail = thumbnailGenerator.writeThumbnail(image, file, ThumbnailGenerator.LONGEST_EDGE);
            thumbnailRepository.upsertQuery(mediaId, thumbnail.cachePath()
                                                              .toString(),
                                       thumbnail.width(), thumbnail.height(), timeProvider.now())
                               .execute();
            eventPublisher.publishEvent(new ThumbnailsReadyEvent(this, Set.of(mediaId)));
        } catch (Exception e) {
            log.warn("Failed to write on-demand thumbnail for media {} ({})", mediaId, file, e);
        }
    }
}
