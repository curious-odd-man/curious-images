package com.github.curiousoddman.curious_images.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportJobFileIssueRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportJobStatsRecord;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssue;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssueType;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportJobType;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.IMPORT_JOB_FILE_ISSUE;
import static com.github.curiousoddman.curious_images.dbobj.Tables.IMPORT_JOB_STATS;

/**
 * Hand-written jOOQ repository for {@code import_job_stats}/{@code import_job_file_issue} (see
 * migration {@code V9999__import_job_stats.sql} — rename to the next real Flyway version).
 * <p>
 * Every run is its own row (real history — {@code IMPORT_JOB_STATS.ID} is an identity column):
 * {@link #insertStarted} creates it when a run begins, {@link #updateProgress} updates that same
 * row throughout (throttled mid-run, then once more at the end), and {@link #insertIssues} bulk
 * inserts that run's per-file issues once, at the very end (see {@code ImportJob#finishStatsSession}).
 * Only the most recent run is currently surfaced in the UI ({@link #findLast()}), older ones stay
 * in the database for now.
 * <p>
 * Requires running jOOQ codegen after the migration is in place, the same as every other table in
 * {@code dbobj} — {@code ImportJobStatsRecord}/{@code ImportJobFileIssueRecord} aren't
 * hand-authored, they're generated like the rest.
 */
@Repository
@RequiredArgsConstructor
public class ImportJobStatsRepository {
    private final ObjectMapper mapper = new ObjectMapper();

    private final DSLContext dsl;

    @SneakyThrows
    public long insertStarted(ImportJobStats stats) {
        ImportJobStatsRecord record = dsl.insertInto(IMPORT_JOB_STATS)
                                         .set(IMPORT_JOB_STATS.JOB_TYPE, stats.jobType()
                                                                              .name())
                                         .set(IMPORT_JOB_STATS.ROOT_PATHS_JSON, mapper.writeValueAsString(stats.rootPaths()))
                                         .set(IMPORT_JOB_STATS.STATUS, stats.status()
                                                                            .name())
                                         .set(IMPORT_JOB_STATS.STARTED_AT, stats.startedAt())
                                         .set(IMPORT_JOB_STATS.FINISHED_AT, stats.finishedAt())
                                         .set(IMPORT_JOB_STATS.PHOTO_IMPORTED_COUNT, stats.photoImportedCount())
                                         .set(IMPORT_JOB_STATS.PHOTO_UPDATED_COUNT, stats.photoUpdatedCount())
                                         .set(IMPORT_JOB_STATS.VIDEO_IMPORTED_COUNT, stats.videoImportedCount())
                                         .set(IMPORT_JOB_STATS.VIDEO_UPDATED_COUNT, stats.videoUpdatedCount())
                                         .set(IMPORT_JOB_STATS.BYTES_IMPORTED, stats.bytesImported())
                                         .set(IMPORT_JOB_STATS.SKIPPED_UNCHANGED_COUNT, stats.skippedUnchangedCount())
                                         .set(IMPORT_JOB_STATS.UNSUPPORTED_CODEC_COUNT, stats.unsupportedCodecCount())
                                         .set(IMPORT_JOB_STATS.UNSUPPORTED_EXTENSION_COUNT, stats.unsupportedExtensionCount())
                                         .returning(IMPORT_JOB_STATS.ID)
                                         .fetchOne();
        return record.getId();
    }

    @SneakyThrows
    public void updateProgress(long runId, ImportJobStats stats) {
        dsl.update(IMPORT_JOB_STATS)
           .set(IMPORT_JOB_STATS.STATUS, stats.status()
                                              .name())
           .set(IMPORT_JOB_STATS.FINISHED_AT, stats.finishedAt())
           .set(IMPORT_JOB_STATS.PHOTO_IMPORTED_COUNT, stats.photoImportedCount())
           .set(IMPORT_JOB_STATS.PHOTO_UPDATED_COUNT, stats.photoUpdatedCount())
           .set(IMPORT_JOB_STATS.VIDEO_IMPORTED_COUNT, stats.videoImportedCount())
           .set(IMPORT_JOB_STATS.VIDEO_UPDATED_COUNT, stats.videoUpdatedCount())
           .set(IMPORT_JOB_STATS.BYTES_IMPORTED, stats.bytesImported())
           .set(IMPORT_JOB_STATS.SKIPPED_UNCHANGED_COUNT, stats.skippedUnchangedCount())
           .set(IMPORT_JOB_STATS.UNSUPPORTED_CODEC_COUNT, stats.unsupportedCodecCount())
           .set(IMPORT_JOB_STATS.UNSUPPORTED_EXTENSION_COUNT, stats.unsupportedExtensionCount())
           .where(IMPORT_JOB_STATS.ID.eq(runId))
           .execute();
    }

    /**
     * Bulk-inserts every issue collected during the run, keyed to it via {@code run_id}. Called
     * once, at the very end of the run (see {@code ImportJob#finishStatsSession}) — issues aren't
     * trickled in throughout, to avoid a stream of tiny inserts on top of the throttled progress
     * updates.
     */
    public void insertIssues(long runId, List<ImportFileIssue> issues) {
        if (issues.isEmpty()) {
            return;
        }
        List<Query> inserts = new ArrayList<>(issues.size());
        for (ImportFileIssue issue : issues) {
            inserts.add(dsl.insertInto(IMPORT_JOB_FILE_ISSUE)
                           .set(IMPORT_JOB_FILE_ISSUE.FILE_PATH, issue.absolutePath())
                           .set(IMPORT_JOB_FILE_ISSUE.ISSUE_TYPE, issue.type()
                                                                       .name())
                           .set(IMPORT_JOB_FILE_ISSUE.REASON, issue.reason())
                           .set(IMPORT_JOB_FILE_ISSUE.RUN_ID, runId));
        }
        dsl.batch(inserts)
           .execute();
    }

    @SneakyThrows
    public Optional<ImportJobStats> findLast() {
        ImportJobStatsRecord record = dsl.selectFrom(IMPORT_JOB_STATS)
                                         .orderBy(IMPORT_JOB_STATS.ID.desc())
                                         .limit(1)
                                         .fetchOne();
        if (record == null) {
            return Optional.empty();
        }

        List<String> rootPaths = mapper.readValue(record.getRootPathsJson(), mapper.getTypeFactory()
                                                                                   .constructCollectionType(List.class, String.class));
        List<ImportFileIssue> issues = findIssues(record.getId());

        return Optional.of(new ImportJobStats(
                record.getId(),
                ImportJobType.valueOf(record.getJobType()),
                rootPaths,
                JobStatus.valueOf(record.getStatus()),
                record.getStartedAt(),
                record.getFinishedAt(),
                record.getPhotoImportedCount(),
                record.getPhotoUpdatedCount(),
                record.getVideoImportedCount(),
                record.getVideoUpdatedCount(),
                record.getBytesImported(),
                record.getSkippedUnchangedCount(),
                record.getUnsupportedCodecCount(),
                record.getUnsupportedExtensionCount(),
                issues
        ));
    }

    private List<ImportFileIssue> findIssues(long runId) {
        return dsl.selectFrom(IMPORT_JOB_FILE_ISSUE)
                  .where(IMPORT_JOB_FILE_ISSUE.RUN_ID.eq(runId))
                  .fetch()
                  .map(this::toIssue);
    }

    private ImportFileIssue toIssue(ImportJobFileIssueRecord record) {
        return new ImportFileIssue(
                record.getFilePath(),
                ImportFileIssueType.valueOf(record.getIssueType()),
                record.getReason()
        );
    }
}
