package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.domain.imports.ImportFailureDetail;
import com.github.curiousoddman.curious_images.domain.imports.ImportJobType;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full statistics snapshot for one import job run (a single {@code ImportJob}/{@code AddFilesJob}
 * lifetime, which may internally scan several {@code IMPORT_ROOT}s — all of it is one row here).
 * Only the most recent snapshot is kept (see {@code ImportJobStatsRepository#findLast()}); a new
 * run overwrites it.
 * <p>
 * {@code status} is {@link JobStatus#RUNNING} while the job is in progress — the tracker persists
 * and publishes a throttled snapshot as it goes so the tree item / view can update live — and
 * settles to {@link JobStatus#COMPLETED}, {@link JobStatus#FAILED}, or {@link JobStatus#INTERRUPTED}
 * once the job ends.
 *
 * @param jobType                  how this run was triggered
 * @param rootPaths                the import root path(s) scanned during this run
 * @param status                   current/final status of the run
 * @param startedAt                when the run started
 * @param finishedAt               when the run ended, or {@code null} while still running
 * @param photoImportedCount       brand-new photo rows created
 * @param photoUpdatedCount        existing photo rows updated (file changed on disk)
 * @param videoImportedCount       brand-new video rows created
 * @param videoUpdatedCount        existing video rows updated (file changed on disk)
 * @param bytesImported            total size, in bytes, of every imported + updated file
 * @param skippedUnchangedCount    files already known, unchanged since last scan
 * @param unsupportedCodecCount    videos discovered but rejected for an unsupported codec
 * @param unsupportedExtensionCount files discovered whose extension isn't a supported media type at all
 * @param failures                 individual failed files, each with its own reason
 */
public record ImportJobStats(
        long id,
        ImportJobType jobType,
        List<String> rootPaths,
        JobStatus status,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        long photoImportedCount,
        long photoUpdatedCount,
        long videoImportedCount,
        long videoUpdatedCount,
        long bytesImported,
        long skippedUnchangedCount,
        long unsupportedCodecCount,
        long unsupportedExtensionCount,
        List<ImportFailureDetail> failures
) {
    public long photoCount() {
        return photoImportedCount + photoUpdatedCount;
    }

    public long videoCount() {
        return videoImportedCount + videoUpdatedCount;
    }

    public long ignoredCount() {
        return skippedUnchangedCount + unsupportedCodecCount + unsupportedExtensionCount;
    }

    public long failedCount() {
        return failures.size();
    }
}
