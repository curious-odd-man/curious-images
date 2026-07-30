package com.github.curiousoddman.curious_images.domain.scenegroup;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * Cheap pre-filter (album-refinement-feature-spec.md §4.1): decides whether two photos are close
 * enough in time/location to be worth the more expensive perceptual-hash comparison
 * ({@link PerceptualHasher} + {@link HammingDistance}, done by {@link SceneGroupingService}).
 * Pure/stateless — no I/O, no DB access — so it's trivial to unit test independent of the rest of
 * the pipeline.
 */
@Component
public class SceneCandidateMatcher {

    private static final double EARTH_RADIUS_METERS = 6_371_000.0;

    /**
     * @return {@code true} if the two photos are close enough in time (and, when both have GPS,
     * close enough in location) to warrant a perceptual-hash comparison. Photos missing a capture
     * date entirely are never candidates for anything — there's no proximity to evaluate — so
     * they always end up as singletons rather than risk grouping-by-coincidence.
     */
    public boolean isCandidate(ScenePhotoDescriptor a, ScenePhotoDescriptor b,
                               Duration timestampWindow, int geoRadiusMeters) {
        if (a.captureDate() == null || b.captureDate() == null) {
            return false;
        }

        long secondsApart = Math.abs(ChronoUnit.SECONDS.between(a.captureDate(), b.captureDate()));
        if (secondsApart > timestampWindow.toSeconds()) {
            return false;
        }

        boolean bothHaveGps = a.gpsLat() != null && a.gpsLon() != null
                && b.gpsLat() != null && b.gpsLon() != null;
        if (!bothHaveGps) {
            // Spec §4.1: geolocation missing for one or both -> fall back to timestamp-only
            // proximity, which has already passed above.
            return true;
        }

        return haversineMeters(a.gpsLat(), a.gpsLon(), b.gpsLat(), b.gpsLon()) <= geoRadiusMeters;
    }

    private static double haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double h = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return 2 * EARTH_RADIUS_METERS * Math.asin(Math.sqrt(h));
    }
}
