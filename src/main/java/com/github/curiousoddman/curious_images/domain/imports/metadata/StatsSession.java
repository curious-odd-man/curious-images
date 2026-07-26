package com.github.curiousoddman.curious_images.domain.imports.metadata;

import com.github.curiousoddman.curious_images.domain.imports.ImportStatsTracker;
import com.github.curiousoddman.curious_images.domain.imports.data.FileImportResult;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportJobType;
import com.github.curiousoddman.curious_images.domain.imports.data.ScanResult;
import com.github.curiousoddman.curious_images.event.model.ImportStatsUpdatedEvent;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.persistence.ImportJobStatsRepository;
import com.github.curiousoddman.curious_images.util.FileUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
public class StatsSession implements AutoCloseable {
    private static final long STATS_PUBLISH_INTERVAL_MS = 500;

    private final ImportJobStatsRepository  importJobStatsRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TimeProvider              timeProvider;
    private final AtomicBoolean             interrupted;

    private volatile ImportStatsTracker statsTracker;
    private volatile boolean            sessionHadRootLevelFailure;
    private volatile long               lastStatsPublishMs;

    public StatsSession begin(ImportJobType jobType, List<String> effectiveRootPaths) {
        sessionHadRootLevelFailure = false;
        statsTracker = new ImportStatsTracker(jobType, effectiveRootPaths, timeProvider);
        lastStatsPublishMs = 0;
        long runId = importJobStatsRepository.insertStarted(statsTracker.snapshot(JobStatus.RUNNING));
        statsTracker.setRunId(runId);
        eventPublisher.publishEvent(new ImportStatsUpdatedEvent(this, statsTracker.snapshot(JobStatus.RUNNING)));
        return this;
    }

    public void persistAndPublishStats(JobStatus status) {
        ImportJobStats snapshot = statsTracker.snapshot(status);
        importJobStatsRepository.updateProgress(snapshot.id(), snapshot);
        eventPublisher.publishEvent(new ImportStatsUpdatedEvent(this, snapshot));
    }

    public synchronized void persistAndPublishStatsThrottled(boolean force) {
        long nowMs = System.currentTimeMillis();
        if (!force && nowMs - lastStatsPublishMs < STATS_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastStatsPublishMs = nowMs;
        persistAndPublishStats(JobStatus.RUNNING);
    }

    @Override
    public void close() throws Exception {
        JobStatus finalStatus = interrupted.get()
                ? JobStatus.INTERRUPTED
                : (sessionHadRootLevelFailure ? JobStatus.FAILED : JobStatus.COMPLETED);
        ImportJobStats snapshot = statsTracker.snapshot(finalStatus);
        importJobStatsRepository.updateProgress(snapshot.id(), snapshot);
        importJobStatsRepository.insertIssues(snapshot.id(), snapshot.issues());
        eventPublisher.publishEvent(new ImportStatsUpdatedEvent(this, snapshot));
    }

    public void reportUnsupportedFileExtensions(ScanResult scanResult) {
        for (Path unsupported : scanResult.unsupportedExtensionFiles()) {
            String extension = FileUtils.extensionOf(unsupported.getFileName()
                                                                .toString());
            statsTracker.recordUnsupportedExtension(unsupported.toString(),
                    "Unsupported extension: ." + extension);
        }
    }

    public void recordFailure(Path file, Exception e) {
        statsTracker.recordFailure(file.toString(), e.getMessage() == null ? e.toString() : e.getMessage());

    }

    public void recordRootLevelFailure(String rootPathString, Exception e) {
        sessionHadRootLevelFailure = true;
        statsTracker.recordFailure(rootPathString, e.getMessage() == null ? e.toString() : e.getMessage());
    }

    public void recordOutcome(FileImportResult result, Path file) {
        switch (result.outcome()) {
            case SKIPPED_UNCHANGED -> statsTracker.recordSkippedUnchanged();
            case REJECTED_UNSUPPORTED_CODEC -> statsTracker.recordUnsupportedCodec(file.toString(),
                    result.reason() != null ? result.reason() : "Unsupported codec");
            case IMPORTED -> {
                if (result.video()) {
                    statsTracker.recordVideoImported(result.fileSize());
                } else {
                    statsTracker.recordPhotoImported(result.fileSize());
                }
            }
            case UPDATED -> {
                if (result.video()) {
                    statsTracker.recordVideoUpdated(result.fileSize());
                } else {
                    statsTracker.recordPhotoUpdated(result.fileSize());
                }
            }
        }
    }

    public ImportJobStats getStats(JobStatus status) {
        return statsTracker.snapshot(status);
    }
}
