package com.github.curiousoddman.curious_images.domain.common;

import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.domain.ai.PersonCorrectionService;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.Rotate;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository.EditType;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository.Field;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.io.File;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.IntUnaryOperator;

/**
 * Backs the media grid's right-click "Rotate" actions (see {@code PhotoCellController},
 * {@code DuplicatesController}, {@code SlideshowController}) and the metadata-edit UI's rotation
 * controls, for both photos and videos.
 * <p>
 * A media whose rotation is being manually corrected was, by definition, scanned by the AI
 * pipeline with the wrong orientation — its face bounding boxes, face crops, face/CLIP embeddings,
 * and any resulting cluster membership are all now meaningless. Rather than leave stale AI data
 * around waiting to be silently overwritten by a future pipeline run, {@link #applyRotation} does
 * all of the following for one media:
 * <ol>
 *   <li>updates {@code PHOTO.ORIENTATION} or {@code VIDEO.ROTATION} to the new (normalized
 *       0/90/180/270) value, and records the change in {@code MEDIA_METADATA_EDIT};</li>
 *   <li>resets every {@code AI_*_DONE} flag (+ clears the error/retry count) so the media is
 *       picked up by the next {@code AiPipelineJob} run as if newly imported;</li>
 *   <li>hard-deletes the media's existing {@code FACE} / {@code FACE_EMBEDDING} /
 *       {@code CLIP_EMBEDDING} rows, their on-disk face-thumbnail files, and their Lucene index
 *       entries — recomputing or deleting any cluster those faces belonged to via
 *       {@link PersonCorrectionService#removeFacesFromClusters}.</li>
 * </ol>
 * On success this also queues an immediate thumbnail regeneration and a fresh AI pipeline run.
 * <p>
 * <b>Transaction note:</b> cluster cleanup (step 3's clustering side) and the face/embedding row
 * deletion happen as two separate transactions, because the cluster cleanup needs the FACE rows'
 * {@code cluster_id} values to still be readable. A crash between the two could leave a media's
 * faces detached from a recomputed/deleted cluster but not yet deleted themselves — recoverable
 * manually (the next full AI pipeline / cluster rebuild pass will not re-use them, since they're
 * about to be deleted here right after), but not fully atomic. See class discussion if stronger
 * atomicity is ever needed.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MediaRotationService {

    private final DSLContext                  dsl;
    private final MediaRepository             photoRepo;
    private final MediaMetadataEditRepository metadataEditRepository;
    private final FaceRepository              faceRepo;
    private final FaceEmbeddingRepository     faceEmbeddingRepo;
    private final ClipEmbeddingRepository     clipEmbeddingRepo;
    private final PersonCorrectionService     personCorrectionService;
    private final FaceVectorIndex             faceVectorIndex;
    private final ClipVectorIndex             clipVectorIndex;
    private final JobManager                  jobManager;
    private final TimeProvider                timeProvider;

    /**
     * @param mediaId      the media being rotated (photo or video)
     * @param deltaDegrees one of enum
     *                     any value is accepted and normalized, but the menu only ever offers
     *                     these three
     */
    public void rotateAndClearAiResults(long mediaId, Rotate deltaDegrees) {
        applyRotation(mediaId, current -> ((current + deltaDegrees.getDegrees()) % 360 + 360) % 360, EditType.RELATIVE);
    }

    /**
     * Sets rotation to an absolute value (0/90/180/270) rather than adjusting the current one.
     */
    public void rotateAbsolute(long mediaId, Rotate absoluteDegrees) {
        int normalizedTarget = ((absoluteDegrees.getDegrees() % 360) + 360) % 360;
        applyRotation(mediaId, current -> normalizedTarget, EditType.ABSOLUTE);
    }

    /**
     * Bulk relative rotation — applies the same delta to every media, each independently.
     */
    public void rotateRelativeBulk(Collection<Long> mediaIds, Rotate deltaDegrees) {
        mediaIds.forEach(id -> rotateAndClearAiResults(id, deltaDegrees));
    }

    /**
     * Bulk absolute rotation — sets every media to the same absolute value.
     */
    public void rotateAbsoluteBulk(Collection<Long> mediaIds, Rotate absoluteDegrees) {
        mediaIds.forEach(id -> rotateAbsolute(id, absoluteDegrees));
    }

    /**
     * Shared implementation for both relative and absolute rotation, photo or video. {@code
     * newDegreesFn} computes the target absolute angle from the current one (ignored for
     * absolute edits, applied as a delta for relative ones).
     */
    private void applyRotation(long mediaId, IntUnaryOperator newDegreesFn, EditType editType) {
        Media media = photoRepo.findMediaById(mediaId);
        if (media == null) {
            log.warn("rotate: media {} no longer exists", mediaId);
            return;
        }

        int current    = Objects.requireNonNullElse(media.getRotationDegrees(), 0);
        int normalized = newDegreesFn.applyAsInt(current);

        List<FaceRecord> faces = faceRepo.findByMediaId(mediaId);
        List<Long> faceIds = faces.stream()
                                  .map(FaceRecord::getId)
                                  .toList();

        // Untangle cluster membership *before* the FACE rows disappear — removeFacesFromClusters
        // needs each face's current cluster_id to know which cluster(s) to recompute/delete.
        Set<Long> orphanedPersons = personCorrectionService.removeFacesFromClusters(faceIds);
        if (!orphanedPersons.isEmpty()) {
            log.info("rotate: media {} rotation left person(s) {} owning zero clusters — " +
                    "leaving them for manual cleanup via the People tab", mediaId, orphanedPersons);
        }

        LocalDateTime now = timeProvider.now();
        dsl.transaction(cfg -> {
            DSLContext ctx = cfg.dsl();
            if (media.isVideo()) {
                photoRepo.updateVideoRotationAndResetAi(ctx, mediaId, normalized, now);
            } else {
                photoRepo.updatePhotoOrientationAndResetAi(ctx, mediaId, normalized, now);
            }
            faceEmbeddingRepo.deleteByFaceIds(ctx, faceIds);
            faceRepo.deleteByMediaId(ctx, mediaId);
            clipEmbeddingRepo.deleteByPhotoId(ctx, mediaId);
            metadataEditRepository.recordEdit(ctx, mediaId, Field.ROTATION,
                    String.valueOf(current), String.valueOf(normalized), editType, now);
        });

        deleteFaceThumbnailFiles(faces);
        removeFromLuceneIndexes(mediaId, faceIds);

        jobManager.submitThumbnailGenerationJob(List.of(mediaId));
    }

    private void deleteFaceThumbnailFiles(List<FaceRecord> faces) {
        for (FaceRecord face : faces) {
            String path = face.getThumbnailAbsolutePath();
            if (path == null) {
                continue;
            }
            try {
                if (!new File(path).delete()) {
                    log.debug("rotate: face thumbnail {} was already gone", path);
                }
            } catch (Exception e) {
                log.warn("rotate: failed to delete face thumbnail {}", path, e);
            }
        }
    }

    private void removeFromLuceneIndexes(long photoId, List<Long> faceIds) {
        try {
            for (Long faceId : faceIds) {
                faceVectorIndex.delete(faceId);
            }
            clipVectorIndex.delete(photoId);
            faceVectorIndex.commit();
            clipVectorIndex.commit();
        } catch (Exception e) {
            // Non-fatal: DB state (the real source of truth) is already committed at this point
            // regardless. Worst case is a harmless dangling Lucene entry for a deleted face/media
            // id until the indexes are next rebuilt — those ids are never reused, so it can never
            // resurface as a false match against a *different* real face/media.
            log.warn("rotate: failed to remove media {} / faces {} from Lucene indexes", photoId, faceIds, e);
        }
    }
}
