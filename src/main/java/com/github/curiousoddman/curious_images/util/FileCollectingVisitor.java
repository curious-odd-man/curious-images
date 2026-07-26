package com.github.curiousoddman.curious_images.util;

import lombok.RequiredArgsConstructor;

import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class FileCollectingVisitor extends SimpleFileVisitor<Path> {
    private final Set<String> supportedExtensions;
    private final List<Path>  found;

    /**
     * Regular files visited whose extension isn't in {@code supportedExtensions} — i.e. never
     * even attempted as a photo/video. Read by {@code ImportJob} after the walk completes to
     * populate the "unsupported extension" counter in the Last Import stats view.
     */
    private final AtomicLong unsupportedExtensionCount = new AtomicLong();

    @Override
    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
        if (attrs.isRegularFile()) {
            if (supportedExtensions.contains(FileUtils.extensionOf(file.getFileName()
                                                                       .toString()))) {
                found.add(file);
            } else {
                unsupportedExtensionCount.incrementAndGet();
            }
        }
        return FileVisitResult.CONTINUE;
    }

    public long getUnsupportedExtensionCount() {
        return unsupportedExtensionCount.get();
    }
}
