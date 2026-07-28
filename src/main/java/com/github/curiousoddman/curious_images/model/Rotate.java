package com.github.curiousoddman.curious_images.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Rotate {
    ROTATE_NONE(0),
    ROTATE_CW(90),
    ROTATE_CCW(-90),
    ROTATE_180(180);

    private final int degrees;

    public static Rotate of(Integer degrees) {
        if (degrees == null) {
            return null;
        }
        return switch (degrees) {
            case 90 -> ROTATE_CW;
            case 270 -> ROTATE_CCW;
            case 180 -> ROTATE_180;
            default -> Rotate.ROTATE_NONE;
        };
    }
}
