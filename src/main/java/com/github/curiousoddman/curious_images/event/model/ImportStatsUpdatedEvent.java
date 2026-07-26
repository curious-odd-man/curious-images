package com.github.curiousoddman.curious_images.event.model;

import com.github.curiousoddman.curious_images.model.ImportJobStats;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * Published by {@code ImportJob} whenever it persists a new snapshot of the current/last import
 * job's stats — both throttled mid-run updates and the final one. Consumed by
 * {@code ImportStatsController} (refreshes itself if currently visible) and {@code TreeManager}
 * (keeps the "Last Import" tree item's label in sync without a full tree rebuild).
 */
@Getter
public class ImportStatsUpdatedEvent extends ApplicationEvent {
    private final ImportJobStats stats;

    public ImportStatsUpdatedEvent(Object source, ImportJobStats stats) {
        super(source);
        this.stats = stats;
    }
}
