package com.github.curiousoddman.curious_images.domain.dedupe;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaHashRecord;
import com.github.curiousoddman.curious_images.domain.common.MediaExtensions;
import com.github.curiousoddman.curious_images.domain.dedupe.hasher.FileHasher;
import com.github.curiousoddman.curious_images.domain.dedupe.hasher.PixelHasher;
import com.github.curiousoddman.curious_images.persistence.DuplicateGroupRepository;
import com.github.curiousoddman.curious_images.persistence.DuplicateJobRepository;
import com.github.curiousoddman.curious_images.persistence.MediaHashRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.util.QueryBuffer;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Duplicate detection: a separate, user-triggered background job (never run as part of import).
 * <p>
 * Two photos are duplicates when their decoded pixel content is identical AND they share the same
 * file extension; two videos are duplicates when their raw file bytes are identical (exact-match
 * only — no perceptual/frame hashing for v1) AND they share the same file extension. Comparison
 * never crosses file types OR hash types (photos vs. videos always hash differently — see
 * {@link HashType} — and are never compared against each other even if hex strings collided), per
 * the product spec and implementation plan §6.
 * <p>
 * Resumability: each media's hash is cached in MEDIA_HASH alongside the file size it was computed
 * from. A rerun only (re)hashes media whose current file size doesn't match what's cached —
 * everything else is reused for free. This means an interrupted run isn't wasted work: whatever
 * was hashed before the interrupt stays cached for next time.
 * <p>
 * Hashing is parallelized across a configurable fixed pool of plain platform threads
 * ({@code app.duplicate-detection.thread-count}, default 4). Photo hashing is CPU-bound (decode +
 * digest); video hashing is I/O-bound streaming digest of the raw bytes — both share the same
 * pool, since neither dominates enough to warrant separate pools for v1.
 */
@Slf4j
@RequiredArgsConstructor
public class DuplicateDetectionJob extends BackgroundJob {
    public static final String        DUPLICATE_DETECTION = "Duplicate Detection";
    public static final AtomicInteger THREAD_COUNTER      = new AtomicInteger();

    private final DSLContext               dsl;
    private final MediaRepository          mediaRepository;
    private final MediaHashRepository      photoHashRepository;
    private final DuplicateJobRepository   duplicateJobRepository;
    private final DuplicateGroupRepository duplicateGroupRepository;
    private final PixelHasher              pixelHasher;
    private final FileHasher               fileHasher;
    private final TimeProvider             timeProvider;
    private final int                      threadCount;

    @Override
    public void runImpl() {
        log.info("Starting duplicate detection");
        publishStarted("Loading media library...");
        long jobId = -1;
        try {
            List<MediaForHashing>      photos         = mediaRepository.findAllForHashing();
            Map<Long, MediaHashRecord> existingHashes = photoHashRepository.findAllAsMap();

            jobId = duplicateJobRepository.insertRunning(timeProvider.now(), photos.size());

            Map<Long, HashEntry>  hashByPhoto  = new HashMap<>(photos.size());
            List<MediaForHashing> needsHashing = new ArrayList<>();
            for (MediaForHashing photo : photos) {
                MediaHashRecord cached = existingHashes.get(photo.id());
                if (cached != null && cached.getHashedFileSize() == photo.fileSize()) {
                    HashType hashType = cached.getHashType() != null
                            ? HashType.valueOf(cached.getHashType())
                            : hashTypeFor(photo.extension());
                    hashByPhoto.put(photo.id(), new HashEntry(photo.extension(), hashType, cached.getContentHash()));
                } else {
                    needsHashing.add(photo);
                }
            }
            log.info("{} of {} photos need (re)hashing", needsHashing.size(), photos.size());

            AtomicInteger processed = new AtomicInteger(hashByPhoto.size());
            publishInProgress("Hashing photos...", processed.get(), photos.size());

            boolean interrupted = !needsHashing.isEmpty()
                    && hashAndPersist(needsHashing, photos.size(), processed, hashByPhoto);

            if (interrupted || isInterruptRequested()) {
                duplicateJobRepository.markInterrupted(jobId, timeProvider.now());
                log.info("Duplicate detection interrupted after hashing {} of {} photos",
                        processed.get(), photos.size());
                publishInterrupted();
                return;
            }

            Map<GroupKey, List<Long>> groups     = groupDuplicates(hashByPhoto);
            int                       groupCount = persistGroups(jobId, groups);

            duplicateJobRepository.markCompleted(jobId, timeProvider.now(), groupCount);
            log.info("Duplicate detection completed: {} duplicate group(s) found among {} photos",
                    groupCount, photos.size());
            publishEnded("Found %d duplicate group%s".formatted(groupCount, groupCount == 1 ? "" : "s"));
        } catch (Exception e) {
            log.error("Duplicate detection failed", e);
            if (jobId >= 0) {
                duplicateJobRepository.markFailed(jobId, timeProvider.now(), String.valueOf(e.getMessage()));
            }
            publishFailed(e);
            throw e;
        }
    }

    /**
     * Hashes {@code needsHashing} across a fixed pool of {@link #threadCount} threads, persisting
     * (batched) and reporting progress as each result comes back. Polls the interrupt flag once
     * per completed result, so cancellation lands promptly without needing to interrupt
     * in-flight decode work.
     *
     * @return {@code true} if the run was interrupted partway through
     */
    private boolean hashAndPersist(List<MediaForHashing> needsHashing, int totalPhotos,
                                   AtomicInteger processed, Map<Long, HashEntry> hashByPhoto) {
        LocalDateTime now = timeProvider.now();

        try (ExecutorService executor = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "duplicate-hash-" + THREAD_COUNTER.incrementAndGet());
            t.setDaemon(true);
            return t;
        })) {
            CompletionService<HashOutcome> completionService = new ExecutorCompletionService<>(executor);

            for (MediaForHashing photo : needsHashing) {
                completionService.submit(() -> hashOne(photo));
            }

            try (QueryBuffer buffer = new QueryBuffer(dsl)) {
                for (int i = 0; i < needsHashing.size(); i++) {
                    if (isInterruptRequested()) {
                        executor.shutdownNow();
                        return true;
                    }

                    HashOutcome result;
                    try {
                        result = completionService.take()
                                                  .get();
                    } catch (InterruptedException ie) {
                        Thread.currentThread()
                              .interrupt();
                        executor.shutdownNow();
                        return true;
                    } catch (ExecutionException ee) {
                        log.warn("Failed to hash a media", ee.getCause());
                        processed.incrementAndGet();
                        continue;
                    }

                    if (result.hash() != null) {
                        hashByPhoto.put(result.mediaId(), new HashEntry(result.extension(), result.hashType(), result.hash()));
                        buffer.add(photoHashRepository.upsertQuery(
                                result.mediaId(), result.hashType()
                                                        .name(),
                                result.hash(), result.fileSize(), now));
                    }
                    // else: undecodable/unreadable file (corrupt photo, CR2 with no usable
                    // preview, or an I/O error reading a video) — skip silently, same "don't fail
                    // the whole job over one bad file" policy as ImportService.

                    int done = processed.incrementAndGet();
                    publishProgressThrottled("Hashing photos", done, totalPhotos, result.absolutePath(), done == totalPhotos);
                }
            }
            return false;
        }
    }

    /**
     * Dispatches to the right hasher for this media's type (see implementation plan §6): decoded
     * pixel bytes for photos, raw file bytes for videos. The two are never comparable, so the
     * result always carries which one produced it.
     */
    private HashOutcome hashOne(MediaForHashing photo) {
        Path file = Path.of(photo.absolutePath());
        if (MediaExtensions.isVideo(photo.extension())) {
            FileHasher.MediaHashResult result = fileHasher.hash(photo.id(), file, photo.extension(), photo.fileSize());
            return new HashOutcome(photo.id(), result.extension(), result.fileSize(), result.absolutePath(),
                    HashType.FILE, result.fileHash());
        }
        PixelHasher.MediaHashResult result = pixelHasher.hash(photo.id(), file, photo.extension(), photo.fileSize());
        return new HashOutcome(photo.id(), result.extension(), result.fileSize(), result.absolutePath(),
                HashType.PIXEL, result.pixelHash());
    }

    private static HashType hashTypeFor(String extension) {
        return MediaExtensions.isVideo(extension) ? HashType.FILE : HashType.PIXEL;
    }

    private Map<GroupKey, List<Long>> groupDuplicates(Map<Long, HashEntry> hashByPhoto) {
        Map<GroupKey, List<Long>> groups = new HashMap<>();
        for (Map.Entry<Long, HashEntry> entry : hashByPhoto.entrySet()) {
            GroupKey key = getGroupKey(entry);
            groups.computeIfAbsent(key, _ -> new ArrayList<>())
                  .add(entry.getKey());
        }
        groups.values()
              .removeIf(photoIds -> photoIds.size() < 2);
        return groups;
    }

    private static GroupKey getGroupKey(Map.Entry<Long, HashEntry> entry) {
        HashEntry hashEntry = entry.getValue();
        // Grouping key includes hashType as an explicit safety rule (implementation plan
        // §6): PIXEL and FILE hashes must never be compared, even though in practice photo
        // and video extensions never overlap, so this never actually changes which groups
        // form today — it just guarantees a future extension overlap can't silently cross-match.
        return new GroupKey(hashEntry.hashType(), hashEntry.extension(), hashEntry.hash());
    }

    /**
     * Inserts this run's groups, then deletes every other run's groups — the Duplicates View
     * always reflects only the latest completed run.
     */
    private int persistGroups(long jobId, Map<GroupKey, List<Long>> groups) {
        LocalDateTime now = timeProvider.now();
        return dsl.transactionResult(configuration -> {
            DSLContext ctx   = DSL.using(configuration);
            int        count = 0;
            for (Map.Entry<GroupKey, List<Long>> entry : groups.entrySet()) {
                long groupId = duplicateGroupRepository.insertGroup(
                        ctx, jobId, entry.getKey()
                                         .extension(), entry.getKey()
                                                            .hash(), now);
                duplicateGroupRepository.insertMembers(ctx, groupId, entry.getValue());
                count++;
            }
            duplicateGroupRepository.deleteGroupsNotInJob(ctx, jobId);
            return count;
        });
    }

    @Override
    public String getProcessName() {
        return DUPLICATE_DETECTION;
    }

    private record GroupKey(HashType hashType, String extension, String hash) {
    }

    private record HashEntry(String extension, HashType hashType, String hash) {
    }

    private record HashOutcome(long mediaId, String extension, long fileSize, String absolutePath,
                               HashType hashType, String hash) {
    }
}
