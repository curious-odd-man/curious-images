package com.github.curiousoddman.curious_images.ui.nodes;

import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.ui.styles.Theme;
import com.github.curiousoddman.curious_images.ui.styles.ThemeManager;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

public class ThemePickerButton {
    private final Popup popup = new Popup();

    private final Button parent;

    public ThemePickerButton(Button parent) {
        this.parent = parent;

        popup.setAutoHide(true);   // closes automatically on outside click / focus loss
        popup.setHideOnEscape(true);
        popup.getContent()
             .add(buildPanel());

        ThemeManager.register(popup.getScene());

        parent.setOnAction(e -> togglePopup());
    }

    private void togglePopup() {
        if (popup.isShowing()) {
            popup.hide();
            return;
        }
        // Position just below the button, left-aligned with it.
        Bounds bounds = parent.localToScreen(parent.getBoundsInLocal());
        popup.show(parent.getScene()
                         .getWindow(), bounds.getMinX(), bounds.getMaxY() + 4);
    }

    private VBox buildPanel() {
        VBox panel = new VBox(2);
        panel.setPadding(new Insets(6));
        panel.getStyleClass()
             .add(CssClasses.THEME_PICKER_PANEL);
        panel.setMinWidth(180);

        Label header = new Label("Choose a theme");
        header.getStyleClass()
              .add(CssClasses.THEME_PICKER_HEADER);
        panel.getChildren()
             .add(header);

        Theme current = ThemeManager.getCurrentTheme();

        for (Theme theme : Theme.values()) {
            panel.getChildren()
                 .add(buildRow(theme, theme == current));
        }

        return panel;
    }

    private Node buildRow(Theme theme, boolean isCurrent) {
        Label swatch = new Label();
        swatch.setMinSize(12, 12);
        swatch.setMaxSize(12, 12);
        swatch.getStyleClass()
              .add(CssClasses.THEME_PICKER_SWATCH);
        // The swatch previews the color of the OPTION being rendered, not the currently active
        // theme, so this one genuinely needs a per-row dynamic color and can't be a static CSS
        // class the way the rest of the panel now is.
        swatch.setStyle("-fx-background-color: " + swatchColorFor(theme) + ";");

        Label name = new Label(theme.getDisplayName());
        if (isCurrent) {
            name.getStyleClass()
                .add(CssClasses.BOLD_LABEL);
        }

        Label check = new Label(isCurrent ? "\u2713" : "");
        check.getStyleClass()
             .add(CssClasses.THEME_PICKER_CHECK);

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, swatch, name, spacer, check);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.getStyleClass()
           .add(CssClasses.THEME_PICKER_ROW);

        row.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                ThemeManager.setTheme(theme);
                popup.hide();
            }
        });

        return row;
    }

    private String swatchColorFor(Theme theme) {
        return switch (theme) {
            case DARK -> "#3ecfb2";
            case WARM -> "#c1633b";
            case MONO -> "#2d2d2d";
            case VIBRANT -> "#5b5ff0";
            case SIMPLE -> "#2f7dd1";
        };
    }
}