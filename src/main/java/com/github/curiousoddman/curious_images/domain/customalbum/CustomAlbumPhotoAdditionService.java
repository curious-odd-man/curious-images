package com.github.curiousoddman.curious_images.domain.customalbum;

import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.event.model.TreeViewUpdateEvent;
import com.github.curiousoddman.curious_images.event.model.UserNotificationEvent;
import com.github.curiousoddman.curious_images.event.payload.CustomAlbumVideosSkipped;
import com.github.curiousoddman.curious_images.event.payload.TreeViewUpdatePayload;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.util.QueryBuffer;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Set;

/**
 * Backs the "Add to Album..." context-menu action (album-refinement-feature-spec.md, and the
 * "add photos" addition to {@code GridContextMenu}). Only ever adds already-imported library
 * photos — there's no file-picker/import path here, matching how the menu entry is scoped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CustomAlbumPhotoAdditionService {

    private final DSLContext                 dsl;
    private final MediaRepository            mediaRepository;
    private final CustomAlbumRepository      customAlbumRepository;
    private final CustomAlbumPhotoRepository customAlbumPhotoRepository;
    private final TimeProvider               timeProvider;
    private final JobManager                 jobManager;
    private final ApplicationEventPublisher  eventPublisher;

    /**
     * Adds photos to an existing custom album. {@code mediaIds} is filtered down to
     * photo-only ids first (videos are silently skipped, per the feature's "photos only" rule —
     * see {@link MediaRepository#findByIdIn} which only ever returns photo rows), and ids
     * already in the album are naturally no-ops thanks to the join table's primary key.
     * Kicks off a {@link com.github.curiousoddman.curious_images.domain.scenegroup.SceneGroupingJob}
     * sweep afterward so the new photos get clustered. Publishes
     * {@link com.github.curiousoddman.curious_images.event.payload.CustomAlbumVideosSkipped} if
     * any videos were filtered out, so that's surfaced rather than silent.
     *
     * @return how many photos were actually added (after the photo-only filter).
     */
    public int addPhotos(long albumId, Set<Long> mediaIds) {
        Set<Long> photoIds = mediaRepository.findByIdIn(mediaIds)
                                            .stream()
                                            .map(MediaPhotoRecord::getId)
                                            .collect(java.util.stream.Collectors.toSet());

        int videosSkipped = mediaIds.size() - photoIds.size();
        if (videosSkipped > 0) {
            String albumName = customAlbumRepository.findById(albumId)
                                                    .map(CustomAlbumRecord::getName)
                                                    .orElse("album");
            eventPublisher.publishEvent(new UserNotificationEvent(this,
                    new CustomAlbumVideosSkipped(photoIds.size(), videosSkipped, albumName)));
        }

        if (photoIds.isEmpty()) {
            return 0;
        }

        LocalDateTime now = timeProvider.now();
        try (QueryBuffer buffer = new QueryBuffer(dsl)) {
            for (Long photoId : photoIds) {
                buffer.add(customAlbumPhotoRepository.insertQuery(albumId, photoId, now));
            }
        }

        jobManager.submitSceneGroupingJob();
        return photoIds.size();
    }

    /**
     * Convenience for the picker dialog's "create new album" path: creates the album, adds the
     * (already photo-filtered) selection to it in one call, and publishes
     * {@link TreeViewUpdatePayload.CustomAlbumCreate} so the tree picks up the new album without
     * a full rebuild — same event the tree's inline "+" button publishes.
     *
     * @return the new album's id.
     */
    public long createAlbumAndAddPhotos(String albumName, Set<Long> mediaIds) {
        LocalDateTime now     = timeProvider.now();
        long          albumId = customAlbumRepository.insert(albumName, now);
        addPhotos(albumId, mediaIds);
        eventPublisher.publishEvent(new TreeViewUpdateEvent(this,
                new TreeViewUpdatePayload.CustomAlbumCreate(albumId, albumName)));
        return albumId;
    }
}
