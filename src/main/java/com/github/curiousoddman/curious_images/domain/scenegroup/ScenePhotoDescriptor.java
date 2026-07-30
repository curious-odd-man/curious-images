package com.github.curiousoddman.curious_images.domain.scenegroup;

import java.time.LocalDateTime;

/**
 * Just enough per-photo metadata for scene-candidate matching (album-refinement-feature-spec.md
 * §4.1) — deliberately not the full {@code MediaPhotoRecord}, so
 * {@link SceneCandidateMatcher}/{@link SceneGroupingService} don't need a jOOQ dependency.
 */
public record ScenePhotoDescriptor(long photoId, LocalDateTime captureDate, Double gpsLat, Double gpsLon,
                            String absolutePath, String extension) {
}
