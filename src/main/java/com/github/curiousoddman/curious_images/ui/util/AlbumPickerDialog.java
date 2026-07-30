package com.github.curiousoddman.curious_images.ui.util;

import javafx.geometry.Insets;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Optional;

/**
 * "Add to Album..." prompt for {@code GridContextMenu} — lets the user pick one of their
 * existing custom albums, or type a name to create a new one on the spot (so they don't have to
 * cancel, go create an album via the tree's "+" button, then retry). Same
 * {@code Dialog}-with-a-{@code GridPane} construction style as {@link MetadataEditDialogs}.
 */
@UtilityClass
public final class AlbumPickerDialog {

    /**
     * Minimal transfer type so this stays pure-JavaFX — no jOOQ record dependency in {@code ui.util}.
     */
    public record AlbumChoice(long id, String name) {
        @Override
        public String toString() {
            return name;
        }
    }

    public sealed interface Result {
        record Existing(long albumId) implements Result {
        }

        record New(String name) implements Result {
        }
    }

    /**
     * @return empty if cancelled, or if neither an existing album was selected nor a new name
     * typed (both blank counts as "nothing to do", not an error).
     */
    public static Optional<Result> ask(Window owner, List<AlbumChoice> existingAlbums) {
        Dialog<Result> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle("Add to Album");
        dialog.getDialogPane()
              .getButtonTypes()
              .addAll(ButtonType.OK, ButtonType.CANCEL);

        ComboBox<AlbumChoice> existingCombo = new ComboBox<>();
        existingCombo.getItems()
                     .setAll(existingAlbums);
        existingCombo.setPromptText(existingAlbums.isEmpty() ? "No albums yet" : "Choose an album...");
        existingCombo.setDisable(existingAlbums.isEmpty());
        existingCombo.setMaxWidth(Double.MAX_VALUE);
        if (!existingAlbums.isEmpty()) {
            existingCombo.getSelectionModel()
                         .selectFirst();
        }

        TextField newAlbumField = new TextField();
        newAlbumField.setPromptText("New album name");

        // Typing a new name takes precedence over the dropdown selection — clearing the combo
        // selection makes that precedence visible rather than silent.
        newAlbumField.textProperty()
                     .addListener((obs, oldText, newText) -> {
                         if (newText != null && !newText.isBlank()) {
                             existingCombo.getSelectionModel()
                                          .clearSelection();
                         }
                     });

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.addRow(0, new Label("Existing album:"), existingCombo);
        grid.addRow(1, new Label("or new album:"), newAlbumField);
        dialog.getDialogPane()
              .setContent(grid);

        dialog.setResultConverter(button -> {
            if (button != ButtonType.OK) {
                return null;
            }
            String newName = newAlbumField.getText() == null ? "" : newAlbumField.getText()
                                                                                  .trim();
            if (!newName.isEmpty()) {
                return new Result.New(newName);
            }
            AlbumChoice selected = existingCombo.getValue();
            return selected == null ? null : new Result.Existing(selected.id());
        });

        return dialog.showAndWait();
    }
}
