package com.github.curiousoddman.curious_images.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Rotate {
    ROTATE_CW(90),
    ROTATE_CCW(-90),
    ROTATE_180(180);

    private final int degrees;
}
