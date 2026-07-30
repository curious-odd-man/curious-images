package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static com.github.curiousoddman.curious_images.dbobj.Tables.CUSTOM_ALBUM;

/**
 * Hand-written jOOQ repository for {@code custom_album} — user-created albums (see
 * album-refinement-feature-spec.md). Unlike {@link AlbumRepository}, rows here are never
 * rebuilt/deleted by the AI pipeline; they only change in response to explicit user actions
 * (create, rename, add photos, set cover).
 */
@Repository
@RequiredArgsConstructor
public class CustomAlbumRepository {

    private final DSLContext dsl;

    public long insert(String name, LocalDateTime now) {
        return dsl.insertInto(CUSTOM_ALBUM)
                  .set(CUSTOM_ALBUM.NAME, name)
                  .set(CUSTOM_ALBUM.CREATED_AT, now)
                  .returning(CUSTOM_ALBUM.ID)
                  .fetchOne()
                  .getId();
    }

    public void rename(long albumId, String newName, LocalDateTime now) {
        dsl.update(CUSTOM_ALBUM)
           .set(CUSTOM_ALBUM.NAME, newName)
           .set(CUSTOM_ALBUM.UPDATED_AT, now)
           .where(CUSTOM_ALBUM.ID.eq(albumId))
           .execute();
    }

    /**
     * Cascades to {@code scene_group} and {@code custom_album_photo} via {@code ON DELETE CASCADE}.
     */
    public void delete(long albumId) {
        dsl.deleteFrom(CUSTOM_ALBUM)
           .where(CUSTOM_ALBUM.ID.eq(albumId))
           .execute();
    }

    public void updateCoverPhoto(long albumId, long photoId, LocalDateTime now) {
        dsl.update(CUSTOM_ALBUM)
           .set(CUSTOM_ALBUM.COVER_PHOTO_ID, photoId)
           .set(CUSTOM_ALBUM.UPDATED_AT, now)
           .where(CUSTOM_ALBUM.ID.eq(albumId))
           .execute();
    }

    public List<CustomAlbumRecord> findAll() {
        return dsl.selectFrom(CUSTOM_ALBUM)
                  .orderBy(CUSTOM_ALBUM.NAME)
                  .fetch();
    }

    public Optional<CustomAlbumRecord> findById(long albumId) {
        return Optional.ofNullable(
                dsl.selectFrom(CUSTOM_ALBUM)
                   .where(CUSTOM_ALBUM.ID.eq(albumId))
                   .fetchOne());
    }
}
