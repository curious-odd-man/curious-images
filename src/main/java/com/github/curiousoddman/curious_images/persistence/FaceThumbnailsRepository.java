package com.github.curiousoddman.curious_images.persistence;

import com.github.curiousoddman.curious_images.domain.ai.DetectedFace;
import com.github.curiousoddman.curious_images.domain.common.thumbnail.ThumbnailGenerator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;

@Repository
@RequiredArgsConstructor
public class FaceThumbnailsRepository {
    public static final int FACE_THUMBNAIL_SIZE = 128;

    private final ThumbnailGenerator thumbnailGenerator;

    public Path createFaceThumbnail(String originImageFullPath, BufferedImage img, DetectedFace face,
                                    Long frameOffsetMs) throws IOException {
        int x = (int) (img.getWidth() * face.x());
        int y = (int) (img.getHeight() * face.y());
        int w = (int) (img.getWidth() * face.w());
        int h = (int) (img.getHeight() * face.h());

        BufferedImage faceImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D    graphics  = faceImage.createGraphics();
        graphics.drawImage(img,
                0, 0, faceImage.getWidth(), faceImage.getHeight(),
                x, y, x + w, y + h, null
        );

        Path thumbnailPath = constructPath(originImageFullPath, x, y, w, h, frameOffsetMs);
        return thumbnailGenerator.writeThumbnail(
                                         faceImage,
                                         thumbnailPath,
                                         FACE_THUMBNAIL_SIZE)
                                 .cachePath();
    }

    /**
     * @param frameOffsetMs {@code null} for a photo. For a video, two different sampled frames
     *                      can easily produce a face at the exact same bbox (e.g. a person
     *                      standing still) — without this, their thumbnail filenames would
     *                      collide and one would silently overwrite the other.
     */
    private Path constructPath(String originImageFullPath, int x, int y, int w, int h, Long frameOffsetMs) {
        Path path = Path.of(originImageFullPath);
        String fileName = path.getFileName()
                              .toString();

        String dirName = fileName.replace('.', '_');

        String baseName = frameOffsetMs == null
                ? "%d_%d_%d_%d".formatted(x, y, w, h)
                : "%d_%d_%d_%d_f%d".formatted(x, y, w, h, frameOffsetMs);

        return path.getParent()
                   .resolve(dirName)
                   .resolve(baseName + ".jpg");
    }
}
