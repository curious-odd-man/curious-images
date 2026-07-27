package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaMetadataEditRecord;
import lombok.RequiredArgsConstructor;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_METADATA_EDIT;

/**
 * Audit trail of manual metadata corrections (rotation, capture date) applied via the UI —
 * one row per individual field change (see {@code MetadataEditService}/{@code PhotoRotationService}).
 * <p>
 * {@code ImportJob} consults {@link #findEditedMediaIds} / {@link #hasManualEdit} before rebuilding
 * a media's metadata on rescan, so a manual correction isn't silently clobbered by re-extracted
 * EXIF/container metadata.
 */
@Repository
@RequiredArgsConstructor
public class MediaMetadataEditRepository {

    private final DSLContext dsl;

    /**
     * Matches {@code MEDIA_METADATA_EDIT.FIELD_NAME} values.
     */
    public enum Field {
        ROTATION,
        CAPTURE_DATE
    }

    /**
     * Matches {@code MEDIA_METADATA_EDIT.EDIT_TYPE} values. RELATIVE only applies to ROTATION.
     */
    public enum EditType {
        ABSOLUTE,
        RELATIVE
    }

    public void recordEdit(DSLContext ctx, long mediaId, Field field, String oldValue, String newValue,
                           EditType editType, LocalDateTime now) {
        ctx.insertInto(MEDIA_METADATA_EDIT)
           .set(MEDIA_METADATA_EDIT.MEDIA_ID, mediaId)
           .set(MEDIA_METADATA_EDIT.FIELD_NAME, field.name())
           .set(MEDIA_METADATA_EDIT.OLD_VALUE, oldValue)
           .set(MEDIA_METADATA_EDIT.NEW_VALUE, newValue)
           .set(MEDIA_METADATA_EDIT.EDIT_TYPE, editType.name())
           .set(MEDIA_METADATA_EDIT.EDITED_AT, now)
           .execute();
    }

    /**
     * Which of the given media ids already have at least one manual edit recorded for
     * {@code field} — bulk form, used by {@code ImportJob} so a rescan of many files needs one
     * query per field, not one per file.
     */
    public Set<Long> findEditedMediaIds(Collection<Long> mediaIds, Field field) {
        if (mediaIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(
                dsl.selectDistinct(MEDIA_METADATA_EDIT.MEDIA_ID)
                   .from(MEDIA_METADATA_EDIT)
                   .where(MEDIA_METADATA_EDIT.MEDIA_ID.in(mediaIds)
                                                      .and(MEDIA_METADATA_EDIT.FIELD_NAME.eq(field.name())))
                   .fetch(MEDIA_METADATA_EDIT.MEDIA_ID));
    }

    public boolean hasManualEdit(long mediaId, Field field) {
        return dsl.fetchExists(
                dsl.selectOne()
                   .from(MEDIA_METADATA_EDIT)
                   .where(MEDIA_METADATA_EDIT.MEDIA_ID.eq(mediaId)
                                                      .and(MEDIA_METADATA_EDIT.FIELD_NAME.eq(field.name()))));
    }

    /**
     * Full audit trail for one media, most recent first — for a future "edit history" panel.
     */
    public List<MediaMetadataEditRecord> findHistoryByMediaId(long mediaId) {
        return dsl.selectFrom(MEDIA_METADATA_EDIT)
                  .where(MEDIA_METADATA_EDIT.MEDIA_ID.eq(mediaId))
                  .orderBy(MEDIA_METADATA_EDIT.EDITED_AT.desc())
                  .fetch();
    }
}
