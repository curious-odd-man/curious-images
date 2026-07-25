package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.ClipEmbeddingRecord;
import com.github.curiousoddman.curious_images.util.EmbeddingMath;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CLIP_EMBEDDING;

/**
 * Hand-written jOOQ repository for {@code clip_embedding}.
 * Embeddings are stored as raw little-endian float32 bytes: 512 floats × 4 bytes = 2048 bytes.
 */
@Repository
@RequiredArgsConstructor
public class ClipEmbeddingRepository {

    private final DSLContext dsl;

    /**
     * Returns an unexecuted MERGE (upsert) for a CLIP embedding row.
     * <p>
     * Merges on {@code (media_id, frame_offset_ms)} rather than {@code media_id} alone — a video
     * can have several embedding rows, one per sampled frame (implementation plan §5), each
     * upserted independently. A photo always passes {@code frameOffsetMs = null}, matching the
     * single "whole photo" embedding it's always had.
     *
     * @param photoId       the media row id
     * @param frameOffsetMs {@code null} for a photo; the sampled-frame timestamp (ms) for a video
     * @param embedding     512-dim float array, already L2-normalised
     * @param modelVersion  e.g. {@code "clip_vit_b32"}
     */
    public Query upsertQuery(long photoId, Long frameOffsetMs, float[] embedding, String modelVersion) {
        byte[]    bytes         = EmbeddingMath.toBytes(embedding);
        Condition matchOnFrame  = frameOffsetMs == null
                ? CLIP_EMBEDDING.FRAME_OFFSET_MS.isNull()
                : CLIP_EMBEDDING.FRAME_OFFSET_MS.eq(frameOffsetMs);
        return dsl.mergeInto(CLIP_EMBEDDING)
                  .using(dsl.selectOne())
                  .on(CLIP_EMBEDDING.MEDIA_ID.eq(photoId)
                                            .and(matchOnFrame))
                  .whenMatchedThenUpdate()
                  .set(CLIP_EMBEDDING.EMBEDDING, bytes)
                  .set(CLIP_EMBEDDING.MODEL_VER, modelVersion)
                  .whenNotMatchedThenInsert(CLIP_EMBEDDING.MEDIA_ID, CLIP_EMBEDDING.FRAME_OFFSET_MS,
                          CLIP_EMBEDDING.EMBEDDING, CLIP_EMBEDDING.MODEL_VER)
                  .values(photoId, frameOffsetMs, bytes, modelVersion);
    }

    /**
     * Returns one representative embedding for a media — the earliest-sampled frame for a video
     * (or its only row, {@code frame_offset_ms IS NULL}, for a photo). Used where "the" embedding
     * for a media is needed (e.g. find-similar) and picking a single frame is an acceptable
     * approximation; use {@link #findAllByMediaId} where every sampled frame matters.
     */
    public Optional<ClipEmbeddingRecord> findByMediaId(long mediaId) {
        return dsl.selectFrom(CLIP_EMBEDDING)
                  .where(CLIP_EMBEDDING.MEDIA_ID.eq(mediaId))
                  .orderBy(CLIP_EMBEDDING.FRAME_OFFSET_MS.asc()
                                                         .nullsFirst())
                  .limit(1)
                  .fetchOptional();
    }

    /**
     * Every embedding row for a media, in chronological order — a video can have several (one per
     * sampled frame, implementation plan §5); a photo always has exactly one.
     */
    public List<ClipEmbeddingRecord> findAllByMediaId(long mediaId) {
        return dsl.selectFrom(CLIP_EMBEDDING)
                  .where(CLIP_EMBEDDING.MEDIA_ID.eq(mediaId))
                  .orderBy(CLIP_EMBEDDING.FRAME_OFFSET_MS.asc()
                                                         .nullsFirst())
                  .fetch();
    }

    /**
     * Loads all CLIP embeddings — used by album generation (similarity clustering).
     */
    public List<ClipEmbeddingRecord> findAll() {
        return dsl.selectFrom(CLIP_EMBEDDING)
                  .fetch();
    }

    /**
     * Deletes every CLIP embedding row for a media (all sampled frames, for a video), if any.
     * Call inside the same transaction as the corresponding {@code PHOTO}/{@code VIDEO}/
     * {@code FACE} row changes — see {@code PhotoRotationService}, the only current caller
     * (manual rotation correction wipes a media's AI data outright).
     */
    public void deleteByPhotoId(DSLContext ctx, long photoId) {
        ctx.deleteFrom(CLIP_EMBEDDING)
           .where(CLIP_EMBEDDING.MEDIA_ID.eq(photoId))
           .execute();
    }

    public List<ClipEmbeddingRecord> findByPhotoIds(List<Long> photoIds) {
        return dsl.selectFrom(CLIP_EMBEDDING)
                  .where(CLIP_EMBEDDING.MEDIA_ID.in(photoIds))
                  .fetch();
    }
}
