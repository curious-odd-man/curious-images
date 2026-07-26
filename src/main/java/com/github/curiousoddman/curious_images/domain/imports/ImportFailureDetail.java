package com.github.curiousoddman.curious_images.domain.imports;

/**
 * One file that failed to import, paired with the reason it failed. Collected during a run by
 * {@link ImportStatsTracker} and persisted as part of {@link com.github.curiousoddman.curious_images.model.ImportJobStats}
 * so the Last Import view can show both a per-reason breakdown and the individual file list.
 *
 * @param absolutePath the file that failed
 * @param reason       {@code Throwable#getMessage()} (or the exception's {@code toString()} when
 *                     the message is {@code null}) — never the raw stack trace.
 */
public record ImportFailureDetail(String absolutePath, String reason) {}
