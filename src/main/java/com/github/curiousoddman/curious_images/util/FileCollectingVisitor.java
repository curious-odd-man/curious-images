package com.github.curiousoddman.curious_images.util;

import lombok.RequiredArgsConstructor;

import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;

@RequiredArgsConstructor
public class FileCollectingVisitor extends SimpleFileVisitor<Path> {
    private final Set<String> supportedExtensions;
    private final List<Path>  found;

    /**
     * Regular files visited whose extension isn't in {@code supportedExtensions} — i.e. never
     * even attempted as a photo/video. Read by {@code ImportJob} after the walk completes to
     * both populate the "unsupported extension" counter and record a per-file SKIPPED issue for
     * each one (see {@code ImportStatsTracker#recordUnsupportedExtension}) in the Last Import view.
     */
    private final List<Path> unsupportedExtensionFiles = Collections.synchronizedList(new ArrayList<>());

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()) {
            if (supportedExtensions.contains(FileUtils.extensionOf(file.getFileName()
                                                                       .toString()))) {
                found.add(file);
            } else {
                unsupportedExtensionFiles.add(file);
            }
        }
        return FileVisitResult.CONTINUE;
    }

    public List<Path> getUnsupportedExtensionFiles() {
        return unsupportedExtensionFiles;
    }
}
