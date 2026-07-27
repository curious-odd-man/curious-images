package com.github.curiousoddman.curious_images.domain.common;

import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository.EditType;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository.Field;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * Backs the metadata-edit UI's capture-date correction (single + bulk). Unlike
 * {@link MediaRotationService}, this never resets AI fields — a capture-date change doesn't
 * affect pixel content, faces, tags, or embeddings — and it applies uniformly to photo and video
 * since {@code CAPTURE_DATE} lives on the shared {@code MEDIA} table.
 * <p>
 * Bulk edits only support setting the same absolute date/time on every selected media (per the
 * implementation plan's bulk-capture-date decision) — there's no delta/shift variant.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataEditService {

    private final DSLContext                  dsl;
    private final MediaRepository             mediaRepository;
    private final MediaMetadataEditRepository metadataEditRepository;
    private final TimeProvider                timeProvider;

    public void setCaptureDate(long mediaId, LocalDateTime newCaptureDate) {
        Media media = mediaRepository.findMediaById(mediaId);
        if (media == null) {
            log.warn("edit capture date: media {} no longer exists", mediaId);
            return;
        }
        applySingle(mediaId, media.getCaptureDate(), newCaptureDate);
    }

    public void setCaptureDateBulk(Collection<Long> mediaIds, LocalDateTime newCaptureDate) {
        for (Long mediaId : mediaIds) {
            setCaptureDate(mediaId, newCaptureDate);
        }
    }

    private void applySingle(long mediaId, LocalDateTime oldCaptureDate, LocalDateTime newCaptureDate) {
        LocalDateTime now = timeProvider.now();
        dsl.transaction(cfg -> {
            DSLContext ctx = cfg.dsl();
            mediaRepository.updateCaptureDate(ctx, mediaId, newCaptureDate);
            metadataEditRepository.recordEdit(ctx, mediaId, Field.CAPTURE_DATE,
                    oldCaptureDate == null ? null : oldCaptureDate.toString(),
                    newCaptureDate.toString(),
                    EditType.ABSOLUTE, now);
        });
    }
}
