package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // TODO: engagement features - rank, order and plan - see doc
    // TODO: Selections/collections/albums whatever - integrate media-shoot-magic here
    // TODO: AI models eviction - no need to store those in memory all the time.
    // TODO: Remove all hardcoded styles --> use classes instead, then update all themes accordingly. Remove colors from icons - use classes instead
    // FIXME: I believe that prototype scope controllers are never removed from the context - leading to a memory leak. Investigate how to address this
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
