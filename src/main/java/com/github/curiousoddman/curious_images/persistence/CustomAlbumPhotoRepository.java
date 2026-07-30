package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumPhotoRecord;
import com.github.curiousoddman.curious_images.model.PhotoRefinementState;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.jooq.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CUSTOM_ALBUM_PHOTO;

/**
 * Hand-written jOOQ repository for {@code custom_album_photo} — the Photo↔CustomAlbum join
 * carrying per-album refinement {@code state} and {@code scene_group_id}.
 * <p>
 * Writes that are natural to batch ({@link #insertQuery}) return an unexecuted {@link Query},
 * same convention as {@link AlbumPhotoRepository#insertQuery}; queue into a
 * {@link com.github.curiousoddman.curious_images.util.QueryBuffer} and flush as a batch. Single
 * row/small-set updates (state changes from the UI, bulk scene-group actions) execute
 * immediately since they're driven by one user action at a time, not an import-scale loop.
 */
@Repository
@RequiredArgsConstructor
public class CustomAlbumPhotoRepository {

    private final DSLContext dsl;

    /**
     * Returns an unexecuted INSERT for a single membership row. {@code state} is left to the
     * column default (Unassigned/0, per the feature spec's "default on add") and
     * {@code scene_group_id} is left NULL, marking the photo as not-yet-clustered so the
     * scene-grouping job (Phase 2) picks it up.
     */
    public Query insertQuery(long albumId, long photoId, LocalDateTime now) {
        return dsl.insertInto(CUSTOM_ALBUM_PHOTO)
                  .set(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID, albumId)
                  .set(CUSTOM_ALBUM_PHOTO.PHOTO_ID, photoId)
                  .set(CUSTOM_ALBUM_PHOTO.ADDED_AT, now);
    }

    /**
     * Distinct custom album ids that currently have at least one not-yet-clustered photo.
     * Drives {@code SceneGroupingJob}'s global sweep — it processes one album's worth of
     * ungrouped photos at a time via {@code SceneGroupingService#processAlbum}, in this order.
     */
    public List<Long> findAlbumIdsWithoutSceneGroup() {
        return dsl.selectDistinct(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID)
                  .from(CUSTOM_ALBUM_PHOTO)
                  .where(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID.isNull())
                  .orderBy(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID)
                  .fetch(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID);
    }

    public List<CustomAlbumPhotoRecord> findByAlbumId(long albumId) {
        return dsl.selectFrom(CUSTOM_ALBUM_PHOTO)
                  .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
                  .orderBy(CUSTOM_ALBUM_PHOTO.ADDED_AT)
                  .fetch();
    }

    /**
     * Photos in this album not yet processed by the scene-grouping job. Consumed by
     * {@code SceneGroupingService} (Phase 2) as the "new photos" side of matching.
     */
    public List<CustomAlbumPhotoRecord> findWithoutSceneGroup(long albumId) {
        return dsl.selectFrom(CUSTOM_ALBUM_PHOTO)
                  .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
                  .and(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID.isNull())
                  .orderBy(CUSTOM_ALBUM_PHOTO.ADDED_AT)
                  .fetch();
    }

    /**
     * {@code Unassigned} photos in one scene group, in the order triage mode should present
     * them. Scope is always (album, group) together — a scene group belongs to exactly one
     * album, but passing both keeps this query aligned with the
     * {@code idx_custom_album_photo_state} composite index.
     */
    public List<CustomAlbumPhotoRecord> findUnassignedByAlbumAndGroup(long albumId, long sceneGroupId) {
        return dsl.selectFrom(CUSTOM_ALBUM_PHOTO)
                  .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
                  .and(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID.eq(sceneGroupId))
                  .and(CUSTOM_ALBUM_PHOTO.STATE.eq(PhotoRefinementState.UNASSIGNED.dbValue()))
                  .orderBy(CUSTOM_ALBUM_PHOTO.ADDED_AT)
                  .fetch();
    }

    public void updateState(long albumId, long photoId, PhotoRefinementState newState) {
        dsl.update(CUSTOM_ALBUM_PHOTO)
           .set(CUSTOM_ALBUM_PHOTO.STATE, newState.dbValue())
           .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
           .and(CUSTOM_ALBUM_PHOTO.PHOTO_ID.eq(photoId))
           .execute();
    }

    /**
     * Sets every given photo in the album to {@code newState}. Used both by triage's "mark
     * remaining Unassigned as RatherNo on advance" and by the unrefined view's explicit
     * "set entire group to state X" bulk action.
     */
    public void updateStateBulk(long albumId, Set<Long> photoIds, PhotoRefinementState newState) {
        if (photoIds.isEmpty()) {
            return;
        }
        dsl.update(CUSTOM_ALBUM_PHOTO)
           .set(CUSTOM_ALBUM_PHOTO.STATE, newState.dbValue())
           .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
           .and(CUSTOM_ALBUM_PHOTO.PHOTO_ID.in(photoIds))
           .execute();
    }

    /**
     * Implements the spec §7 bulk action "mark all in group as No except selected photo(s)":
     * sets every photo in {@code sceneGroupId} to {@code newState} except those in
     * {@code keepPhotoIds}.
     */
    public void updateStateForGroupExcept(long albumId, long sceneGroupId, Set<Long> keepPhotoIds,
                                          PhotoRefinementState newState) {
        var condition = CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId)
                                                          .and(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID.eq(sceneGroupId));
        dsl.update(CUSTOM_ALBUM_PHOTO)
           .set(CUSTOM_ALBUM_PHOTO.STATE, newState.dbValue())
           .where(keepPhotoIds.isEmpty()
                          ? condition
                          : condition.and(CUSTOM_ALBUM_PHOTO.PHOTO_ID.notIn(keepPhotoIds)))
           .execute();
    }

    /**
     * Drives the refined-view lock (spec §7): refined view is locked whenever this is 0.
     */
    public int countYes(long albumId) {
        return dsl.selectCount()
                  .from(CUSTOM_ALBUM_PHOTO)
                  .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
                  .and(CUSTOM_ALBUM_PHOTO.STATE.eq(PhotoRefinementState.YES.dbValue()))
                  .fetchOne(0, int.class);
    }

    /**
     * Drives the cover-photo fallback (spec §6) before the user has explicitly picked one:
     * the first photo marked {@code Yes}, by the order it was added to the album.
     */
    public Optional<Long> findFirstYesByAddedOrder(long albumId) {
        return Optional.ofNullable(
                dsl.select(CUSTOM_ALBUM_PHOTO.PHOTO_ID)
                   .from(CUSTOM_ALBUM_PHOTO)
                   .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(albumId))
                   .and(CUSTOM_ALBUM_PHOTO.STATE.eq(PhotoRefinementState.YES.dbValue()))
                   .orderBy(CUSTOM_ALBUM_PHOTO.ADDED_AT)
                   .limit(1)
                   .fetchOne(CUSTOM_ALBUM_PHOTO.PHOTO_ID));
    }
}
