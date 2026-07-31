package com.github.curiousoddman.curious_images;

import com.github.curiousoddman.curious_images.app.preloader.AnimatedPreloader;
import com.sun.javafx.application.LauncherImpl;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.transaction.annotation.EnableTransactionManagement;

@SpringBootApplication
@EnableTransactionManagement
public class Main {
    // TODO: engagement features - rank, order and plan - see doc
    // TODO: Remove all hardcoded styles --> use classes instead, then update all themes accordingly.
    // TODO: global refactoring - I need to reevaluate location of each file in app. especially those about ui controllers.
    public static void main(String[] args) {
        LauncherImpl.launchApplication(JavafxApplication.class, AnimatedPreloader.class, args);
    }
}
