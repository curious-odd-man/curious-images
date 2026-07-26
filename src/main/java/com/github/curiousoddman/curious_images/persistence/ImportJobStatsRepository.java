package com.github.curiousoddman.curious_images.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ImportJobStatsRecord;
import com.github.curiousoddman.curious_images.domain.imports.ImportFailureDetail;
import com.github.curiousoddman.curious_images.domain.imports.ImportJobType;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.IMPORT_JOB_STATS;

/**
 * Hand-written jOOQ repository for {@code import_job_stats} (see migration
 * {@code V9999__import_job_stats.sql} — rename to the next real Flyway version). Only the most
 * recent import job's stats are ever kept, at a fixed row id ({@link #ROW_ID}); every run
 * overwrites it via {@link #upsert}, so "last import job" is always just that one row.
 * <p>
 * Requires running jOOQ codegen after the migration is in place, the same as every other table in
 * {@code dbobj} — {@code ImportJobStatsRecord} isn't hand-authored, it's generated like the rest.
 */
@Repository
@RequiredArgsConstructor
public class ImportJobStatsRepository {
    private static final long ROW_ID = 1L;

    private final ObjectMapper mapper = new ObjectMapper();

    private final DSLContext dsl;

    @SneakyThrows
    public void upsert(ImportJobStats stats) {
        String rootPathsJson = mapper.writeValueAsString(stats.rootPaths());
        String failuresJson  = mapper.writeValueAsString(stats.failures());

        dsl.insertInto(IMPORT_JOB_STATS)
           .set(IMPORT_JOB_STATS.ID, ROW_ID)
           .set(IMPORT_JOB_STATS.JOB_TYPE, stats.jobType().name())
           .set(IMPORT_JOB_STATS.ROOT_PATHS_JSON, rootPathsJson)
           .set(IMPORT_JOB_STATS.STATUS, stats.status().name())
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
           .set(IMPORT_JOB_STATS.FAILURES_JSON, failuresJson)
           .onDuplicateKeyUpdate()
           .set(IMPORT_JOB_STATS.JOB_TYPE, stats.jobType().name())
           .set(IMPORT_JOB_STATS.ROOT_PATHS_JSON, rootPathsJson)
           .set(IMPORT_JOB_STATS.STATUS, stats.status().name())
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
           .set(IMPORT_JOB_STATS.FAILURES_JSON, failuresJson)
           .execute();
    }

    @SneakyThrows
    public Optional<ImportJobStats> findLast() {
        ImportJobStatsRecord record = dsl.selectFrom(IMPORT_JOB_STATS)
                                         .where(IMPORT_JOB_STATS.ID.eq(ROW_ID))
                                         .fetchOne();
        if (record == null) {
            return Optional.empty();
        }

        List<String> rootPaths = mapper.readValue(record.getRootPathsJson(), mapper.getTypeFactory()
                                                                                   .constructCollectionType(List.class, String.class));
        List<ImportFailureDetail> failures = mapper.readValue(record.getFailuresJson(), mapper.getTypeFactory()
                                                                                              .constructCollectionType(List.class, ImportFailureDetail.class));

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
                failures
        ));
    }
}
