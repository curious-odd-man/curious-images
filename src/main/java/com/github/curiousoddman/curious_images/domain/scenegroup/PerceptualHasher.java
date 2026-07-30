package com.github.curiousoddman.curious_images.domain.scenegroup;


import com.github.curiousoddman.curious_images.util.ImageUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Perceptual hashing for scene-group similarity confirmation (album-refinement-feature-spec.md
 * §4.2). Deliberately separate from {@code domain.dedupe.hasher.PixelHasher}/{@code FileHasher}:
 * those compute exact-content hashes (bit-identical or byte-identical) for duplicate detection,
 * whereas this computes a difference-hash (dHash) that tolerates minor visual differences between
 * near-duplicate burst shots — the two problems need different algorithms, not just different
 * thresholds on the same one.
 * <p>
 * dHash was chosen (over e.g. pHash/DCT) per the spec's "no embedding model required" constraint:
 * it's a handful of OpenCV ops (resize + grayscale + pairwise compare), no FFT, no ONNX runtime
 * involved — consistent with keeping this pipeline on-device/offline-friendly.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerceptualHasher {
    // 9x8 so each row yields 8 pairwise comparisons (9 columns -> 8 adjacent pairs) = 64 bits/long.
    private static final int HASH_WIDTH  = 9;
    private static final int HASH_HEIGHT = 8;

    private final ImageUtils imageUtils;

    /**
     * @return a 64-bit dHash, or empty if the image at {@code sourceFile} couldn't be decoded —
     * same "skip this one, don't fail the run" policy as {@link com.github.curiousoddman.curious_images.domain.dedupe.hasher.PixelHasher}.
     */
    public Optional<Long> hash(Path sourceFile, String extension) {
        Optional<Mat> image = imageUtils.imageOrCr2Preview(sourceFile, extension, 0);
        return image.map(this::hashDecoded);
    }

    private long hashDecoded(Mat image) {
        Mat gray  = new Mat();
        Mat small = new Mat();
        try {
            Imgproc.cvtColor(image, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.resize(gray, small, new Size(HASH_WIDTH, HASH_HEIGHT), 0, 0, Imgproc.INTER_AREA);

            long hash = 0L;
            int  bit  = 0;
            for (int y = 0; y < HASH_HEIGHT; y++) {
                for (int x = 0; x < HASH_WIDTH - 1; x++) {
                    double left  = small.get(y, x)[0];
                    double right = small.get(y, x + 1)[0];
                    if (left > right) {
                        hash |= (1L << bit);
                    }
                    bit++;
                }
            }
            return hash;
        } finally {
            gray.release();
            small.release();
            image.release();
        }
    }
}
