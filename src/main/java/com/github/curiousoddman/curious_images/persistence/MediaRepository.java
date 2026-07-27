package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaVideoRecord;
import com.github.curiousoddman.curious_images.domain.dedupe.MediaForHashing;
import com.github.curiousoddman.curious_images.domain.imports.metadata.CaptureDateSource;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.MediaType;
import com.github.curiousoddman.curious_images.model.TimelineData;
import com.github.curiousoddman.curious_images.util.Either;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.JSON;
import org.jooq.Query;
import org.jooq.Result;
import org.jooq.SelectWhereStep;
import org.jooq.TableField;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA;
import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.MEDIA_VIDEO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.PHOTO;
import static com.github.curiousoddman.curious_images.dbobj.Tables.VIDEO;

@Repository
@RequiredArgsConstructor
public class MediaRepository {
    private final DSLContext   dsl;
    private final TimeProvider timeProvider;

    public Optional<MediaPhotoRecord> findByAbsolutePath(String absolutePath) {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.ABSOLUTE_PATH.eq(absolutePath))
                .fetchOptional();
    }

    /**
     * Minimal, type-agnostic lookup used by {@code ImportJob}'s "cheap rescan: file unchanged"
     * check — that decision only ever needs the media id and file size, regardless of whether the
     * path turns out to be a photo or a video, so this queries {@code MEDIA} directly rather than
     * either subtype view.
     */
    public Optional<ExistingMediaSummary> findExistingMediaSummaryByAbsolutePath(String absolutePath) {
        return dsl.select(MEDIA.ID, MEDIA.FILE_SIZE, MEDIA.MEDIA_TYPE)
                  .from(MEDIA)
                  .where(MEDIA.ABSOLUTE_PATH.eq(absolutePath))
                  .fetchOptional(r -> new ExistingMediaSummary(
                          r.get(MEDIA.ID), r.get(MEDIA.FILE_SIZE), r.get(MEDIA.MEDIA_TYPE)));
    }

    public record ExistingMediaSummary(long id, long fileSize, MediaType mediaType) {
    }

    public List<MediaPhotoRecord> findByFolderId(long folderId) {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.FOLDER_ID.eq(folderId))
                .orderBy(MEDIA_PHOTO.FILENAME)
                .fetch()
                .stream()
                .toList();
    }

    /**
     * Mixed photo+video lookup for a folder — used by the grid/browse UI (see implementation plan
     * §2), which shows videos alongside photos in a plain grid. Sorted by filename, merging both
     * subtypes the same way {@link #findByCaptureDate} does for the timeline view.
     */
    public List<Media> findMediaByFolderId(long folderId) {
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(MEDIA_PHOTO.FOLDER_ID.eq(folderId))
                .fetch();
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(MEDIA_VIDEO.FOLDER_ID.eq(folderId))
                .fetch();

        return Stream.concat(
                             photo.stream()
                                  .map(Media::photo),
                             video.stream()
                                  .map(Media::video))
                     .sorted(Comparator.comparing(Media::getFilename, Comparator.nullsLast(Comparator.naturalOrder())))
                     .toList();
    }

    public long insertPhoto(long folderId, String absolutePath, String filename, String extension,
                            long fileSize, Integer width, Integer height,
                            LocalDateTime captureDate, CaptureDateSource captureDateSource,
                            int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                            String exifExtraJson, LocalDateTime now) {
        return dsl.transactionResult(conf -> {
            DSLContext tx = DSL.using(conf);
            Long id = tx.insertInto(MEDIA)
                        .set(MEDIA.FOLDER_ID, folderId)
                        .set(MEDIA.ABSOLUTE_PATH, absolutePath)
                        .set(MEDIA.FILENAME, filename)
                        .set(MEDIA.EXTENSION, extension)
                        .set(MEDIA.FILE_SIZE, fileSize)
                        .set(MEDIA.WIDTH, width)
                        .set(MEDIA.HEIGHT, height)
                        .set(MEDIA.CAPTURE_DATE, captureDate)
                        .set(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                        .set(MEDIA.CAMERA_MAKE, cameraMake)
                        .set(MEDIA.CAMERA_MODEL, cameraModel)
                        .set(MEDIA.IMPORTED_AT, now)
                        .set(MEDIA.LAST_SEEN_AT, now)
                        .set(MEDIA.AI_UPDATED_AT, now)
                        .set(MEDIA.MEDIA_TYPE, MediaType.PHOTO)
                        .returning(MEDIA.ID)
                        .fetchOne()
                        .getId();
            tx.insertInto(PHOTO)
              .set(PHOTO.ID, id)
              .set(PHOTO.ORIENTATION, orientationDegrees)
              .set(PHOTO.LENS_MODEL, lensModel)
              .set(PHOTO.EXIF_EXTRA, toJson(exifExtraJson))
              .execute();
            return id;
        });
    }

    /**
     * Video counterpart to {@link #insertPhoto}. GPS is written here (rather than left to a
     * separate update) since {@link com.github.curiousoddman.curious_images.domain.imports.metadata.VideoMetadataExtractor}
     * can parse it directly from container location tags, unlike EXIF-only photos which get GPS
     * through a separate path not yet exercised for the video branch.
     */
    public long insertVideo(long folderId, String absolutePath, String filename, String extension,
                            long fileSize, Integer width, Integer height,
                            LocalDateTime captureDate, CaptureDateSource captureDateSource,
                            Long durationMs, String codec, Double frameRate, int rotationDegrees,
                            Double gpsLat, Double gpsLon, Double gpsAltitude, LocalDateTime now) {
        return dsl.transactionResult(conf -> {
            DSLContext tx = DSL.using(conf);
            Long id = tx.insertInto(MEDIA)
                        .set(MEDIA.FOLDER_ID, folderId)
                        .set(MEDIA.ABSOLUTE_PATH, absolutePath)
                        .set(MEDIA.FILENAME, filename)
                        .set(MEDIA.EXTENSION, extension)
                        .set(MEDIA.FILE_SIZE, fileSize)
                        .set(MEDIA.WIDTH, width)
                        .set(MEDIA.HEIGHT, height)
                        .set(MEDIA.CAPTURE_DATE, captureDate)
                        .set(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                        .set(MEDIA.GPS_LAT, gpsLat)
                        .set(MEDIA.GPS_LON, gpsLon)
                        .set(MEDIA.GPS_ALTITUDE, gpsAltitude)
                        .set(MEDIA.IMPORTED_AT, now)
                        .set(MEDIA.LAST_SEEN_AT, now)
                        .set(MEDIA.AI_UPDATED_AT, now)
                        .set(MEDIA.MEDIA_TYPE, MediaType.VIDEO)
                        .returning(MEDIA.ID)
                        .fetchOne()
                        .getId();
            tx.insertInto(VIDEO)
              .set(VIDEO.ID, id)
              .set(VIDEO.DURATION_MS, durationMs)
              .set(VIDEO.CODEC, codec)
              .set(VIDEO.FRAME_RATE, frameRate)
              .set(VIDEO.ROTATION, rotationDegrees)
              .execute();
            return id;
        });
    }

    public List<Query> updateVideoMetadataQuery(long mediaId, long fileSize, Integer width, Integer height,
                                                LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                                Long durationMs, String codec, Double frameRate, int rotationDegrees,
                                                Double gpsLat, Double gpsLon, Double gpsAltitude, LocalDateTime now) {
        return List.of(
                dsl.update(MEDIA)
                   .set(MEDIA.FILE_SIZE, fileSize)
                   .set(MEDIA.WIDTH, width)
                   .set(MEDIA.HEIGHT, height)
                   .set(MEDIA.CAPTURE_DATE, captureDate)
                   .set(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                   .set(MEDIA.GPS_LAT, gpsLat)
                   .set(MEDIA.GPS_LON, gpsLon)
                   .set(MEDIA.GPS_ALTITUDE, gpsAltitude)
                   .set(MEDIA.LAST_SEEN_AT, now)
                   .where(MEDIA.ID.eq(mediaId)),

                dsl.update(VIDEO)
                   .set(VIDEO.DURATION_MS, durationMs)
                   .set(VIDEO.CODEC, codec)
                   .set(VIDEO.FRAME_RATE, frameRate)
                   .set(VIDEO.ROTATION, rotationDegrees)
                   .where(VIDEO.ID.eq(mediaId))
        );
    }

    /**
     * Rescan-protected counterpart to {@link #updateVideoMetadataQuery} — same fields, but skips
     * {@code CAPTURE_DATE} and/or {@code ROTATION} when the caller ({@code ImportJob}, consulting
     * {@code MediaMetadataEditRepository}) says the user has manually edited that field for this
     * media. Built via {@code UpdateQuery} rather than the fluent {@code .set(...)} chain so fields
     * can be conditionally included without generic-type gymnastics.
     */
    public List<Query> updateVideoMetadataQueryProtected(long mediaId, long fileSize, Integer width, Integer height,
                                                         LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                                         Long durationMs, String codec, Double frameRate, int rotationDegrees,
                                                         Double gpsLat, Double gpsLon, Double gpsAltitude, LocalDateTime now,
                                                         boolean captureDateProtected, boolean rotationProtected) {
        var mediaUpdate = dsl.updateQuery(MEDIA);
        mediaUpdate.addValue(MEDIA.FILE_SIZE, fileSize);
        mediaUpdate.addValue(MEDIA.WIDTH, width);
        mediaUpdate.addValue(MEDIA.HEIGHT, height);
        mediaUpdate.addValue(MEDIA.GPS_LAT, gpsLat);
        mediaUpdate.addValue(MEDIA.GPS_LON, gpsLon);
        mediaUpdate.addValue(MEDIA.GPS_ALTITUDE, gpsAltitude);
        mediaUpdate.addValue(MEDIA.LAST_SEEN_AT, now);
        if (!captureDateProtected) {
            mediaUpdate.addValue(MEDIA.CAPTURE_DATE, captureDate);
            mediaUpdate.addValue(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource));
        }
        mediaUpdate.addConditions(MEDIA.ID.eq(mediaId));

        var videoUpdate = dsl.updateQuery(VIDEO);
        videoUpdate.addValue(VIDEO.DURATION_MS, durationMs);
        videoUpdate.addValue(VIDEO.CODEC, codec);
        videoUpdate.addValue(VIDEO.FRAME_RATE, frameRate);
        if (!rotationProtected) {
            videoUpdate.addValue(VIDEO.ROTATION, rotationDegrees);
        }
        videoUpdate.addConditions(VIDEO.ID.eq(mediaId));

        return List.of(mediaUpdate, videoUpdate);
    }

    /**
     * Rescan-protected counterpart to {@link #updateMetadataQuery} — see
     * {@link #updateVideoMetadataQueryProtected} for the rationale and pattern.
     */
    public List<Query> updateMetadataQueryProtected(long mediaId, long fileSize, Integer width, Integer height,
                                                    LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                                    int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                                                    String exifExtraJson, LocalDateTime now,
                                                    boolean captureDateProtected, boolean orientationProtected) {
        var mediaUpdate = dsl.updateQuery(MEDIA);
        mediaUpdate.addValue(MEDIA.FILE_SIZE, fileSize);
        mediaUpdate.addValue(MEDIA.WIDTH, width);
        mediaUpdate.addValue(MEDIA.HEIGHT, height);
        mediaUpdate.addValue(MEDIA.CAMERA_MAKE, cameraMake);
        mediaUpdate.addValue(MEDIA.CAMERA_MODEL, cameraModel);
        mediaUpdate.addValue(MEDIA.LAST_SEEN_AT, now);
        if (!captureDateProtected) {
            mediaUpdate.addValue(MEDIA.CAPTURE_DATE, captureDate);
            mediaUpdate.addValue(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource));
        }
        if (!orientationProtected) {
            mediaUpdate.addValue(PHOTO.ORIENTATION, orientationDegrees);
        }
        mediaUpdate.addConditions(MEDIA.ID.eq(mediaId));

        var photoUpdate = dsl.updateQuery(PHOTO);
        photoUpdate.addValue(PHOTO.LENS_MODEL, lensModel);
        photoUpdate.addValue(PHOTO.EXIF_EXTRA, toJson(exifExtraJson));
        photoUpdate.addConditions(PHOTO.ID.eq(mediaId));

        return List.of(mediaUpdate, photoUpdate);
    }

    public Query touchLastSeenAtQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.LAST_SEEN_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public List<Query> updateMetadataQuery(long mediaId, long fileSize, Integer width, Integer height,
                                           LocalDateTime captureDate, CaptureDateSource captureDateSource,
                                           int orientationDegrees, String cameraMake, String cameraModel, String lensModel,
                                           String exifExtraJson, LocalDateTime now) {
        return List.of(
                dsl.update(MEDIA)
                   .set(MEDIA.FILE_SIZE, fileSize)
                   .set(MEDIA.WIDTH, width)
                   .set(MEDIA.HEIGHT, height)
                   .set(MEDIA.CAPTURE_DATE, captureDate)
                   .set(MEDIA.CAPTURE_DATE_SOURCE, sourceName(captureDateSource))
                   .set(PHOTO.ORIENTATION, orientationDegrees)
                   .set(MEDIA.CAMERA_MAKE, cameraMake)
                   .set(MEDIA.CAMERA_MODEL, cameraModel)
                   .set(MEDIA.LAST_SEEN_AT, now)
                   .where(MEDIA.ID.eq(mediaId)),

                dsl.update(PHOTO)
                   .set(PHOTO.LENS_MODEL, lensModel)
                   .set(PHOTO.EXIF_EXTRA, toJson(exifExtraJson))
                   .where(PHOTO.ID.eq(mediaId))
        );
    }

    public void updatePhotoOrientationAndResetAi(DSLContext ctx, long mediaId, int newOrientationDegrees, LocalDateTime now) {
        ctx.update(PHOTO)
           .set(PHOTO.ORIENTATION, newOrientationDegrees)
           .where(PHOTO.ID.eq(mediaId))
           .execute();

        resetAiFieldsForRotation(ctx, mediaId, now);
    }

    /**
     * Video counterpart to {@link #updatePhotoOrientationAndResetAi} — same AI-reset rationale
     * applies: a rotated video invalidates any face/CLIP data already extracted from its frames.
     */
    public void updateVideoRotationAndResetAi(DSLContext ctx, long mediaId, int newRotationDegrees, LocalDateTime now) {
        ctx.update(VIDEO)
           .set(VIDEO.ROTATION, newRotationDegrees)
           .where(VIDEO.ID.eq(mediaId))
           .execute();

        resetAiFieldsForRotation(ctx, mediaId, now);
    }

    private void resetAiFieldsForRotation(DSLContext ctx, long mediaId, LocalDateTime now) {
        ctx.update(MEDIA)
           .set(MEDIA.AI_FACE_DETECT_DONE, false)
           .set(MEDIA.AI_FACE_EMBED_DONE, false)
           .set(MEDIA.AI_CLIP_EMBED_DONE, false)
           .set(MEDIA.AI_LUCENE_INDEX_DONE, false)
           .set(MEDIA.AI_LAST_ERROR, (String) null)
           .set(MEDIA.AI_RETRY_COUNT, (short) 0)
           .set(MEDIA.AI_UPDATED_AT, now)
           .where(MEDIA.ID.eq(mediaId))
           .execute();
    }

    /**
     * Manual capture-date correction — {@code MEDIA} is shared by both photo and video, so this is
     * already type-agnostic, unlike rotation. No AI-field reset: capture date doesn't affect pixel
     * content, faces, or embeddings. Sets {@code CAPTURE_DATE_SOURCE} to {@code MANUAL_EDIT} so a
     * future rescan can tell this value came from the user, not extraction.
     */
    public void updateCaptureDate(DSLContext ctx, long mediaId, LocalDateTime newCaptureDate) {
        ctx.update(MEDIA)
           .set(MEDIA.CAPTURE_DATE, newCaptureDate)
           .set(MEDIA.CAPTURE_DATE_SOURCE, CaptureDateSource.MANUAL_EDIT.name())
           .where(MEDIA.ID.eq(mediaId))
           .execute();
    }

    public void deleteById(DSLContext ctx, long mediaId) {
        ctx.deleteFrom(MEDIA)
           .where(MEDIA.ID.eq(mediaId))
           .execute();
    }

    public List<MediaForHashing> findAllForHashing() {
        return dsl.select(MEDIA.ID, MEDIA.ABSOLUTE_PATH, MEDIA.EXTENSION, MEDIA.FILE_SIZE)
                  .from(MEDIA)
                  .fetch(r ->
                          new MediaForHashing(
                                  r.get(MEDIA.ID),
                                  r.get(MEDIA.ABSOLUTE_PATH),
                                  r.get(MEDIA.EXTENSION),
                                  r.get(MEDIA.FILE_SIZE)
                          )
                  );
    }


    // ── Timeline queries ──────────────────────────────────────────────────────────
    public TimelineData findTimelineData() {
        var dayRows = dsl.select(
                                 DSL.year(MEDIA.CAPTURE_DATE)
                                    .as("yr"),
                                 DSL.month(MEDIA.CAPTURE_DATE)
                                    .as("mo"),
                                 DSL.day(MEDIA.CAPTURE_DATE)
                                    .as("dy"),
                                 DSL.count()
                                    .as("cnt"))
                         .from(MEDIA)
                         .where(MEDIA.CAPTURE_DATE.isNotNull())
                         .groupBy(DSL.year(MEDIA.CAPTURE_DATE),
                                 DSL.month(MEDIA.CAPTURE_DATE),
                                 DSL.day(MEDIA.CAPTURE_DATE))
                         .orderBy(DSL.year(MEDIA.CAPTURE_DATE),
                                 DSL.month(MEDIA.CAPTURE_DATE),
                                 DSL.day(MEDIA.CAPTURE_DATE))
                         .fetch();

        List<TimelineData.TimelineDay> days = dayRows.stream()
                                                     .map(r -> new TimelineData.TimelineDay(
                                                             r.get("yr", Integer.class),
                                                             r.get("mo", Integer.class),
                                                             r.get("dy", Integer.class),
                                                             r.get("cnt", Integer.class)))
                                                     .toList();

        int undatedCount = dsl.fetchCount(MEDIA, MEDIA.CAPTURE_DATE.isNull());

        return new TimelineData(days, undatedCount);
    }

    public List<Media> findByCaptureDate(int year, int month, Integer day) {
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(conditionByDate(MEDIA_VIDEO.CAPTURE_DATE, year, month, day))
                .orderBy(MEDIA_VIDEO.CAPTURE_DATE, MEDIA_VIDEO.FILENAME)
                .fetch();
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(conditionByDate(MEDIA_PHOTO.CAPTURE_DATE, year, month, day))
                .orderBy(MEDIA_PHOTO.CAPTURE_DATE, MEDIA_PHOTO.FILENAME)
                .fetch();


        Stream<Either<MediaPhotoRecord, MediaVideoRecord>> videoStream = video.stream()
                                                                              .map(Either::right);
        Stream<Either<MediaPhotoRecord, MediaVideoRecord>> photoStream = photo.stream()
                                                                              .map(Either::left);

        Function<Either<MediaPhotoRecord, MediaVideoRecord>, LocalDateTime> sortKeyOf = e -> {
            if (e.isLeft()) {
                return e.getLeft()
                        .getCaptureDate();
            } else {
                return e.getRight()
                        .getCaptureDate();
            }
        };

        return Stream.concat(videoStream, photoStream)
                     .sorted(Comparator.comparing(sortKeyOf))
                     .map(Media::new)
                     .toList();
    }

    public List<MediaPhotoRecord> findByNullCaptureDate() {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.CAPTURE_DATE.isNull())
                .orderBy(MEDIA_PHOTO.FILENAME)
                .fetchInto(MediaPhotoRecord.class);
    }

    /**
     * Mixed photo+video lookup for the "undated" browse view — kept consistent with
     * {@link #findMediaByFolderId} and {@link #findByCaptureDate}, which already show mixed
     * media (implementation plan §8: "render mixed photo+video grids" applies just as much to
     * this view as to folders/timeline/albums/person pages). In practice a video's capture date
     * is rarely null (falls back to filesystem mtime, same as a photo without EXIF), but nothing
     * guarantees that.
     */
    public List<Media> findMediaByNullCaptureDate() {
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(MEDIA_PHOTO.CAPTURE_DATE.isNull())
                .fetch();
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(MEDIA_VIDEO.CAPTURE_DATE.isNull())
                .fetch();
        return Stream.concat(
                             photo.stream()
                                  .map(Media::photo),
                             video.stream()
                                  .map(Media::video))
                     .sorted(Comparator.comparing(Media::getFilename, Comparator.nullsLast(Comparator.naturalOrder())))
                     .toList();
    }

    public Optional<MediaPhotoRecord> findById(long mediaId) {
        return Optional.ofNullable(
                selectPhotoMedia()
                        .where(MEDIA_PHOTO.ID.eq(mediaId))
                        .fetchOne());
    }

    public List<MediaPhotoRecord> findByIdIn(Collection<Long> ids) {
        return selectPhotoMedia()
                .where(MEDIA_PHOTO.ID.in(ids))
                .fetch()
                .stream()
                .toList();
    }

    /**
     * Mixed photo+video lookup by id — used by {@code ThumbnailGenerationJob}, which now
     * generates thumbnails for whichever media type the grid actually asked for (see
     * implementation plan §3).
     */
    public List<Media> findMediaByIdIn(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(MEDIA_PHOTO.ID.in(ids))
                .fetch();
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(MEDIA_VIDEO.ID.in(ids))
                .fetch();
        return Stream.concat(
                             photo.stream()
                                  .map(Media::photo),
                             video.stream()
                                  .map(Media::video)
                     )
                     .toList();
    }

    public Query markFaceDetectAndEmbedDoneQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_FACE_DETECT_DONE, true)
                  .set(MEDIA.AI_FACE_EMBED_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public Query markClipEmbedDoneQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_CLIP_EMBED_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public Query markLuceneIndexDoneQuery(long mediaId, LocalDateTime now) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_LUCENE_INDEX_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public Query markErrorQuery(long mediaId, String errorMessage, LocalDateTime now) {
        String truncated = errorMessage != null && errorMessage.length() > 1024
                ? errorMessage.substring(0, 1021) + "..."
                : errorMessage;
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_LAST_ERROR, truncated)
                  .set(MEDIA.AI_RETRY_COUNT,
                          MEDIA.AI_RETRY_COUNT.add((short) 1))
                  .set(MEDIA.AI_UPDATED_AT, now)
                  .where(MEDIA.ID.eq(mediaId));
    }

    public List<Long> findPendingFaceDetect() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_FACE_DETECT_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public List<Long> findPendingClipEmbed() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_CLIP_EMBED_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public List<Long> findPendingLuceneIndex() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_CLIP_EMBED_DONE.eq(true))
                  .and(MEDIA.AI_LUCENE_INDEX_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public Query resetAiFields(long mediaId) {
        return dsl
                .update(MEDIA)
                .set(MEDIA.AI_UPDATED_AT, timeProvider.now())
                .set(MEDIA.AI_CLIP_EMBED_DONE, false)
                .set(MEDIA.AI_FACE_DETECT_DONE, false)
                .set(MEDIA.AI_FACE_EMBED_DONE, false)
                .set(MEDIA.AI_LUCENE_INDEX_DONE, false)
                .set(MEDIA.AI_TAG_DONE, false)
                .where(MEDIA.ID.eq(mediaId));
    }

    /**
     * Mixed photo+video, ordered by capture date — used by event-album generation
     * (implementation plan §6/§8, "Albums... tying videos into existing album UI").
     * <p>
     * Previously this queried the shared {@code MEDIA} table directly and force-mapped every row
     * into a {@code MediaPhotoRecord} via jOOQ's {@code fetchInto} — which happened to not throw
     * (extra POJO fields with no matching column are just left null/default), but silently
     * mislabeled every video row as a photo and dropped photo-specific columns that do exist
     * (orientation, camera make/model) for genuine photos too, since {@code MEDIA} alone doesn't
     * have them. Properly typed now via the same photo/video merge pattern as
     * {@link #findMediaByFolderId}.
     */
    public List<Media> findOrderedByCaptureDate() {
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(MEDIA_PHOTO.CAPTURE_DATE.isNotNull())
                .fetch();
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(MEDIA_VIDEO.CAPTURE_DATE.isNotNull())
                .fetch();
        return Stream.concat(
                             photo.stream()
                                  .map(Media::photo),
                             video.stream()
                                  .map(Media::video))
                     .sorted(Comparator.comparing(Media::getCaptureDate))
                     .toList();
    }

    /**
     * Mixed photo+video with GPS coordinates — used by location-album generation. See
     * {@link #findOrderedByCaptureDate} for why this replaced a {@code fetchInto(MediaPhotoRecord)}
     * hack that silently mislabeled videos.
     */
    public List<Media> findAllWithGps() {
        Result<MediaPhotoRecord> photo = selectPhotoMedia()
                .where(MEDIA_PHOTO.GPS_LAT.isNotNull()
                                          .and(MEDIA_PHOTO.GPS_LON.isNotNull()))
                .fetch();
        Result<MediaVideoRecord> video = selectVideoMedia()
                .where(MEDIA_VIDEO.GPS_LAT.isNotNull()
                                          .and(MEDIA_VIDEO.GPS_LON.isNotNull()))
                .fetch();
        return Stream.concat(
                             photo.stream()
                                  .map(Media::photo),
                             video.stream()
                                  .map(Media::video)
                     )
                     .toList();
    }

    public List<Long> findPendingAiTagging() {
        return dsl.select(MEDIA.ID)
                  .from(MEDIA)
                  .where(MEDIA.AI_TAG_DONE.eq(false))
                  .fetch(MEDIA.ID);
    }

    public Query markTaggingDone(Long mediaId) {
        return dsl.update(MEDIA)
                  .set(MEDIA.AI_TAG_DONE, true)
                  .set(MEDIA.AI_UPDATED_AT, timeProvider.now())
                  .where(MEDIA.ID.eq(mediaId));
    }

    private SelectWhereStep<MediaPhotoRecord> selectPhotoMedia() {
        return dsl.selectFrom(MEDIA_PHOTO);
    }


    private SelectWhereStep<MediaVideoRecord> selectVideoMedia() {
        return dsl.selectFrom(MEDIA_VIDEO);
    }

    private static String sourceName(CaptureDateSource captureDateSource) {
        return captureDateSource == null ? null : captureDateSource.name();
    }

    private JSON toJson(String json) {
        return json == null ? null : JSON.valueOf(json);
    }

    public Media findMediaById(Long mediaId) {
        return selectPhotoMedia().where(MEDIA_PHOTO.ID.eq(mediaId))
                                 .fetchOptional()
                                 .map(Media::photo)
                                 .orElseGet(() -> selectVideoMedia().where(MEDIA_VIDEO.ID.eq(mediaId))
                                                                    .fetchOptional()
                                                                    .map(Media::video)
                                                                    .orElse(null));
    }

    private static Condition conditionByDate(TableField<?, LocalDateTime> field, int year, int month, Integer day) {
        Condition condition = field.isNotNull()
                                   .and(DSL.year(field)
                                           .eq(year))
                                   .and(DSL.month(field)
                                           .eq(month));

        if (day != null) {
            condition = condition.and(DSL.day(field)
                                         .eq(day));
        }
        return condition;
    }
}
