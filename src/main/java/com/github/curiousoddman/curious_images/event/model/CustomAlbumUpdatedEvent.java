package com.github.curiousoddman.curious_images.event.model;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published by {@code SceneGroupingJob} after it finishes processing one custom album's
 * not-yet-clustered photos. Consumed by the custom-album panel (Phase 5) to refresh the
 * unrefined/triage views live if that album happens to be open while grouping runs, and by
 * {@code LibraryController} to refresh the tree (e.g. cover-photo fallback becoming available).
 */
@Getter
public class CustomAlbumUpdatedEvent extends ApplicationEvent {
    private final long customAlbumId;

    public CustomAlbumUpdatedEvent(Object source, long customAlbumId) {
        super(source);
        this.customAlbumId = customAlbumId;
    }
}
