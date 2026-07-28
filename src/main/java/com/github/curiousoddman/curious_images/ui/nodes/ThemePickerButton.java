package com.github.curiousoddman.curious_images.ui.nodes;

import com.github.curiousoddman.curious_images.ui.styles.Theme;
import com.github.curiousoddman.curious_images.ui.styles.ThemeManager;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.effect.DropShadow;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
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
        panel.setBackground(new Background(new BackgroundFill(
                Color.WHITE, new CornerRadii(8), Insets.EMPTY)));
        panel.setBorder(new Border(new BorderStroke(
                Color.web("#e0e0e0"), BorderStrokeStyle.SOLID,
                new CornerRadii(8), new BorderWidths(1))));
        panel.setEffect(new DropShadow(12, Color.rgb(0, 0, 0, 0.18)));
        panel.setMinWidth(180);

        Label header = new Label("Choose a theme");
        header.setStyle("-fx-font-size: 11px; -fx-text-fill: #8a8a8a; -fx-padding: 4 8 6 8;");
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
        swatch.setStyle("-fx-background-color: " + swatchColorFor(theme) + "; -fx-background-radius: 6;");

        Label name = new Label(theme.getDisplayName());
        name.setStyle(isCurrent
                ? "-fx-font-weight: bold;"
                : "-fx-font-weight: normal;");

        Label check = new Label(isCurrent ? "\u2713" : "");
        check.setStyle("-fx-text-fill: #5b5ff0; -fx-font-weight: bold;");

        var spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox row = new HBox(8, swatch, name, spacer, check);
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(6, 8, 6, 8));
        row.setStyle("-fx-background-radius: 4; -fx-cursor: hand;");

        row.setOnMouseEntered(e -> row.setStyle("-fx-background-radius: 4; -fx-cursor: hand; -fx-background-color: #f2f2f7;"));
        row.setOnMouseExited(e -> row.setStyle("-fx-background-radius: 4; -fx-cursor: hand;"));

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
            case SIMPLE -> "#000000";
        };
    }
}