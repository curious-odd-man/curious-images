package com.github.curiousoddman.curious_images.domain.scenegroup;

import com.github.curiousoddman.curious_images.config.AiConfig;
import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.ThumbnailRecord;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.SceneGroupRepository;
import com.github.curiousoddman.curious_images.persistence.ThumbnailRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Orchestrates incremental scene-group clustering for one custom album
 * (album-refinement-feature-spec.md §4.2/§4.3). Called by {@link SceneGroupingJob}.
 * <p>
 * Only photos with {@code scene_group_id IS NULL} are ever matched or (re)assigned — an
 * already-grouped photo is only ever read as a comparison candidate for a new photo, never moved.
 * This is what gives assignment stability (§4.3) without needing an explicit "don't touch me"
 * flag: the DB state itself (non-null scene_group_id) is the stability marker.
 * <p>
 * Perceptual hashes are not persisted (Phase 0's schema has no column for it) — every pass
 * re-decodes/re-hashes whichever photos actually need comparing. This is intentionally lazy: a
 * hash is only computed once a photo has already passed the cheap timestamp/geo pre-filter
 * against a specific other photo, and is cached for the rest of that single pass so the same
 * photo is never hashed twice in one run. If this ever shows up as a hot path on large albums, an
 * additive follow-up would be a small {@code scene_hash} column to persist it across runs.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SceneGroupingService {

    private final CustomAlbumPhotoRepository customAlbumPhotoRepository;
    private final SceneGroupRepository        sceneGroupRepository;
    private final MediaRepository             mediaRepository;
    private final ThumbnailRepository         thumbnailRepository;
    private final PerceptualHasher            perceptualHasher;
    private final SceneCandidateMatcher       sceneCandidateMatcher;
    private final AiConfig                    aiConfig;

    /**
     * @return how many photos were newly assigned a scene group, for job progress reporting.
     */
    public int processAlbum(long customAlbumId) {
        List<CustomAlbumPhotoRecord> all = customAlbumPhotoRepository.findByAlbumId(customAlbumId);

        List<CustomAlbumPhotoRecord> ungrouped = all.stream()
                                                     .filter(r -> r.getSceneGroupId() == null)
                                                     .sorted(Comparator.comparing(CustomAlbumPhotoRecord::getAddedAt))
                                                     .toList();
        if (ungrouped.isEmpty()) {
            return 0;
        }

        List<Long> photoIds = all.stream()
                                  .map(CustomAlbumPhotoRecord::getPhotoId)
                                  .toList();
        Map<Long, MediaPhotoRecord> mediaById = mediaRepository.findByIdIn(photoIds)
                                                                .stream()
                                                                .collect(java.util.stream.Collectors.toMap(
                                                                        MediaPhotoRecord::getId, m -> m));
        Map<Long, ThumbnailRecord> thumbnailsById = thumbnailRepository.findByPhotoIds(photoIds);
        Map<Long, Long>            hashCache      = new HashMap<>();

        // Seed with real prior assignments; grows as this pass creates new groups/singletons, so
        // a later ungrouped photo in the same pass can still match one from earlier in the pass.
        List<AssignedPhoto> assigned = new ArrayList<>();
        for (CustomAlbumPhotoRecord r : all) {
            if (r.getSceneGroupId() != null) {
                descriptorFor(r, mediaById).ifPresent(d -> assigned.add(new AssignedPhoto(d, r.getSceneGroupId())));
            }
        }

        int timestampWindowSeconds = aiConfig.getSceneGroupTimestampWindowSeconds();
        int geoRadiusMeters        = aiConfig.getSceneGroupGeoRadiusMeters();
        int hashDistanceThreshold  = aiConfig.getSceneGroupHashDistanceThreshold();

        int assignedCount = 0;
        for (CustomAlbumPhotoRecord record : ungrouped) {
            Optional<ScenePhotoDescriptor> descriptorOpt = descriptorFor(record, mediaById);
            if (descriptorOpt.isEmpty()) {
                // Media row vanished/never existed for this join row — shouldn't happen given the
                // FK, but don't leave the photo stuck NULL forever: singleton it and move on.
                log.warn("No media metadata for custom_album_photo photo_id={}, singleton-grouping it", record.getPhotoId());
                long groupId = sceneGroupRepository.insert(customAlbumId);
                sceneGroupRepository.assignPhotoToGroup(customAlbumId, record.getPhotoId(), groupId);
                assignedCount++;
                continue;
            }
            ScenePhotoDescriptor descriptor = descriptorOpt.get();
            Long                 newHash    = hashFor(descriptor, thumbnailsById, hashCache).orElse(null);

            Long matchedGroupId = null;
            if (newHash != null) {
                for (AssignedPhoto candidate : assigned) {
                    if (!sceneCandidateMatcher.isCandidate(descriptor, candidate.descriptor(),
                            timestampWindowSeconds, geoRadiusMeters)) {
                        continue;
                    }
                    Long candidateHash = hashFor(candidate.descriptor(), thumbnailsById, hashCache).orElse(null);
                    if (candidateHash == null) {
                        continue;
                    }
                    if (HammingDistance.between(newHash, candidateHash) <= hashDistanceThreshold) {
                        matchedGroupId = candidate.groupId();
                        break;
                    }
                }
            }

            long groupId = matchedGroupId != null ? matchedGroupId : sceneGroupRepository.insert(customAlbumId);
            sceneGroupRepository.assignPhotoToGroup(customAlbumId, descriptor.photoId(), groupId);
            assigned.add(new AssignedPhoto(descriptor, groupId));
            assignedCount++;
        }

        return assignedCount;
    }

    private Optional<ScenePhotoDescriptor> descriptorFor(CustomAlbumPhotoRecord record,
                                                         Map<Long, MediaPhotoRecord> mediaById) {
        MediaPhotoRecord media = mediaById.get(record.getPhotoId());
        if (media == null) {
            return Optional.empty();
        }
        return Optional.of(new ScenePhotoDescriptor(
                media.getId(), media.getCaptureDate(), media.getGpsLat(), media.getGpsLon(),
                media.getAbsolutePath(), media.getExtension()));
    }

    /**
     * Resolves and hashes the cheapest available decodable source for a photo: the existing
     * thumbnail file if one's already been generated (small, fast to decode), falling back to the
     * original source file otherwise (e.g. a photo just added to an album whose on-demand
     * thumbnail hasn't been generated yet — see {@code ThumbnailGenerationJob}). Result is cached
     * per photo for the lifetime of one {@link #processAlbum} call, including negative results
     * (undecodable photos stay {@code null}-cached so they're not retried against every other
     * candidate in the same pass).
     */
    private Optional<Long> hashFor(ScenePhotoDescriptor descriptor, Map<Long, ThumbnailRecord> thumbnailsById,
                                   Map<Long, Long> hashCache) {
        if (hashCache.containsKey(descriptor.photoId())) {
            return Optional.ofNullable(hashCache.get(descriptor.photoId()));
        }

        ThumbnailRecord thumbnail = thumbnailsById.get(descriptor.photoId());
        Path            source;
        String          extension;
        if (thumbnail != null && thumbnail.getCachePath() != null) {
            source = Path.of(thumbnail.getCachePath());
            // Thumbnails are always encoded as a standard raster format regardless of the source
            // extension; ImageUtils only special-cases "cr2" (it sniffs bytes otherwise), so any
            // non-CR2 placeholder extension decodes the thumbnail file correctly.
            extension = "jpg";
        } else {
            source = Path.of(descriptor.absolutePath());
            extension = descriptor.extension();
        }

        Optional<Long> hash = perceptualHasher.hash(source, extension);
        hashCache.put(descriptor.photoId(), hash.orElse(null));
        return hash;
    }

    private record AssignedPhoto(ScenePhotoDescriptor descriptor, long groupId) {
    }
}
