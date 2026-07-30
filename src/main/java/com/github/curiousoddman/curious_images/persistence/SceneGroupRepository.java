package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.model.PhotoRefinementState;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CUSTOM_ALBUM_PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.SCENE_GROUP;

/**
 * Hand-written jOOQ repository for {@code scene_group}. Owns both the group rows themselves
 * and the act of assigning a {@code custom_album_photo} row to one, since "create/find a group
 * to assign into" is one cohesive operation driven by {@code SceneGroupingService} (Phase 2).
 */
@Repository
@RequiredArgsConstructor
public class SceneGroupRepository {

    private final DSLContext dsl;

    /**
     * Creates a new scene group — used both for a fresh match between two ungrouped photos and
     * for a singleton (spec §4.4) when a photo has no candidates at all.
     */
    public long insert(long customAlbumId) {
        return dsl.insertInto(SCENE_GROUP)
                  .set(SCENE_GROUP.CUSTOM_ALBUM_ID, customAlbumId)
                  .returning(SCENE_GROUP.ID)
                  .fetchOne()
                  .getId();
    }

    /**
     * Assigns a photo to a group. Callers must never call this for a photo that already has a
     * {@code scene_group_id} — assignment stability (spec §4.3) means an existing assignment is
     * never changed, so {@code SceneGroupingService} only ever calls this for photos it has
     * confirmed are still unassigned.
     */
    public void assignPhotoToGroup(long customAlbumId, long photoId, long sceneGroupId) {
        dsl.update(CUSTOM_ALBUM_PHOTO)
           .set(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID, sceneGroupId)
           .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(customAlbumId))
           .and(CUSTOM_ALBUM_PHOTO.PHOTO_ID.eq(photoId))
           .execute();
    }

    /**
     * Scene group ids in this album that still have at least one {@code Unassigned} photo,
     * ordered by that group's earliest-added photo — the order triage mode (Phase 7) presents
     * groups in.
     */
    public List<Long> findGroupIdsWithUnassignedPhotos(long customAlbumId) {
        return dsl.select(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID)
                  .from(CUSTOM_ALBUM_PHOTO)
                  .where(CUSTOM_ALBUM_PHOTO.CUSTOM_ALBUM_ID.eq(customAlbumId))
                  .and(CUSTOM_ALBUM_PHOTO.STATE.eq(PhotoRefinementState.UNASSIGNED.dbValue()))
                  .and(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID.isNotNull())
                  .groupBy(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID)
                  .orderBy(org.jooq.impl.DSL.min(CUSTOM_ALBUM_PHOTO.ADDED_AT))
                  .fetch(CUSTOM_ALBUM_PHOTO.SCENE_GROUP_ID);
    }
}
