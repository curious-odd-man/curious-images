package com.github.curiousoddman.curious_images.domain.ai;

import ai.onnxruntime.OrtException;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ClusterRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceEmbeddingRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.FaceRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.domain.index.ClipVectorIndex;
import com.github.curiousoddman.curious_images.domain.index.FaceVectorIndex;
import com.github.curiousoddman.curious_images.event.model.UserNotificationEvent;
import com.github.curiousoddman.curious_images.event.payload.FaceClipProcessingFailed;
import com.github.curiousoddman.curious_images.model.FaceLandmarks;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.persistence.ClipEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.ClusterRepository;
import com.github.curiousoddman.curious_images.persistence.FaceEmbeddingRepository;
import com.github.curiousoddman.curious_images.persistence.FaceRepository;
import com.github.curiousoddman.curious_images.persistence.FaceThumbnailsRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.PhotoTagRepository;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
import com.github.curiousoddman.curious_images.util.ImageUtils;
import com.github.curiousoddman.curious_images.util.QueryBuffer;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import com.github.curiousoddman.curious_images.util.async.jobs.IrrecoverableIterationException;
import com.github.curiousoddman.curious_images.util.async.jobs.JobManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.opencv.core.Mat;

import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import static com.github.curiousoddman.curious_images.util.QueryBuffer.DB_FLUSH_BATCH_SIZE;

@Slf4j
@RequiredArgsConstructor
public class AiPipelineJob extends BackgroundJob {
    public static final  String AI_PIPELINE  = "AI Pipeline";
    private static final int    TAGGING_KTOP = 5;

    private static final String ARCFACE_MODEL_VER    = "arcface_r50";
    private static final String CLIP_IMAGE_MODEL_VER = "clip_vit_b32";
    private static final String CLIP_TEXT_MODEL_VER  = "text_vit_b32";

    private final DSLContext               dsl;
    private final MediaRepository          photoRepo;
    private final FaceRepository           faceRepo;
    private final ClusterRepository        clusterRepo;
    private final FaceEmbeddingRepository  faceEmbeddingRepo;
    private final ClipEmbeddingRepository  clipEmbeddingRepo;
    private final RetinaFaceDetector       retinaFaceDetector;
    private final ArcFaceEncoder           arcFaceEncoder;
    private final FaceAligner              faceAligner;
    private final ClipImageEncoder         clipImageEncoder;
    private final ClipVectorIndex          clipVectorIndex;
    private final FaceVectorIndex          faceVectorIndex;
    private final PersonClusteringService  personClusteringService;
    private final TimeProvider             timeProvider;
    private final FaceThumbnailsRepository faceThumbnailsRepository;
    private final JobManager               jobManager;
    private final ImageUtils               imageUtils;
    private final VideoFrameSampler        videoFrameSampler;
    private final boolean                  faceDetectionOnly;
    private final int                      videoFrameSampleCount;
    private final Duration                 videoFrameSampleInterval;
    private final ClipTextEncoder          clipTextEncoder;
    private final PhotoTagRepository       tagRepository;
    private final OnnxModelRegistry        onnxModelRegistry;

    @Override
    public void runImpl() throws Exception {
        publishStarted("Starting AI pipeline...");
        try {
            runFaceAndClipEmbedding();
            if (isInterruptRequested()) {
                publishInterrupted();
                return;
            }

            if (!faceDetectionOnly) {
                runLuceneIndexing();
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }

                runImageTagging();
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }
            }

            personClusteringService.clusterIncremental();

            publishEnded("AI pipeline complete");
            jobManager.submitAlbumGenerationJob();
        } catch (Exception e) {
            log.error("AI pipeline failed", e);
            publishFailed(e);
            throw e;
        } finally {
            onnxModelRegistry.evictAll();
        }
    }

    private void runImageTagging() throws OrtException, IrrecoverableIterationException {
        List<Long> photoIds = photoRepo.findPendingAiTagging();
        if (photoIds.isEmpty()) {
            log.info("AI tagging: no pending photos");
            return;
        }

        // Note - those must be normalized!!
        List<TagEmbeddingRecord> tags = getOrCalculateTagEmbeddings();

        if (isInterruptRequested()) {
            publishInterrupted();
            return;
        }

        List<ClipEmbeddingRecord> clipEmbeddings = clipEmbeddingRepo.findByPhotoIds(photoIds);
        try (QueryBuffer queryBuffer = new QueryBuffer(dsl)) {
            publishInProgress("Image Tagging...", 0, photoIds.size());
            for (int i = 0; i < clipEmbeddings.size(); i++) {
                ClipEmbeddingRecord clipEmbedding = clipEmbeddings.get(i);
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return;
                }

                float[]                 embedding     = EmbeddingMath.getFloats(clipEmbedding.getEmbedding());
                PriorityQueue<TagScore> priorityQueue = new PriorityQueue<>(Comparator.comparing(TagScore::score));
                for (TagEmbeddingRecord tag : tags) {
                    if (isInterruptRequested()) {
                        publishInterrupted();
                        return;
                    }

                    float[] tagEmbedding = EmbeddingMath.getFloats(tag.getEmbedding());
                    float   similarity   = EmbeddingMath.dot(embedding, tagEmbedding);
                    priorityQueue.add(new TagScore(tag, similarity));
                    if (priorityQueue.size() > TAGGING_KTOP) {
                        priorityQueue.poll();
                    }
                }

                for (TagScore tagScore : priorityQueue) {
                    queryBuffer.add(
                            tagRepository.upsert(clipEmbedding.getMediaId(), tagScore.tag(), tagScore.score()),
                            photoRepo.markTaggingDone(clipEmbedding.getMediaId())

                    );
                }
                publishProgressThrottled("Processing photos", i + 1, clipEmbeddings.size(),
                        "Photo id = " + clipEmbedding.getMediaId(), i == clipEmbeddings.size() - 1);
            }
        }
    }

    private List<TagEmbeddingRecord> getOrCalculateTagEmbeddings() throws OrtException, IrrecoverableIterationException {
        List<TagEmbeddingRecord> tags = tagRepository.findAllTags();

        List<TagEmbeddingRecord> missing = tags.stream()
                                               .filter(t -> t.getEmbedding() == null
                                                       || !CLIP_TEXT_MODEL_VER.equals(t.getModelVer()))
                                               .toList();

        if (!missing.isEmpty()) {
            publishInProgress("Tag Embeddings", 0, missing.size());
            try (QueryBuffer queryBuffer = new QueryBuffer(dsl)) {
                log.debug("Calculating embeddings for {} tags", missing.size());
                if (isInterruptRequested()) {
                    publishInterrupted();
                    return List.of();
                }

                for (int i = 0; i < missing.size(); i++) {
                    TagEmbeddingRecord tag       = missing.get(i);
                    float[]            embedding = clipTextEncoder.encode(tag.getTag());

                    tag.setEmbedding(EmbeddingMath.toBytes(embedding));
                    tag.setModelVer(CLIP_TEXT_MODEL_VER);
                    publishProgressThrottled("Tag embeddings", i + 1, missing.size(),
                            tag.getCategory() + " : " + tag.getTag(), i == missing.size() - 1);
                }

                queryBuffer.add(tagRepository.update(missing));
            }
        } else {
            log.debug("All tags are embedded - no work to be done...");
        }

        return tags;
    }

    public record TagScore(TagEmbeddingRecord tag, float score) {
    }

    private void runFaceAndClipEmbedding() {
        List<Long> facePending = photoRepo.findPendingFaceDetect();
        List<Long> clipPending = faceDetectionOnly ? List.of() : photoRepo.findPendingClipEmbed();

        // Union while preserving a stable, deduplicated order.
        LinkedHashSet<Long> allPending = new LinkedHashSet<>(facePending);
        allPending.addAll(clipPending);

        if (allPending.isEmpty()) {
            log.info("Face/CLIP: no pending photos");
            return;
        }

        Set<Long>  faceSet  = new HashSet<>(facePending);
        Set<Long>  clipSet  = new HashSet<>(clipPending);
        List<Long> photoIds = new ArrayList<>(allPending);

        publishInProgress("Processing photos...", 0, photoIds.size());
        try (QueryBuffer buffer = new QueryBuffer(dsl)) {
            for (int i = 0; i < photoIds.size(); i++) {
                if (detectFacesAndEmbedding(photoIds, i, faceSet, buffer, clipSet)) {
                    return;
                }
            }
        }
    }

    private boolean detectFacesAndEmbedding(List<Long> photoIds, int i, Set<Long> faceSet, QueryBuffer buffer, Set<Long> clipSet) {
        if (isInterruptRequested()) {
            return true;
        }

        long          photoId       = photoIds.get(i);
        LocalDateTime now           = timeProvider.now();
        String        lastPhotoPath = "";
        Media         media         = null;
        List<Frame>   frames        = List.of();
        try {
            media = photoRepo.findMediaById(photoId);
            if (media == null) {
                throw new IllegalStateException("Media not found: " + photoId);
            }
            lastPhotoPath = media.getAbsolutePath();

            frames = loadFrames(media);
            if (frames.isEmpty()) {
                throw new IllegalStateException("No frames could be decoded for " + lastPhotoPath);
            }

            for (Frame frame : frames) {
                if (faceSet.contains(photoId)) {
                    List<DetectedFace> faces = retinaFaceDetector.detect(frame.img());
                    for (DetectedFace face : faces) {
                        Path faceThumbnailPath = faceThumbnailsRepository.createFaceThumbnail(
                                lastPhotoPath, ImageUtils.toBufferedImage(frame.img()), face, frame.offsetMs());
                        long faceId = faceRepo.insertAndGetId(
                                photoId, face.x(), face.y(), face.w(), face.h(),
                                face.confidence(), toLandmarks(face.landmarks()), now, faceThumbnailPath,
                                frame.offsetMs());

                        Mat     aligned   = faceAligner.align(frame.img(), face.landmarks());
                        float[] embedding = arcFaceEncoder.encode(aligned);
                        aligned.release();
                        buffer.add(faceEmbeddingRepo.upsertQuery(faceId, embedding, ARCFACE_MODEL_VER));
                    }
                }

                if (clipSet.contains(photoId)) {
                    float[] clipEmbedding = clipImageEncoder.encode(frame.img());
                    buffer.add(clipEmbeddingRepo.upsertQuery(photoId, frame.offsetMs(), clipEmbedding, CLIP_IMAGE_MODEL_VER));
                }
            }

            // One "done" flag per media, not per frame — a video's 3 sampled frames still only
            // flip face_detect_done/clip_embed_done once each (implementation plan §5).
            if (faceSet.contains(photoId)) {
                buffer.add(photoRepo.markFaceDetectAndEmbedDoneQuery(photoId, now));
            }
            if (clipSet.contains(photoId)) {
                buffer.add(photoRepo.markClipEmbedDoneQuery(photoId, now));
            }
        } catch (IrrecoverableIterationException e) {
            log.warn("Face/CLIP processing failed for media {}", photoId, e);
            buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
            log.error("Exiting loop - cannot recover from that....");
            publishFailed(e.getCause());
            return true;
        } catch (Exception e) {
            log.error("Face/CLIP processing failed for media {}", photoId, e);
            eventPublisher.publishEvent(new UserNotificationEvent(this, new FaceClipProcessingFailed(media, e)));
            buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
        } finally {
            for (Frame frame : frames) {
                frame.img()
                     .release(); // native memory — must release explicitly, GC won't do it
            }
        }

        publishProgressThrottled("Processing photos", i + 1, photoIds.size(),
                lastPhotoPath, i == photoIds.size() - 1);
        return false;
    }

    /**
     * Loads the frame(s) to run face/CLIP detection on for one media: a photo is always exactly
     * one frame with a {@code null} offset; a video is sampled into a handful of frames via
     * {@link VideoFrameSampler}, each carrying its own timestamp so the resulting face/
     * clip_embedding rows can record {@code frame_offset_ms} (implementation plan §5). Never
     * throws for a bad file — returns an empty list, which the caller treats as a processing
     * failure for that one media (same "isolate one bad file" policy as everywhere else in this
     * pipeline).
     */
    private List<Frame> loadFrames(Media media) {
        if (media.isVideo()) {
            List<VideoFrameSampler.SampledFrame> sampled = videoFrameSampler.sample(
                    Path.of(media.getAbsolutePath()), videoFrameSampleCount, videoFrameSampleInterval);
            List<Frame> frames = new ArrayList<>(sampled.size());
            for (VideoFrameSampler.SampledFrame sample : sampled) {
                frames.add(new Frame(sample.offsetMs(), ImageUtils.toMat(sample.image())));
            }
            return frames;
        }
        return imageUtils.imageOrCr2Preview(media.photo())
                         .map(img -> List.of(new Frame(null, img)))
                         .orElse(List.of());
    }

    /**
     * One frame to run detection on: {@code offsetMs} is {@code null} for a photo, or the sampled
     * timestamp within a video.
     */
    private record Frame(Long offsetMs, Mat img) {
    }

    // ── Stage 4: Lucene indexing ──────────────────────────────────────────────

    private void runLuceneIndexing() throws Exception {
        List<Long> photoIds = photoRepo.findPendingLuceneIndex();
        if (photoIds.isEmpty()) {
            log.info("Lucene indexing: no pending photos");
            return;
        }
        publishInProgress("Indexing vectors...", 0, photoIds.size());

        try (QueryBuffer buffer = new QueryBuffer(dsl)) {
            for (int i = 0; i < photoIds.size(); i++) {
                if (isInterruptRequested()) {
                    clipVectorIndex.commit();
                    faceVectorIndex.commit();
                    return;
                }

                long          photoId = photoIds.get(i);
                LocalDateTime now     = timeProvider.now();
                try {
                    // Index CLIP embedding(s) — a video can have several, one per sampled frame
                    // (implementation plan §5); each gets its own Lucene doc (see ClipVectorIndex).
                    List<ClipEmbeddingRecord> clipRecs = clipEmbeddingRepo.findAllByMediaId(photoId);
                    for (ClipEmbeddingRecord clipRec : clipRecs) {
                        float[] clipEmbed = EmbeddingMath.getFloats(clipRec.getEmbedding());
                        clipVectorIndex.upsert(photoId, clipRec.getFrameOffsetMs(), clipEmbed);
                    }

                    // Index face embeddings
                    List<FaceRecord> faces = faceRepo.findByMediaId(photoId);
                    List<Long> faceIds = faces.stream()
                                              .map(FaceRecord::getId)
                                              .toList();
                    Map<Long, FaceEmbeddingRecord> faceEmbeds = faceEmbeddingRepo.findByFaceIds(faceIds);
                    for (FaceRecord face : faces) {
                        FaceEmbeddingRecord emb = faceEmbeds.get(face.getId());
                        if (emb != null) {
                            float[] faceEmbed = EmbeddingMath.getFloats(emb.getEmbedding());
                            // TODO: this index is only refreshed here, at Lucene-indexing time — it is
                            //  NOT updated when a manual correction (reassign/merge/exclude, FR1-FR5)
                            //  changes a face's owner later. Revisit if face-similarity search starts
                            //  relying on this index reflecting corrections promptly.
                            Long personId = (face.getClusterId() != null)
                                    ? clusterRepo.findById(face.getClusterId())
                                                 .map(ClusterRecord::getPersonId)
                                                 .orElse(null)
                                    : null;
                            faceVectorIndex.upsert(face.getId(), personId, faceEmbed);
                        }
                    }

                    buffer.add(photoRepo.markLuceneIndexDoneQuery(photoId, now));
                } catch (Exception e) {
                    log.warn("Lucene indexing failed for media {}", photoId, e);
                    buffer.add(photoRepo.markErrorQuery(photoId, e.getMessage(), now));
                }

                int nextIndex = i + 1;
                if (nextIndex % DB_FLUSH_BATCH_SIZE == 0) {
                    clipVectorIndex.commit();
                    faceVectorIndex.commit();
                }
                publishProgressThrottled("Indexing vectors", nextIndex, photoIds.size(),
                        "Photo id: " + photoId, i == photoIds.size() - 1);
            }
        }
        clipVectorIndex.commit();
        faceVectorIndex.commit();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private FaceLandmarks toLandmarks(float[][] landmarks) {
        return new FaceLandmarks(
                landmarks[0][0], landmarks[0][1],
                landmarks[1][0], landmarks[1][1],
                landmarks[2][0], landmarks[2][1],
                landmarks[3][0], landmarks[3][1],
                landmarks[4][0], landmarks[4][1]
        );
    }

    @Override
    public String getProcessName() {
        return AI_PIPELINE;
    }
}