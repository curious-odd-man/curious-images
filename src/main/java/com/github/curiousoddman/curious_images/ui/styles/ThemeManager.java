package com.github.curiousoddman.curious_images.ui.styles;

import javafx.scene.Scene;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.prefs.Preferences;

@Slf4j
public class ThemeManager {
    private static final String      PREF_KEY = "app.theme";
    private static final Preferences PREFS    = Preferences.userNodeForPackage(ThemeManager.class);

    // Weakly-held would be safer for long-lived apps with disposable dialogs,
    // but a plain list is fine for a handful of persistent windows.
    private static final List<Scene> REGISTERED_SCENES = new ArrayList<>();

    public static void register(Scene scene) {
        REGISTERED_SCENES.add(scene);
        log.info("Registered scene. Currently registered {} scenes", REGISTERED_SCENES.size());
        applyTheme(scene, getCurrentTheme());
    }

    public static void unregister(Scene scene) {
        REGISTERED_SCENES.remove(scene);
        log.info("UnRegistered scene. Currently registered {} scenes", REGISTERED_SCENES.size());
    }

    public static Theme getCurrentTheme() {
        String saved = PREFS.get(PREF_KEY, Theme.SIMPLE.name());
        try {
            return Theme.valueOf(saved);
        } catch (IllegalArgumentException e) {
            return Theme.SIMPLE;
        }
    }

    /**
     * Switch theme for every registered scene, and persist the choice.
     */
    public static void setTheme(Theme theme) {
        PREFS.put(PREF_KEY, theme.name());
        for (Scene scene : REGISTERED_SCENES) {
            applyTheme(scene, theme);
        }
    }

    private static void applyTheme(Scene scene, Theme theme) {
        scene.getStylesheets()
             .clear();
        String url = ThemeManager.class.getResource("/" + theme.getResourcePath())
                                       .toExternalForm();
        scene.getStylesheets()
             .add(url);
    }
}
