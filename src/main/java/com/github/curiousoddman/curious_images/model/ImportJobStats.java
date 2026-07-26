package com.github.curiousoddman.curious_images.model;

import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssue;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssueType;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportJobType;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Full statistics snapshot for one import job run (a single {@code ImportJob}/{@code AddFilesJob}
 * lifetime, which may internally scan several {@code IMPORT_ROOT}s — all of it is one row here).
 * Every run gets its own persisted row/id (real history — see {@code ImportJobStatsRepository});
 * {@code id} is 0 for an in-memory snapshot that hasn't been inserted yet. Only the most recent
 * run is currently surfaced in the UI (see {@code ImportJobStatsRepository#findLast()}), but older
 * runs remain in the database.
 * <p>
 * {@code status} is {@link JobStatus#RUNNING} while the job is in progress — the tracker persists
 * and publishes a throttled snapshot as it goes so the tree item / view can update live — and
 * settles to {@link JobStatus#COMPLETED}, {@link JobStatus#FAILED}, or {@link JobStatus#INTERRUPTED}
 * once the job ends.
 *
 * @param id                        this run's id (0 until inserted)
 * @param jobType                   how this run was triggered
 * @param rootPaths                 the import root path(s) scanned during this run
 * @param status                    current/final status of the run
 * @param startedAt                 when the run started
 * @param finishedAt                when the run ended, or {@code null} while still running
 * @param photoImportedCount        brand-new photo rows created
 * @param photoUpdatedCount         existing photo rows updated (file changed on disk)
 * @param videoImportedCount        brand-new video rows created
 * @param videoUpdatedCount         existing video rows updated (file changed on disk)
 * @param bytesImported             total size, in bytes, of every imported + updated file
 * @param skippedUnchangedCount     files already known, unchanged since last scan (aggregate only —
 *                                  routine, so no per-file {@code issues} row for these)
 * @param unsupportedCodecCount     videos discovered but rejected for an unsupported codec
 * @param unsupportedExtensionCount files discovered whose extension isn't a supported media type at all
 * @param issues                    individual skipped (codec/extension only) or failed files, each
 *                                  with its own reason — see {@code IMPORT_JOB_FILE_ISSUE}
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
        List<ImportFileIssue> issues
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
        return issues.stream()
                     .filter(i -> i.type() == ImportFileIssueType.FAILED)
                     .count();
    }

    public long skippedIssueCount() {
        return issues.stream()
                     .filter(i -> i.type() == ImportFileIssueType.SKIPPED)
                     .count();
    }

    public List<ImportFileIssue> skippedIssues() {
        return issues.stream()
                     .filter(i -> i.type() == ImportFileIssueType.SKIPPED)
                     .toList();
    }

    public List<ImportFileIssue> failedIssues() {
        return issues.stream()
                     .filter(i -> i.type() == ImportFileIssueType.FAILED)
                     .toList();
    }

    public long importedCount() {
        return photoImportedCount + videoImportedCount;
    }

    public long rejectedCodecCount() {
        return unsupportedCodecCount;
    }
}
