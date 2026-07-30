package com.github.curiousoddman.curious_images.domain.scenegroup;

/**
 * Bit-difference distance between two 64-bit perceptual hashes (see {@link PerceptualHasher}).
 * Lower = more visually similar; 0 = identical hash.
 */
public final class HammingDistance {

    private HammingDistance() {
    }

    public static int between(long hashA, long hashB) {
        return Long.bitCount(hashA ^ hashB);
    }
}
