package com.github.curiousoddman.curious_images.config;

import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import nu.pattern.OpenCV;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * AI inference configuration bound from {@code application.yaml} under the {@code ai:} prefix.
 * <p>
 * Defaults: CPU execution provider, 4 intra-op threads, batch size 8, model directory
 * {@code ~/.cimages/models/}.
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.ai")
public class AiConfig {
    // FIXME: all config values with time should properly be Duration class

    @PostConstruct
    public void initOpenCv() {
        OpenCV.loadLocally(); // org.openpnp packages this helper; call once at startup
    }

    /**
     * Which ONNX execution provider to use. GPU implies CUDA first, then DirectML fallback.
     */
    private ExecutionProvider executionProvider = ExecutionProvider.CPU;

    /**
     * Number of threads used inside a single ONNX op (intra-op parallelism).
     */
    private int intraOpThreads = 4;

    /**
     * Directory where ONNX model files are stored at runtime. Models are downloaded into this
     * directory on demand (see {@code ModelDownloadJob}) rather than bundled in the build
     * artifact.
     */
    private Path modelDir = Path.of(System.getProperty("user.home"), "cimages", "models");

    /**
     * The set of model files the AI pipeline needs and where to download each one from if it's
     * missing from {@link #modelDir}. Overridable via {@code app.ai.models} in
     * {@code application.yaml}; defaults below mirror what used to be baked into the
     * {@code downloadModels} Gradle task.
     */
    private List<ModelDownload> models = List.of(
            new ModelDownload(
                    "retinaface-resnet50.onnx",
                    "https://huggingface.co/TheEeeeLin/HivisionIDPhotos_matting/resolve/main/retinaface-resnet50.onnx"
            ),
            new ModelDownload(
                    "arcface_r50.onnx",
                    "https://huggingface.co/public-data/insightface/resolve/main/models/buffalo_l/w600k_r50.onnx"
            ),
            new ModelDownload(
                    "clip_image_vit_b32.onnx",
                    "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/visual/model.onnx"
            ),
            new ModelDownload(
                    "clip_text_vit_b32.onnx",
                    "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/main/textual/model.onnx"
            )
    );

    /**
     * Number of images processed per ONNX inference call. Tune up for GPU, down if RAM-constrained.
     */
    private int batchSize = 8;

    /**
     * Gap in hours between photos that starts a new event album.
     */
    private int eventGapHours = 6;

    /**
     * Minimum photos in a time gap to create an event album.
     */
    private int minEventSize = 5;

    /**
     * Minimum photos sharing a GPS cell to create a location album.
     */
    private int minLocationSize = 3;

    /**
     * Minimum photos in a visual-similarity cluster to create a similarity album.
     */
    private int minClusterSize = 10;

    /**
     * Minimum intra-cluster average cosine similarity to accept a similarity album.
     */
    private float minClusterSimilarity = 0.6f;

    /**
     * Thread-pool size for {@code DuplicateDetectionJob}'s hashing phase. Not bound directly from
     * {@code app.duplicate-detection.thread-count} (kept here instead so it's mutable at runtime
     * via {@code AiSettingsService}); seeded from that property at startup.
     */
    private int duplicateDetectionThreadCount = 4;

    /**
     * When {@code true}, the AI pipeline only runs face detection/recognition and skips CLIP
     * embedding generation. Seeded from {@code ai.features.face-only} at startup, then mutable
     * at runtime via {@code AiSettingsService}.
     */
    private boolean faceOnly = false;

    /**
     * How many frames to sample per video for face/CLIP embedding (implementation plan §5).
     * Frames are spaced evenly across the 10%-90% span of the video's duration (e.g. count=3
     * gives 10%/50%/90%), then capped by {@link #videoFrameSampleIntervalSeconds} so short videos
     * don't get needlessly close-together samples.
     */
    private int videoFrameSampleCount = 3;

    /**
     * Minimum spacing, in seconds, between sampled video frames — effectively caps
     * {@link #videoFrameSampleCount} down for videos too short to fit that many samples this far
     * apart. E.g. a 4-second video with intervalSeconds=5 samples only 1 frame (the midpoint)
     * even if videoFrameSampleCount is 3.
     */
    private int videoFrameSampleIntervalSeconds = 5;

    /**
     * Scene grouping (custom albums, album-refinement-feature-spec.md §4.1): two photos in the
     * same custom album are only considered *candidates* for the same scene group if their
     * {@code capture_date}s are within this many seconds of each other. TBD-tuned default — 5
     * minutes comfortably covers a burst or a quick sequence of shots without being so wide it
     * starts pulling in unrelated photos from the same outing.
     */
    private int sceneGroupTimestampWindowSeconds = 300;

    /**
     * Scene grouping: two photos are only candidates for the same scene group if their GPS
     * coordinates (when both have one — see {@link #sceneGroupTimestampWindowSeconds}'s javadoc
     * on the timestamp-only fallback) are within this many meters of each other.
     */
    private int sceneGroupGeoRadiusMeters = 50;

    /**
     * Scene grouping: maximum perceptual-hash (dHash) Hamming distance, out of a 64-bit hash, for
     * two candidate photos to be confirmed as the same scene (spec §4.2). Lower = stricter
     * (near-identical only); higher = looser (catches more visually-similar-but-not-identical
     * shots at the risk of false-grouping). 10/64 is a common "very similar" cutoff for this hash
     * size — tune via {@code app.ai.scene-group-hash-distance-threshold} if it proves too
     * strict/loose in practice.
     */
    private int sceneGroupHashDistanceThreshold = 10;

    public enum ExecutionProvider {
        /**
         * Run on CPU only (ONNX Runtime default).
         */
        CPU,
        /**
         * NVIDIA CUDA GPU execution. Requires CUDA 12+ drivers.
         */
        CUDA,
        /**
         * DirectX 12 GPU execution (AMD / Intel / NVIDIA, no CUDA drivers needed).
         */
        DIRECTML
    }

    /**
     * One downloadable model file: its filename under {@link #modelDir} and the URL to fetch it
     * from. Plain mutable POJO (not a record) so Spring's relaxed {@code @ConfigurationProperties}
     * binding can populate it from {@code application.yaml} if the defaults are overridden.
     */
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelDownload {
        private String filename;
        private String url;
    }
}
