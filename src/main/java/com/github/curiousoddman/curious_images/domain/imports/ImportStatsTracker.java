package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Accumulates counters and failure details across one whole job run — which, for
 * {@code AddFilesJob}, spans every root it hands off to {@link ImportJob#runImportInternal}, not
 * just a single root. Created fresh per run by {@link ImportJob#beginStatsSession} and read back
 * via {@link #snapshot(JobStatus)} both mid-run (throttled, for live updates) and once at the end.
 * <p>
 * Thread-safe: {@code ImportJob} only ever drives one run at a time on its background thread, but
 * counters use atomics defensively since {@code snapshot()} can be called concurrently with
 * persistence/event-publishing.
 */
public class ImportStatsTracker {
    private final ImportJobType jobType;
    private final List<String>  rootPaths;
    private final TimeProvider  timeProvider;
    private final LocalDateTime startedAt;

    private final AtomicLong photoImportedCount        = new AtomicLong();
    private final AtomicLong photoUpdatedCount          = new AtomicLong();
    private final AtomicLong videoImportedCount         = new AtomicLong();
    private final AtomicLong videoUpdatedCount          = new AtomicLong();
    private final AtomicLong bytesImported              = new AtomicLong();
    private final AtomicLong skippedUnchangedCount       = new AtomicLong();
    private final AtomicLong unsupportedCodecCount       = new AtomicLong();
    private final AtomicLong unsupportedExtensionCount   = new AtomicLong();
    private final List<ImportFailureDetail> failures = Collections.synchronizedList(new ArrayList<>());

    public ImportStatsTracker(ImportJobType jobType, List<String> rootPaths, TimeProvider timeProvider) {
        this.jobType = jobType;
        this.rootPaths = List.copyOf(rootPaths);
        this.timeProvider = timeProvider;
        this.startedAt = timeProvider.now();
    }

    public void recordPhotoImported(long fileSize) {
        photoImportedCount.incrementAndGet();
        bytesImported.addAndGet(fileSize);
    }

    public void recordPhotoUpdated(long fileSize) {
        photoUpdatedCount.incrementAndGet();
        bytesImported.addAndGet(fileSize);
    }

    public void recordVideoImported(long fileSize) {
        videoImportedCount.incrementAndGet();
        bytesImported.addAndGet(fileSize);
    }

    public void recordVideoUpdated(long fileSize) {
        videoUpdatedCount.incrementAndGet();
        bytesImported.addAndGet(fileSize);
    }

    public void recordSkippedUnchanged() {
        skippedUnchangedCount.incrementAndGet();
    }

    public void recordUnsupportedCodec() {
        unsupportedCodecCount.incrementAndGet();
    }

    public void recordUnsupportedExtension(long count) {
        unsupportedExtensionCount.addAndGet(count);
    }

    public void recordFailure(String absolutePath, String reason) {
        failures.add(new ImportFailureDetail(absolutePath, reason));
    }

    public ImportJobStats snapshot(JobStatus status) {
        LocalDateTime finishedAt = status == JobStatus.RUNNING ? null : timeProvider.now();
        List<ImportFailureDetail> failuresCopy;
        synchronized (failures) {
            failuresCopy = List.copyOf(failures);
        }
        return new ImportJobStats(
                0L,
                jobType,
                rootPaths,
                status,
                startedAt,
                finishedAt,
                photoImportedCount.get(),
                photoUpdatedCount.get(),
                videoImportedCount.get(),
                videoUpdatedCount.get(),
                bytesImported.get(),
                skippedUnchangedCount.get(),
                unsupportedCodecCount.get(),
                unsupportedExtensionCount.get(),
                failuresCopy
        );
    }
}
