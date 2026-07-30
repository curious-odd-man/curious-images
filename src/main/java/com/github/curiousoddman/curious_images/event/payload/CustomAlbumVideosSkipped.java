package com.github.curiousoddman.curious_images.event.payload;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * Shown after "Add to Album..." when the selection included videos — those are silently
 * dropped by {@code CustomAlbumPhotoAdditionService} (custom albums are photos-only per
 * album-refinement-feature-spec.md), so this is the user-visible surface of that filtering
 * rather than a silent no-op.
 */
@Getter
@RequiredArgsConstructor
public class CustomAlbumVideosSkipped implements UserNotificationPayload {
    private final String title = "Videos skipped";
    private final int    photosAdded;
    private final int    videosSkipped;
    private final String albumName;

    @Override
    public List<String> getDescription() {
        return List.of("Added " + photosAdded + " photo(s) to \"" + albumName + "\"; "
                + videosSkipped + " video(s) in the selection were skipped — custom albums only hold photos.");
    }

    @Override
    public NotificationLevel getNotificationLevel() {
        return NotificationLevel.WARNING;
    }
}
