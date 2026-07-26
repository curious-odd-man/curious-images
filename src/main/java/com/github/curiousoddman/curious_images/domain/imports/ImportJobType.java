package com.github.curiousoddman.curious_images.domain.imports;

/**
 * How a given run of {@link ImportJob} was triggered — drives the "Import type" field shown in
 * the Last Import stats view (see {@code ImportStatsController}).
 */
public enum ImportJobType {
    /**
     * Source path(s) registered in-place as new {@code IMPORT_ROOT}s and scanned where they are
     * (see {@code AddFilesRequest#copyToDestination()} == false).
     */
    NEW_ROOT,
    /**
     * Source path(s) first copied into a destination folder, then the copies are scanned
     * (see {@code AddFilesRequest#copyToDestination()} == true).
     */
    COPY,
    /**
     * Re-scan of one or more already-known {@code IMPORT_ROOT}s
     * (see {@code ReScanLibraryController}, {@code RescanRootsController}).
     */
    RESCAN
}
