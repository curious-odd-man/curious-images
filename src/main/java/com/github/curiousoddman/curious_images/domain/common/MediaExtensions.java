package com.github.curiousoddman.curious_images.domain.common;

import lombok.experimental.UtilityClass;

import java.util.Set;

/**
 * Single source of truth for "is this extension a photo or a video" — used by both the import
 * pipeline ({@code ImportJob}) and duplicate detection ({@code DuplicateDetectionJob}), which
 * both need to branch per-file on media type without duplicating the extension lists.
 */
@UtilityClass
public class MediaExtensions {

    public static final Set<String> IMAGE_EXTENSIONS = Set.of("jpg", "jpeg", "png", "cr2");

    /**
     * MP4/MOV/M4V containers only — codec is validated per-file at import time (H.264 video +
     * AAC audio only, see implementation plan "Accepted formats" decision).
     */
    public static final Set<String> VIDEO_EXTENSIONS = Set.of("mp4", "mov", "m4v");

    public static final Set<String> SUPPORTED_EXTENSIONS = union(IMAGE_EXTENSIONS, VIDEO_EXTENSIONS);

    public boolean isVideo(String extension) {
        return extension != null && VIDEO_EXTENSIONS.contains(extension.toLowerCase());
    }

    public boolean isImage(String extension) {
        return extension != null && IMAGE_EXTENSIONS.contains(extension.toLowerCase());
    }

    private static Set<String> union(Set<String> a, Set<String> b) {
        return java.util.stream.Stream.concat(a.stream(), b.stream())
                                      .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
