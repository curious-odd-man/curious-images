package com.github.curiousoddman.curious_images.event.payload;

import com.github.curiousoddman.curious_images.model.Media;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;

@Getter
@RequiredArgsConstructor
public class FaceClipProcessingFailed implements UserNotificationPayload {
    private final String    title = "Face/CLIP processing failed";
    private final Media     media;
    private final Exception e;

    @Override
    public List<String> getDescription() {
        return List.of(
                media.getAbsolutePath(),
                Objects.requireNonNullElse(e.getMessage(), e.getClass()
                                                            .getSimpleName())
        );
    }

    @Override
    public NotificationLevel getNotificationLevel() {
        return NotificationLevel.ERROR;
    }
}
