package com.github.curiousoddman.curious_images.domain.imports;

import com.github.curiousoddman.curious_images.domain.common.MediaExtensions;
import com.github.curiousoddman.curious_images.domain.imports.data.FileImportResult;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportJobType;
import com.github.curiousoddman.curious_images.domain.imports.data.ImportOutcome;
import com.github.curiousoddman.curious_images.domain.imports.data.ScanResult;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.ExtractedVideoMetadata;
import com.github.curiousoddman.curious_images.domain.imports.metadata.PhotoMetadataExtractor;
import com.github.curiousoddman.curious_images.domain.imports.metadata.StatsSession;
import com.github.curiousoddman.curious_images.domain.imports.metadata.StatsSessionFactory;
import com.github.curiousoddman.curious_images.domain.imports.metadata.UnsupportedVideoCodecException;
import com.github.curiousoddman.curious_images.domain.imports.metadata.VideoMetadataExtractor;
import com.github.curiousoddman.curious_images.event.model.LibraryUpdatedEvent;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.persistence.FolderRepository;
import com.github.curiousoddman.curious_images.persistence.ImportRootRepository;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository;
import com.github.curiousoddman.curious_images.persistence.MediaMetadataEditRepository.Field;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository.ExistingMediaSummary;
import com.github.curiousoddman.curious_images.persistence.PhotoPreviewRepository;
import com.github.curiousoddman.curious_images.util.FileCollectingVisitor;
import com.github.curiousoddman.curious_images.util.FileUtils;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
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
import java.util.concurrent.atomic.AtomicBoolean;

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

    private final DSLContext                  dsl;
    private final ImportRootRepository        importRootRepository;
    private final FolderRepository            folderRepository;
    private final MediaRepository             mediaRepository;
    private final MediaMetadataEditRepository metadataEditRepository;
    private final PhotoPreviewRepository      photoPreviewRepository;
    private final PhotoMetadataExtractor      metadataExtractor;
    private final VideoMetadataExtractor      videoMetadataExtractor;
    private final TimeProvider                timeProvider;
    private final List<String>                rootPaths;
    private final StatsSessionFactory         statsSessionFactory;

    @Override
    public void runImpl() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean(false);
        try (StatsSession statsSession = statsSessionFactory.getSession(ImportJobType.RESCAN, rootPaths, interrupted)) {
            for (int i = 0; i < rootPaths.size(); i++) {
                if (isInterruptRequested()) {
                    publishInterrupted();
                    interrupted.set(true);
                    break;
                }
                log.info("Multi-root scan: root {} of {}: {}", i + 1, rootPaths.size(), rootPaths.get(i));
                runImportInternal(statsSession, rootPaths.get(i));
            }
        }
        if (!interrupted.get()) {
            eventPublisher.publishEvent(new LibraryUpdatedEvent(this));
        }
    }

    // ── Internal scan (synchronous, called on the background thread) ──────────
    void runImportInternal(StatsSession statsSession, String rootPathString) {
        log.info("Starting import scan of {}", rootPathString);
        publishStarted("Discovering files…");
        try {
            Path            rootPath      = Path.of(rootPathString);
            long            importRootId  = importRootRepository.findOrCreate(rootPathString, timeProvider.now());
            Map<Path, Long> folderIdCache = new HashMap<>();

            ScanResult scanResult = scan(rootPath);
            List<Path> files      = scanResult.files();
            statsSession.reportUnsupportedFileExtensions(scanResult);
            log.info("Discovered {} supported files under {}", files.size(), rootPathString);
            publishInProgress("Reading files", 0, files.size());

            List<Query> buffer = new ArrayList<>(DB_FLUSH_BATCH_SIZE);
            for (int i = 0; i < files.size(); i++) {
                if (isInterruptRequested()) {
                    flush(buffer);
                    log.info("Import scan interrupted after {} files", i);
                    statsSession.persistAndPublishStatsThrottled(true);
                    return; // caller handles publishInterrupted
                }

                Path file = files.get(i);
                try {
                    FileImportResult result = importOneFile(rootPath, importRootId, folderIdCache, file, buffer);
                    statsSession.recordOutcome(result, file);
                } catch (Exception e) {
                    log.error("Failed to import {}", file, e);
                    statsSession.recordFailure(file, e);
                }

                if (buffer.size() >= DB_FLUSH_BATCH_SIZE) {
                    flush(buffer);
                }
                publishProgressThrottled("Reading files", i + 1, files.size(),
                        file.toString(), i == files.size() - 1);
                statsSession.persistAndPublishStatsThrottled(i == files.size() - 1);
            }
            flush(buffer);

            importRootRepository.updateLastScannedAt(importRootId, timeProvider.now());
            ImportJobStats stats = statsSession.getStats(getJobStatus());
            publishEnded("Imported/updated %d, skipped %d unchanged, %d unsupported codec, %d errors"
                    .formatted(stats.importedCount(), stats.skippedUnchangedCount(), stats.rejectedCodecCount(), stats.failedCount()));
        } catch (Exception e) {
            log.error("Import scan of {} failed", rootPathString, e);
            statsSession.recordRootLevelFailure(rootPathString, e);
            publishFailed(e);
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
            return new FileImportResult(ImportOutcome.SKIPPED_UNCHANGED, false, fileSize, null);
        }

        ExtractedMetadata metadata = metadataExtractor.extract(file, extension);

        if (existing.isPresent()) {
            long photoId = existing.get()
                                   .id();
            boolean captureDateProtected = metadataEditRepository.hasManualEdit(photoId, Field.CAPTURE_DATE);
            boolean orientationProtected = metadataEditRepository.hasManualEdit(photoId, Field.ROTATION);
            buffer.addAll(mediaRepository.updateMetadataQueryProtected(photoId, fileSize,
                    metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(),
                    metadata.orientationDegrees(), metadata.cameraMake(),
                    metadata.cameraModel(), metadata.lensModel(),
                    metadata.exifExtraJson(), now,
                    captureDateProtected, orientationProtected));
            queuePreview(photoId, metadata, buffer);
            buffer.add(mediaRepository.resetAiFields(photoId));
            return new FileImportResult(ImportOutcome.UPDATED, false, fileSize, null);
        }

        // Brand-new media: insert returns the generated ID immediately.
        long photoId = mediaRepository.insertPhoto(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(),
                metadata.captureDate(), metadata.captureDateSource(),
                metadata.orientationDegrees(), metadata.cameraMake(),
                metadata.cameraModel(), metadata.lensModel(),
                metadata.exifExtraJson(), now);
        queuePreview(photoId, metadata, buffer);
        return new FileImportResult(ImportOutcome.IMPORTED, false, fileSize, null);
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
            return new FileImportResult(ImportOutcome.SKIPPED_UNCHANGED, true, fileSize, null);
        }

        ExtractedVideoMetadata metadata;
        try {
            metadata = videoMetadataExtractor.extract(file);
        } catch (UnsupportedVideoCodecException e) {
            // Reported in the ENDED summary's "unsupported codec" count, and as a per-file SKIPPED
            // issue with this exact reason — see recordOutcome.
            log.warn("Skipping unsupported video codec: {}", e.getMessage());
            return new FileImportResult(ImportOutcome.REJECTED_UNSUPPORTED_CODEC, true, fileSize, e.getMessage());
        }

        if (existing.isPresent()) {
            long videoId = existing.get()
                                   .id();
            boolean captureDateProtected = metadataEditRepository.hasManualEdit(videoId, Field.CAPTURE_DATE);
            boolean rotationProtected    = metadataEditRepository.hasManualEdit(videoId, Field.ROTATION);
            buffer.addAll(mediaRepository.updateVideoMetadataQueryProtected(videoId, fileSize,
                    metadata.width(), metadata.height(),
                    metadata.captureDate(), metadata.captureDateSource(),
                    metadata.durationMs(), metadata.codec(), metadata.frameRate(), metadata.rotationDegrees(),
                    metadata.gpsLat(), metadata.gpsLon(), metadata.gpsAltitude(), now,
                    captureDateProtected, rotationProtected));
            buffer.add(mediaRepository.resetAiFields(videoId));
            return new FileImportResult(ImportOutcome.UPDATED, true, fileSize, null);
        }

        mediaRepository.insertVideo(folderId, absolutePath, filename, extension, fileSize,
                metadata.width(), metadata.height(),
                metadata.captureDate(), metadata.captureDateSource(),
                metadata.durationMs(), metadata.codec(), metadata.frameRate(), metadata.rotationDegrees(),
                metadata.gpsLat(), metadata.gpsLon(), metadata.gpsAltitude(), now);
        return new FileImportResult(ImportOutcome.IMPORTED, true, fileSize, null);
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
        return new ScanResult(found, visitor.getUnsupportedExtensionFiles());
    }

    @Override
    public String getProcessName() {
        return IMPORT_SCAN;
    }
}
