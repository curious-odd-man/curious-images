package com.github.curiousoddman.curious_images.config;

import com.github.curiousoddman.curious_images.domain.DataAccess;
import com.github.curiousoddman.curious_images.domain.ai.OnnxModelRegistry;
import com.github.curiousoddman.curious_images.domain.user.prefs.UserPrefKey;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.function.Function;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiSettingsService {

    private final AiConfig          aiConfig;
    private final DataAccess        dataAccess;
    private final OnnxModelRegistry onnxModelRegistry;

    // Fallback defaults if nothing has ever been persisted for these two - they used to be bound
    // via @Value directly onto JobFactory; now AiConfig is the single source of truth, seeded
    // from these yaml keys the first time the app runs.
    @Value("${app.duplicate-detection.thread-count:4}")
    private int     yamlDuplicateDetectionThreadCount;
    @Value("${ai.features.face-only:false}")
    private boolean yamlFaceOnly;

    @PostConstruct
    public void applyPersistedOverrides() {
        aiConfig.setAiExecutionProvider(
                AiExecutionProvider.valueOf(
                        dataAccess.getUserPref(UserPrefKey.AI_EXECUTION_PROVIDER, aiConfig.getAiExecutionProvider()
                                                                                          .name())));
        aiConfig.setOnnxIntraOpThreads(getInt(UserPrefKey.AI_INTRA_OP_THREADS, aiConfig.getOnnxIntraOpThreads()));
        aiConfig.setDuplicateDetectionThreadCount(getInt(UserPrefKey.AI_DUPLICATE_DETECTION_THREAD_COUNT, yamlDuplicateDetectionThreadCount));
        aiConfig.setAiPipelineFaceOnly(getBoolean(UserPrefKey.AI_FACE_ONLY, yamlFaceOnly));
        aiConfig.setAlbumEventsGap(getDuration(UserPrefKey.AI_EVENT_GAP, aiConfig.getAlbumEventsGap()));
        aiConfig.setAlbumEventsMinPhotos(getInt(UserPrefKey.AI_MIN_EVENT_SIZE, aiConfig.getAlbumEventsMinPhotos()));
        aiConfig.setAlbumLocationsMinCellSize(getInt(UserPrefKey.AI_MIN_LOCATION_SIZE, aiConfig.getAlbumLocationsMinCellSize()));
        aiConfig.setAlbumSimilaritiesMinClusterSize(getInt(UserPrefKey.AI_MIN_CLUSTER_SIZE, aiConfig.getAlbumSimilaritiesMinClusterSize()));
        aiConfig.setAlbumSimilaritiesMinSimilarity(getFloat(UserPrefKey.AI_MIN_CLUSTER_SIMILARITY, aiConfig.getAlbumSimilaritiesMinSimilarity()));
        aiConfig.setVideoFrameSampleCount(getInt(UserPrefKey.AI_VIDEO_FRAME_SAMPLE_COUNT, aiConfig.getVideoFrameSampleCount()));
        aiConfig.setVideoFrameSampleInterval(getDuration(UserPrefKey.AI_VIDEO_FRAME_SAMPLE_INTERVAL, aiConfig.getVideoFrameSampleInterval()));
        log.info("Applied persisted AI settings: provider={}, intraOpThreads={}, dedupeThreads={}, faceOnly={}",
                aiConfig.getAiExecutionProvider(), aiConfig.getOnnxIntraOpThreads(),
                aiConfig.getDuplicateDetectionThreadCount(), aiConfig.isAiPipelineFaceOnly());
    }

    // ── Performance (require an ONNX session reload to take effect) ────────────

    public void setExecutionProvider(AiExecutionProvider provider) {
        aiConfig.setAiExecutionProvider(provider);
        dataAccess.setUserPref(UserPrefKey.AI_EXECUTION_PROVIDER, provider.name());
        onnxModelRegistry.evictAll();
        log.info("Execution provider changed to {} - AI sessions will reload on next use", provider);
    }

    public void setIntraOpThreads(int threads) {
        aiConfig.setOnnxIntraOpThreads(threads);
        dataAccess.setUserPref(UserPrefKey.AI_INTRA_OP_THREADS, String.valueOf(threads));
        onnxModelRegistry.evictAll();
    }

    // ── Performance (take effect on next call/job, no reload needed) ───────────

    public void setDuplicateDetectionThreadCount(int threadCount) {
        aiConfig.setDuplicateDetectionThreadCount(threadCount);
        dataAccess.setUserPref(UserPrefKey.AI_DUPLICATE_DETECTION_THREAD_COUNT, String.valueOf(threadCount));
    }

    public void setFaceOnly(boolean faceOnly) {
        aiConfig.setAiPipelineFaceOnly(faceOnly);
        dataAccess.setUserPref(UserPrefKey.AI_FACE_ONLY, String.valueOf(faceOnly));
    }

    // ── Album-generation tuning (take effect next time albums are (re)generated) ─

    public void setEventGapHours(Duration duration) {
        aiConfig.setAlbumEventsGap(duration);
        dataAccess.setUserPref(UserPrefKey.AI_EVENT_GAP, DurationStyle.SIMPLE.print(duration));
    }

    public void setAlbumEventMinPhotos(int count) {
        aiConfig.setAlbumEventsMinPhotos(count);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_EVENT_SIZE, String.valueOf(count));
    }

    public void setAlbumLocationsMinCellSize(int size) {
        aiConfig.setAlbumLocationsMinCellSize(size);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_LOCATION_SIZE, String.valueOf(size));
    }

    public void setAlbumSimilaritiesMinClusterSize(int size) {
        aiConfig.setAlbumSimilaritiesMinClusterSize(size);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_CLUSTER_SIZE, String.valueOf(size));
    }

    public void setAlbumSimilaritiesMinSimilarity(float similarity) {
        aiConfig.setAlbumSimilaritiesMinSimilarity(similarity);
        dataAccess.setUserPref(UserPrefKey.AI_MIN_CLUSTER_SIMILARITY, String.valueOf(similarity));
    }

    // ── Video frame sampling (take effect on next AI pipeline run) ──────────────

    public void setVideoFrameSampleCount(int count) {
        aiConfig.setVideoFrameSampleCount(count);
        dataAccess.setUserPref(UserPrefKey.AI_VIDEO_FRAME_SAMPLE_COUNT, String.valueOf(count));
    }

    public void setVideoFrameSampleInterval(Duration duration) {
        aiConfig.setVideoFrameSampleInterval(duration);
        dataAccess.setUserPref(UserPrefKey.AI_VIDEO_FRAME_SAMPLE_INTERVAL, DurationStyle.SIMPLE.print(duration));
    }

    public AiConfig config() {
        return aiConfig;
    }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Duration getDuration(UserPrefKey key, Duration defaultValue) {
        return tryParseOrDefault(DurationStyle::detectAndParse, defaultValue, key, DurationStyle.SIMPLE::print);
    }

    private int getInt(UserPrefKey key, int defaultValue) {
        return tryParseOrDefault(Integer::parseInt, defaultValue, key, String::valueOf);
    }

    private boolean getBoolean(UserPrefKey key, boolean defaultValue) {
        return Boolean.parseBoolean(dataAccess.getUserPref(key, String.valueOf(defaultValue)));
    }

    private float getFloat(UserPrefKey key, float defaultValue) {
        return tryParseOrDefault(Float::parseFloat, defaultValue, key, String::valueOf);
    }

    private <T> T tryParseOrDefault(Function<String, T> callable, T defaultValue, UserPrefKey key, Function<T, String> toString) {
        String defaultString = toString.apply(defaultValue);
        try {
            String value = dataAccess.getUserPref(key, defaultString);
            return callable.apply(value);
        } catch (Exception e) {
            log.warn("Corrupt pref [{}], using default {}", key.getKey(), defaultString);
            return defaultValue;
        }
    }
}
