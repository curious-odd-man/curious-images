package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssue;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssueType;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportJobType;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
public class ImportStatsTracker {
    private final ImportJobType jobType;
    private final List<String>  rootPaths;
    private final TimeProvider  timeProvider;
    private final LocalDateTime startedAt;

    /**
     * Set once, right after {@code ImportJobStatsRepository#insertStarted} returns the generated
     * id for this run — 0 until then. Every {@link #snapshot} taken after that point carries the
     * real id, so {@code ImportJob} can target the right row for subsequent updates.
     */
    @Getter
    @Setter
    private volatile long runId;

    private final AtomicLong            photoImportedCount        = new AtomicLong();
    private final AtomicLong            photoUpdatedCount         = new AtomicLong();
    private final AtomicLong            videoImportedCount        = new AtomicLong();
    private final AtomicLong            videoUpdatedCount         = new AtomicLong();
    private final AtomicLong            bytesImported             = new AtomicLong();
    private final AtomicLong            skippedUnchangedCount     = new AtomicLong();
    private final AtomicLong            unsupportedCodecCount     = new AtomicLong();
    private final AtomicLong            unsupportedExtensionCount = new AtomicLong();
    private final List<ImportFileIssue> issues                    = Collections.synchronizedList(new ArrayList<>());

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

    /**
     * A video was discovered but rejected for an unsupported codec — counted here AND recorded as
     * a per-file {@link ImportFileIssueType#SKIPPED} issue (unlike routine unchanged-file skips).
     */
    public void recordUnsupportedCodec(String absolutePath, String reason) {
        unsupportedCodecCount.incrementAndGet();
        issues.add(new ImportFileIssue(absolutePath, ImportFileIssueType.SKIPPED, reason));
    }

    /**
     * A file was discovered whose extension isn't a supported media type at all — same
     * count-plus-issue treatment as {@link #recordUnsupportedCodec}.
     */
    public void recordUnsupportedExtension(String absolutePath, String reason) {
        unsupportedExtensionCount.incrementAndGet();
        issues.add(new ImportFileIssue(absolutePath, ImportFileIssueType.SKIPPED, reason));
    }

    public void recordFailure(String absolutePath, String reason) {
        issues.add(new ImportFileIssue(absolutePath, ImportFileIssueType.FAILED, reason));
    }

    public ImportJobStats snapshot(JobStatus status) {
        LocalDateTime         finishedAt = status == JobStatus.RUNNING ? null : timeProvider.now();
        List<ImportFileIssue> issuesCopy;
        synchronized (issues) {
            issuesCopy = List.copyOf(issues);
        }
        return new ImportJobStats(
                runId,
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
                issuesCopy
        );
    }
}
