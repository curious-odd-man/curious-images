package com.github.curiousoddman.curious_images.ui.styles;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum Theme {
    SIMPLE("styles/default.css", "Simple"),
    DARK("styles/theme-1-dark.css", "Dark Mode"),
    WARM("styles/theme-2-warm-editorial.css", "Warm Editorial"),
    MONO("styles/theme-3-minimal-mono.css", "Minimal Mono"),
    VIBRANT("styles/theme-4-vibrant-material.css", "Vibrant Material");

    private final String resourcePath;
    private final String displayName;
}
