package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.domain.common.MediaExtensions;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedVideoMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.domain.imports.metadata.UnsupportedVideoCodecException;
import com.github.curiousoddman.curious_images.domain.imports.metadata.VideoMetadataExtractor;
import com.github.curiousoddman.curious_images.event.model.ImportStatsUpdatedEvent;
import com.github.curiousoddman.curious_images.event.model.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportJobStatsRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository.ExistingMediaSummary;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.util.FileCollectingVisitor;
import com.github.curiousoddman.curious_images.util.FileUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import com.github.curiousoddman.curious_images.util.async.jobs.JobStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.impl.DSL;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Slf4j
@RequiredArgsConstructor
public class ImportJob extends BackgroundJob {

    public static final String IMPORT_SCAN = "Import Scan";

    /**
     * MP4/MOV/M4V containers only — codec is validated per-file at import time (H.264 video +
     * AAC audio only, see implementation plan "Accepted formats" decision); a container with
     * these extensions but a different codec is discovered and rejected in {@link #importOneVideo}.
     */
    private static final Set<String> VIDEO_EXTENSIONS         = MediaExtensions.VIDEO_EXTENSIONS;
    private static final Set<String> SUPPORTED_EXTENSIONS     = MediaExtensions.SUPPORTED_EXTENSIONS;
    private static final int         DB_FLUSH_BATCH_SIZE      = 200;
    private static final int         APPROXIMATE_LIBRARY_SIZE = 25_000;

    /**
     * Minimum interval between throttled stats persist/publish calls during a run — deliberately
     * coarser than {@code BackgroundJob}'s own progress-publish throttle, since this hits the DB
     * on every publish rather than just the event bus.
     */
    private static final long STATS_PUBLISH_INTERVAL_MS = 500;

    private final DSLContext               dsl;
    private final ImportRootRepository     importRootRepository;
    private final FolderRepository         folderRepository;
    private final MediaRepository          mediaRepository;
    private final PhotoPreviewRepository   photoPreviewRepository;
    private final PhotoMetadataExtractor   metadataExtractor;
    private final VideoMetadataExtractor   videoMetadataExtractor;
    private final ImportJobStatsRepository importJobStatsRepository;
    private final TimeProvider             timeProvider;
    private final List<String>             rootPaths;

    /**
     * Non-null for the duration of one "session" — everything between {@link #beginStatsSession}
     * and {@link #finishStatsSession}, which may span several {@link #runImportInternal} calls
     * across several roots (see {@code AddFilesJob}, which drives this job's scan method directly
     * without going through {@link #runImpl()}).
     */
    private volatile ImportStatsTracker statsTracker;
    private volatile boolean            sessionHadRootLevelFailure;
    private volatile long               lastStatsPublishMs;

    @Override
    public void runImpl() throws Exception {
        beginStatsSession(ImportJobType.RESCAN, rootPaths);
        boolean interrupted = false;
        for (int i = 0; i < rootPaths.size(); i++) {
            if (isInterruptRequested()) {
                publishInterrupted();
                interrupted = true;
                break;
            }
            log.info("Multi-root scan: root {} of {}: {}", i + 1, rootPaths.size(), rootPaths.get(i));
            runImportInternal(rootPaths.get(i));
        }
        finishStatsSession(interrupted);
        if (!interrupted) {
            eventPublisher.publishEvent(new LibraryUpdatedEvent(this));
        }
    }

    // ── Stats session (see ImportStatsTracker) ─────────────────────────────────

    /**
     * Starts a new stats-tracking session for one whole job run. Called by {@link #runImpl()}
     * itself for a plain rescan, or by {@code AddFilesJob} before it drives
     * {@link #runImportInternal} directly for each of its (possibly copied-to) roots.
     */
    void beginStatsSession(ImportJobType jobType, List<String> effectiveRootPaths) {
        sessionHadRootLevelFailure = false;
        statsTracker = new ImportStatsTracker(jobType, effectiveRootPaths, timeProvider);
        lastStatsPublishMs = 0;
        persistAndPublishStats(JobStatus.RUNNING);
    }

    /**
     * Ends the current stats-tracking session: persists and publishes the final snapshot, then
     * clears the tracker so a stale one can't leak into the next run.
     */
    void finishStatsSession(boolean interrupted) {
        if (statsTracker == null) {
            return;
        }
        JobStatus finalStatus = interrupted
                ? JobStatus.INTERRUPTED
                : (sessionHadRootLevelFailure ? JobStatus.FAILED : JobStatus.COMPLETED);
        persistAndPublishStats(finalStatus);
        statsTracker = null;
    }

    private void persistAndPublishStats(JobStatus status) {
        if (statsTracker == null) {
            return;
        }
        ImportJobStats snapshot = statsTracker.snapshot(status);
        importJobStatsRepository.upsert(snapshot);
        eventPublisher.publishEvent(new ImportStatsUpdatedEvent(this, snapshot));
    }

    private synchronized void persistAndPublishStatsThrottled(boolean force) {
        if (statsTracker == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        if (!force && nowMs - lastStatsPublishMs < STATS_PUBLISH_INTERVAL_MS) {
            return;
        }
        lastStatsPublishMs = nowMs;
        persistAndPublishStats(JobStatus.RUNNING);
    }

    // ── Internal scan (synchronous, called on the background thread) ──────────

    void runImportInternal(String rootPathString) {
        log.info("Starting import scan of {}", rootPathString);
        publishStarted("Discovering files…");
        int imported      = 0;
        int skipped       = 0;
        int errors        = 0;
        int rejectedCodec = 0;
        try {
            Path            rootPath      = Path.of(rootPathString);
            long            importRootId  = importRootRepository.findOrCreate(rootPathString, timeProvider.now());
            Map<Path, Long> folderIdCache = new HashMap<>();

            ScanResult scanResult = scan(rootPath);
            List<Path> files      = scanResult.files();
            if (statsTracker != null) {
                statsTracker.recordUnsupportedExtension(scanResult.unsupportedExtensionCount());
            }
            log.info("Discovered {} supported files under {}", files.size(), rootPathString);
            publishInProgress("Reading files", 0, files.size());

            List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < files.size(); i++) {
                if (isInterruptRequested()) {
                    flush(buffer);
                    log.info("Import scan interrupted after {} files", i);
                    persistAndPublishStatsThrottled(true);
                    return; // caller handles publishInterrupted
                }

                Path file = files.get(i);
                try {
                    FileImportResult result = importOneFile(rootPath, importRootId, folderIdCache, file, buffer);
                    switch (result.outcome()) {
                        case SKIPPED_UNCHANGED -> {
                            skipped++;
                            recordOutcome(result);
                        }
                        case REJECTED_UNSUPPORTED_CODEC -> {
                            rejectedCodec++;
                            recordOutcome(result);
                        }
                        default -> {
                            imported++;
                            recordOutcome(result);
                        }
                    }
                } catch (Exception e) {
                    errors++;
                    log.error("Failed to import {}", file, e);
                    if (statsTracker != null) {
                        statsTracker.recordFailure(file.toString(), e.getMessage() == null ? e.toString() : e.getMessage());
                    }
                }

                if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                    flush(buffer);
                }
                publishProgressThrottled("Reading files", i + 1, files.size(),
                        file.toString(), i == files.size() - 1);
                persistAndPublishStatsThrottled(i == files.size() - 1);
            }
            flush(buffer);

            importRootRepository.updateLastScannedAt(importRootId, timeProvider.now());
            log.info("Import scan of {} completed: {} imported/updated, {} skipped, {} unsupported codec, {} errors",
                    rootPathString, imported, skipped, rejectedCodec, errors);
            publishEnded("Imported/updated %d, skipped %d unchanged, %d unsupported codec, %d errors"
                    .formatted(imported, skipped, rejectedCodec, errors));
        } catch (Exception e) {
            log.error("Import scan of {} failed", rootPathString, e);
            sessionHadRootLevelFailure = true;
            if (statsTracker != null) {
                statsTracker.recordFailure(rootPathString, e.getMessage() == null ? e.toString() : e.getMessage());
            }
            publishFailed(e);
        }
    }

    private void recordOutcome(FileImportResult result) {
        if (statsTracker == null) {
            return;
        }
        switch (result.outcome()) {
            case SKIPPED_UNCHANGED -> statsTracker.recordSkippedUnchanged();
            case REJECTED_UNSUPPORTED_CODEC -> statsTracker.recordUnsupportedCodec();
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

    // ── File-level import (metadata only — no thumbnail generation) ───────────

    private FileImportResult importOneFile(Path rootPath, long importRootId, Map<Path, Long> folderIdCache,
                                           Path file, List<Query> buffer) throws IOException {
        String filename = file.getFileName()
                              .toString();
        String extension = FileUtils.extensionOf(filename);

        if (VIDEO_EXTENSIONS.contains(extension)) {
            return importOneVideo(rootPath, importRootId, folderIdCache, file, filename, extension, buffer);
        }
        return importOnePhoto(rootPath, importRootId, folderIdCache, file, filename, extension, buffer);
    }

    private FileImportResult importOnePhoto(Path rootPath, long importRootId, Map<Path, Long> folderIdCache,
                                            Path file, String filename, String extension,
                                            List<Query> buffer) throws IOException {
        long folderId = resolveFolderId(importRootId, rootPath, file.getParent(), folderIdCache);
        String absolutePath = file.toAbsolutePath()
                                  .toString();
        long          fileSize = Files.size(file);
        LocalDateTime now      = timeProvider.now();

        Optional<ExistingMediaSummary> existing = mediaRepository.findExistingMediaSummaryByAbsolutePath(absolutePath);

        if (existing.isPresent() && existing.get()
                                            .fileSize() == fileSize) {
            // Cheap rescan: file unchanged. Touch last_seen_at; do NOT reset AI status flags —
            // partial AI progress from a previous run is preserved.
            buffer.add(mediaRepository.touchLastSeenAtQuery(existing.get()
                                                                    .id(), now));
            return new FileImportResult(ImportOutcome.SKIPPED_UNCHANGED, false, fileSize);
        }

        ExtractedMetadata metadata = metadataExtractor.extract(file, extension);

        if (existing.isPresent()) {
            long photoId = existing.get()
                                   .id();
            buffer.addAll(mediaRepository.updateMetadataQuery(photoId, fileSize,
                    metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(),
                    metadata.orientationDegrees(), metadata.cameraMake(),
                    metadata.cameraModel(), metadata.lensModel(),
                    metadata.exifExtraJson(), now));
            queuePreview(photoId, metadata, buffer);
            buffer.add(mediaRepository.resetAiFields(photoId));
            return new FileImportResult(ImportOutcome.UPDATED, false, fileSize);
        }

        // Brand-new media: insert returns the generated ID immediately.
        long photoId = mediaRepository.insertPhoto(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(),
                metadata.captureDate(), metadata.captureDateSource(),
                metadata.orientationDegrees(), metadata.cameraMake(),
                metadata.cameraModel(), metadata.lensModel(),
                metadata.exifExtraJson(), now);
        queuePreview(photoId, metadata, buffer);
        return new FileImportResult(ImportOutcome.IMPORTED, false, fileSize);
    }

    /**
     * Video counterpart to {@link #importOnePhoto} (see implementation plan §2). The only branch
     * point beyond "which extractor/repository method to call" is codec validation: a video whose
     * codec {@link VideoMetadataExtractor} rejects is neither inserted nor updated — it's simply
     * reported as skipped, same file-level isolation as a corrupt/unreadable photo.
     */
    private FileImportResult importOneVideo(Path rootPath, long importRootId, Map<Path, Long> folderIdCache,
                                            Path file, String filename, String extension,
                                            List<Query> buffer) throws IOException {
        long folderId = resolveFolderId(importRootId, rootPath, file.getParent(), folderIdCache);
        String absolutePath = file.toAbsolutePath()
                                  .toString();
        long          fileSize = Files.size(file);
        LocalDateTime now      = timeProvider.now();

        Optional<ExistingMediaSummary> existing = mediaRepository.findExistingMediaSummaryByAbsolutePath(absolutePath);

        if (existing.isPresent() && existing.get()
                                            .fileSize() == fileSize) {
            buffer.add(mediaRepository.touchLastSeenAtQuery(existing.get()
                                                                    .id(), now));
            return new FileImportResult(ImportOutcome.SKIPPED_UNCHANGED, true, fileSize);
        }

        ExtractedVideoMetadata metadata;
        try {
            metadata = videoMetadataExtractor.extract(file);
        } catch (UnsupportedVideoCodecException e) {
            // Reported in the ENDED summary's "unsupported codec" count — see runImportInternal.
            log.warn("Skipping unsupported video codec: {}", e.getMessage());
            return new FileImportResult(ImportOutcome.REJECTED_UNSUPPORTED_CODEC, true, fileSize);
        }

        if (existing.isPresent()) {
            long videoId = existing.get()
                                   .id();
            buffer.addAll(mediaRepository.updateVideoMetadataQuery(videoId, fileSize,
                    metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(),
                    metadata.durationMs(), metadata.codec(), metadata.frameRate(), metadata.rotationDegrees(),
                    metadata.gpsLat(), metadata.gpsLon(), metadata.gpsAltitude(), now));
            buffer.add(mediaRepository.resetAiFields(videoId));
            return new FileImportResult(ImportOutcome.UPDATED, true, fileSize);
        }

        mediaRepository.insertVideo(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(),
                metadata.captureDate(), metadata.captureDateSource(),
                metadata.durationMs(), metadata.codec(), metadata.frameRate(), metadata.rotationDegrees(),
                metadata.gpsLat(), metadata.gpsLon(), metadata.gpsAltitude(), now);
        return new FileImportResult(ImportOutcome.IMPORTED, true, fileSize);
    }

    /**
     * Phase 1 does <b>no</b> thumbnail generation at all — see implementation plan. The only
     * per-file write beyond {@code PHOTO} itself is this quick-preview upsert, and only when
     * {@link PhotoMetadataExtractor} found an embedded EXIF preview (JPEG only) while parsing
     * metadata above — piggybacked onto the same batched flush, so it costs no extra disk writes.
     * The real thumbnail is generated later, on demand, by {@code ThumbnailGenerationJob} — only
     * for photos the UI actually asks to see.
     */
    private void queuePreview(long photoId, ExtractedMetadata metadata, List<Query> buffer) {
        if (metadata.embeddedPreviewBytes() != null) {
            buffer.add(photoPreviewRepository.upsertQuery(photoId, metadata.embeddedPreviewBytes()));
        }
    }

    private long resolveFolderId(long importRootId, Path rootPath, Path dir, Map<Path, Long> folderIdCache) {
        Long cached = folderIdCache.get(dir);
        if (cached != null) {
            return cached;
        }

        long id;
        if (dir.equals(rootPath)) {
            Path fileName = rootPath.getFileName();
            String rootName = fileName != null
                    ? fileName.toString()
                    : rootPath.toString();
            id = folderRepository.findOrCreate(importRootId, null, "", rootName);
        } else {
            long parentId = resolveFolderId(importRootId, rootPath, dir.getParent(), folderIdCache);
            String relativePath = rootPath.relativize(dir)
                                          .toString();
            id = folderRepository.findOrCreate(importRootId, parentId, relativePath,
                    dir.getFileName()
                       .toString());
        }
        folderIdCache.put(dir, id);
        return id;
    }

    private void flush(List<Query> buffer) {
        if (buffer.isEmpty()) {
            return;
        }
        dsl.transaction(configuration -> DSL.using(configuration)
                                            .batch(buffer)
                                            .execute());
        buffer.clear();
    }

    private ScanResult scan(Path rootPath) throws IOException {
        List<Path>            found   = new ArrayList<>(APPROXIMATE_LIBRARY_SIZE);
        FileCollectingVisitor visitor = new FileCollectingVisitor(SUPPORTED_EXTENSIONS, found);
        Files.walkFileTree(rootPath, visitor);
        return new ScanResult(found, visitor.getUnsupportedExtensionCount());
    }

    @Override
    public String getProcessName() {
        return IMPORT_SCAN;
    }

    /**
     * One file's import result, carrying just enough beyond {@link ImportOutcome} for
     * {@link #recordOutcome} to feed the right counters on {@link ImportStatsTracker}.
     */
    private record FileImportResult(ImportOutcome outcome, boolean video, long fileSize) {}

    private record ScanResult(List<Path> files, long unsupportedExtensionCount) {}
}
