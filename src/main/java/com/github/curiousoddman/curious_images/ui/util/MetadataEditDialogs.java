package com.github.curiousoddman.curious_images.ui.util;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Spinner;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import lombok.experimental.UtilityClass;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Optional;

@UtilityClass
public final class MetadataEditDialogs {
    /**
     * Prompts for an absolute rotation in degrees (0/90/180/270).
     */
    public static Optional<Integer> askRotationDegrees(Window owner, int currentDegrees) {
        int normalizedCurrent = ((currentDegrees % 360) + 360) % 360;

        ChoiceDialog<Integer> dialog = new ChoiceDialog<>(normalizedCurrent, 0, 90, 180, 270);
        dialog.initOwner(owner);
        dialog.setTitle("Set rotation");
        dialog.setHeaderText(null);
        dialog.setContentText("Rotation (degrees):");
        return dialog.showAndWait();
    }

    /**
     * Prompts for an absolute capture date + time (used for both single and bulk edits).
     */
    public static Optional<LocalDateTime> askCaptureDate(Window owner, LocalDateTime initial) {
        Dialog<LocalDateTime> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Set capture date");
        dialog.getDialogPane()
              .getButtonTypes()
              .addAll(ButtonType.OK, ButtonType.CANCEL);

        DatePicker       datePicker    = new DatePicker(initial != null ? initial.toLocalDate() : LocalDate.now());
        Spinner<Integer> hourSpinner   = new Spinner<>(0, 23, initial != null ? initial.getHour() : 0);
        Spinner<Integer> minuteSpinner = new Spinner<>(0, 59, initial != null ? initial.getMinute() : 0);
        hourSpinner.setEditable(true);
        minuteSpinner.setEditable(true);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Date:"), datePicker);
        grid.addRow(1, new Label("Time:"), hourSpinner, new Label(":"), minuteSpinner);
        dialog.getDialogPane()
              .setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK || datePicker.getValue() == null) {
                return null;
            }
            return LocalDateTime.of(datePicker.getValue(), LocalTime.of(hourSpinner.getValue(), minuteSpinner.getValue()));
        });

        return dialog.showAndWait();
    }
}
