package com.github.curiousoddman.curious_images.domain.scenegroup;

import com.github.curiousoddman.curious_images.event.model.CustomAlbumUpdatedEvent;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.util.async.jobs.BackgroundJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Background job wrapper around {@link SceneGroupingService}: a global sweep across every custom
 * album that currently has at least one not-yet-clustered photo (album-refinement-feature-spec.md
 * §4). Deliberately global rather than parameterized to one album — {@code JobManager} only ever
 * keeps one queued/running instance per job class (see its {@code submit} dedup check), so a
 * per-album job would silently drop a second album's grouping request if it arrived while the
 * first was still queued. A global sweep sidesteps that: whichever albums have pending photos
 * when the job actually runs all get processed in the same pass.
 * <p>
 * {@link #isSupersedable()} is {@code true} for the same reason {@code ThumbnailGenerationJob} is:
 * if new photos are added to some album while a sweep is already queued or running, that sweep's
 * snapshot of "albums with pending photos" may already be stale by the time it runs (or may have
 * already passed over an album that then got more photos mid-run) — a fresh submission
 * superseding the old one guarantees the freshest set of pending albums gets processed, rather
 * than possibly requiring a second full sweep later to pick up what the first missed.
 */
@Slf4j
@RequiredArgsConstructor
public class SceneGroupingJob extends BackgroundJob {
    public static final String SCENE_GROUPING = "Scene Grouping";

    private final CustomAlbumPhotoRepository customAlbumPhotoRepository;
    private final SceneGroupingService        sceneGroupingService;

    @Override
    public String getProcessName() {
        return SCENE_GROUPING;
    }

    @Override
    public boolean isSupersedable() {
        return true;
    }

    @Override
    public void runImpl() {
        List<Long> albumIds = customAlbumPhotoRepository.findAlbumIdsWithoutSceneGroup();
        if (albumIds.isEmpty()) {
            publishStarted("Nothing to group");
            publishEnded("No custom albums have new photos to group");
            return;
        }

        publishStarted("Grouping " + albumIds.size() + " album(s) with new photos...");

        int totalAssigned = 0;
        for (int i = 0; i < albumIds.size(); i++) {
            if (isInterruptRequested()) {
                publishInterrupted();
                return;
            }

            long albumId = albumIds.get(i);
            publishProgressThrottled("Grouping albums", i, albumIds.size(),
                    "album #" + albumId, i == albumIds.size() - 1);

            int assigned = sceneGroupingService.processAlbum(albumId);
            totalAssigned += assigned;

            eventPublisher.publishEvent(new CustomAlbumUpdatedEvent(this, albumId));
        }

        publishEnded("Grouped " + totalAssigned + " photo(s) across " + albumIds.size() + " album(s)");
    }
}
