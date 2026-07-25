package com.github.curiousoddman.curious_images.domain.index;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.search.TopDocs;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

import static org.apache.lucene.index.VectorSimilarityFunction.DOT_PRODUCT;

/**
 * Lucene HNSW KNN vector index for 512-dim CLIP image embeddings.
 * <p>
 * Uses {@link org.apache.lucene.index.VectorSimilarityFunction#DOT_PRODUCT} which equals
 * cosine similarity when vectors are L2-normalised (as our CLIP embeddings always are).
 * {@link SearcherManager#maybeRefresh()} is called after each {@link #commit()} so the
 * reader sees freshly committed segments within the same process.
 * <p>
 * A video can contribute several embeddings — one per sampled frame (implementation plan §5) —
 * so a media id alone is no longer a unique document key. Each document is keyed by a synthetic
 * {@code row_id} (media id + frame offset, {@code "1234:single"} for a photo or
 * {@code "1234:1500"} for a video frame at 1500ms), so upserting one frame's embedding never
 * clobbers another frame's. The {@code photo_id} field still holds the plain media id and is
 * still indexed (not just stored), so {@link #delete} can remove every frame document for a
 * media in one call, and {@link #search} can map hits back to media ids.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ClipVectorIndex {
    private final IndexWriter     clipIndexWriter;
    private final SearcherManager clipSearcherManager;

    /**
     * Adds or replaces the CLIP embedding for one frame of {@code mediaId} (or the whole photo,
     * when {@code frameOffsetMs} is {@code null}). Call {@link #commit()} after each batch to
     * make writes visible to searchers.
     */
    public void upsert(long mediaId, Long frameOffsetMs, float[] embedding) throws IOException {
        Document doc    = new Document();
        String   rowKey = rowKey(mediaId, frameOffsetMs);
        doc.add(new StringField("row_id", rowKey, Field.Store.YES));
        doc.add(new StringField("photo_id", String.valueOf(mediaId), Field.Store.YES));
        doc.add(new KnnFloatVectorField("clip_vec", embedding, DOT_PRODUCT));
        clipIndexWriter.updateDocument(new Term("row_id", rowKey), doc);
    }

    /**
     * Removes every CLIP embedding document for {@code photoId} — all sampled frames, for a
     * video. Call {@link #commit()} afterward to make the removal visible to searchers. Used by
     * {@code PhotoRotationService} when a photo's rotation is manually corrected and its
     * embedding is deleted outright.
     */
    public void delete(long photoId) throws IOException {
        clipIndexWriter.deleteDocuments(new Term("photo_id", String.valueOf(photoId)));
    }

    /**
     * Commits buffered writes and refreshes searchers. Call after each batch in the indexing
     * pipeline stage.
     */
    public void commit() throws IOException {
        clipIndexWriter.commit();
        clipSearcherManager.maybeRefresh();
    }

    /**
     * Returns up to {@code k} distinct media IDs ordered by descending cosine similarity to
     * {@code queryVec}. The query vector must be L2-normalised.
     * <p>
     * Several frame-hits from the same video are collapsed into a single result (implementation
     * plan §7) — Lucene returns hits best-score-first, so keeping only the first occurrence of
     * each media id is equivalent to keeping its best-matching frame. This can return fewer than
     * {@code k} media ids if enough hits collapse; over-fetching from Lucene compensates for the
     * common case without materially changing recall.
     */
    public List<Long> search(float[] queryVec, int k) throws IOException {
        IndexSearcher searcher = clipSearcherManager.acquire();
        try {
            int     overFetch = Math.max(k * 4, k + 20);
            TopDocs hits      = searcher.search(new KnnFloatVectorQuery("clip_vec", queryVec, overFetch), overFetch);

            LinkedHashSet<Long> distinctMediaIds = new LinkedHashSet<>(k * 2);
            for (ScoreDoc scoreDoc : hits.scoreDocs) {
                if (distinctMediaIds.size() >= k) {
                    break;
                }
                try {
                    long mediaId = Long.parseLong(
                            searcher.storedFields()
                                    .document(scoreDoc.doc)
                                    .get("photo_id"));
                    distinctMediaIds.add(mediaId);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
            return List.copyOf(distinctMediaIds);
        } finally {
            clipSearcherManager.release(searcher);
        }
    }

    /**
     * Same ranking/dedup behavior as {@link #search}, but also returns which frame matched best
     * for a video hit (implementation plan §7: "showing the best-matching frame as the result's
     * thumbnail"). {@code frameOffsetMs} is {@code null} for a photo hit or for a video whose
     * matching row predates frame-level sampling.
     * <p>
     * Note: the grid still renders each result via the normal per-media cached thumbnail (from
     * {@code VideoThumbnailGenerator}, sampled independently of any search), not this specific
     * frame — actually rendering an arbitrary matched frame as a one-off thumbnail is a separate,
     * larger UI feature. This method exists so that capability can be added later (e.g. a
     * "jump to this moment" affordance) without another index-format change.
     */
    public List<ScoredHit> searchWithFrameOffsets(float[] queryVec, int k) throws IOException {
        IndexSearcher searcher = clipSearcherManager.acquire();
        try {
            int     overFetch = Math.max(k * 4, k + 20);
            TopDocs hits      = searcher.search(new KnnFloatVectorQuery("clip_vec", queryVec, overFetch), overFetch);

            LinkedHashSet<Long> seenMediaIds = new LinkedHashSet<>(k * 2);
            List<ScoredHit>     results      = new ArrayList<>(k);
            for (ScoreDoc scoreDoc : hits.scoreDocs) {
                if (results.size() >= k) {
                    break;
                }
                var storedDoc = searcher.storedFields()
                                        .document(scoreDoc.doc);
                long mediaId = Long.parseLong(storedDoc.get("photo_id"));
                if (!seenMediaIds.add(mediaId)) {
                    continue; // a later (lower-scoring) hit for a media already captured — skip
                }
                String rowId         = storedDoc.get("row_id");
                Long   frameOffsetMs = frameOffsetFromRowKey(rowId);
                results.add(new ScoredHit(mediaId, frameOffsetMs, scoreDoc.score));
            }
            return results;
        } finally {
            clipSearcherManager.release(searcher);
        }
    }

    private static Long frameOffsetFromRowKey(String rowId) {
        if (rowId == null) {
            return null;
        }
        int colon = rowId.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String suffix = rowId.substring(colon + 1);
        if ("single".equals(suffix)) {
            return null;
        }
        try {
            return Long.parseLong(suffix);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public record ScoredHit(long mediaId, Long frameOffsetMs, float score) {
    }

    private static String rowKey(long mediaId, Long frameOffsetMs) {
        return mediaId + ":" + (frameOffsetMs == null ? "single" : frameOffsetMs);
    }
}
