package com.github.curiousoddman.curious_images.ui.nodes;

import com.github.curiousoddman.curious_images.event.model.TreeViewUpdateEvent;
import com.github.curiousoddman.curious_images.event.payload.TreeViewUpdatePayload;
import com.github.curiousoddman.curious_images.persistence.AlbumRepository;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumRepository;
import com.github.curiousoddman.curious_images.ui.util.AlertHelper;
import com.github.curiousoddman.curious_images.util.TimeProvider;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import org.kordamp.ikonli.javafx.FontIcon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignP;
import org.springframework.context.ApplicationEventPublisher;

import java.util.EnumSet;
import java.util.Set;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Tree cell for the library tree. Beyond the plain icon+label rendering every node type gets,
 * two node kinds get extra inline behavior (album-refinement-feature-spec.md "additions" —
 * see the tree/menu section of the implementation plan):
 * <ul>
 *     <li>{@link LibraryTreeNode.NodeType#ALBUM_CUSTOM_ROOT} — a trailing "+" button that
 *     prompts for a name and creates a new custom album.</li>
 *     <li>{@link LibraryTreeNode.NodeType#ALBUM_CUSTOM}, {@code ALBUM_EVENT}, {@code ALBUM_LOCATION},
 *     {@code ALBUM_SIMILARITY} — double-click swaps the label for an inline {@link TextField} to
 *     rename the album.</li>
 * </ul>
 * Not a Spring bean (JavaFX {@code TreeCell}s are constructed by the cell factory, one per visible
 * row, not managed by the container) — {@code LibraryController} passes the collaborators it
 * already has injected through this constructor when it sets the cell factory.
 */
public class LibraryTreeCell extends TreeCell<LibraryTreeNode> {

    private static final Set<LibraryTreeNode.NodeType> RENAMABLE_TYPES = EnumSet.of(
            LibraryTreeNode.NodeType.ALBUM_CUSTOM,
            LibraryTreeNode.NodeType.ALBUM_EVENT,
            LibraryTreeNode.NodeType.ALBUM_LOCATION,
            LibraryTreeNode.NodeType.ALBUM_SIMILARITY);

    private final CustomAlbumRepository     customAlbumRepository;
    private final AlbumRepository           albumRepository;
    private final TimeProvider              timeProvider;
    private final ApplicationEventPublisher eventPublisher;

    private final HBox      graphicBox = new HBox(6);
    private final FontIcon  icon       = new FontIcon();
    private final Label     label      = new Label();
    private final Region    spacer     = new Region();
    private final Button    addButton  = new Button();
    private final TextField editField  = new TextField();

    private boolean editingInPlace = false;

    public LibraryTreeCell(CustomAlbumRepository customAlbumRepository, AlbumRepository albumRepository,
                           TimeProvider timeProvider, ApplicationEventPublisher eventPublisher) {
        this.customAlbumRepository = customAlbumRepository;
        this.albumRepository = albumRepository;
        this.timeProvider = timeProvider;
        this.eventPublisher = eventPublisher;

        icon.setIconSize(16);
        HBox.setHgrow(spacer, Priority.ALWAYS);

        addButton.setGraphic(new FontIcon(MaterialDesignP.PLUS));
        addButton.getStyleClass()
                 .add("tree-inline-add-button");
        addButton.setOnAction(this::onAddButtonClicked);

        editField.setOnAction(e -> commitEdit());
        editField.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                cancelInPlaceEdit();
            }
        });
        editField.focusedProperty()
                 .addListener((obs, wasFocused, isFocused) -> {
                     if (!isFocused && editingInPlace) {
                         commitEdit();
                     }
                 });

        graphicBox.setAlignment(Pos.CENTER_LEFT);
        graphicBox.setPadding(new Insets(0, 4, 0, 0));

        setOnMouseClicked(this::onCellClicked);
    }

    @Override
    protected void updateItem(LibraryTreeNode node, boolean empty) {
        runOnFxThread(() -> {
            super.updateItem(node, empty);
            editingInPlace = false;
            if (empty || node == null) {
                setGraphic(null);
                return;
            }

            icon.setIconCode(node.icon());
            label.setText(node.toString());

            graphicBox.getChildren()
                      .setAll(icon, label);
            if (node.type() == LibraryTreeNode.NodeType.ALBUM_CUSTOM_ROOT) {
                graphicBox.getChildren()
                          .addAll(spacer, addButton);
            }
            setGraphic(graphicBox);
        });
    }

    private void onCellClicked(MouseEvent event) {
        LibraryTreeNode node = getItem();
        if (node == null || event.getButton() != MouseButton.PRIMARY || event.getClickCount() != 2) {
            return;
        }
        if (RENAMABLE_TYPES.contains(node.type())) {
            startInPlaceEdit();
        }
    }

    private void startInPlaceEdit() {
        editingInPlace = true;
        editField.setText(label.getText());
        graphicBox.getChildren()
                  .set(1, editField);
        editField.requestFocus();
        editField.selectAll();
    }

    private void cancelInPlaceEdit() {
        editingInPlace = false;
        graphicBox.getChildren()
                  .set(1, label);
    }

    private void commitEdit() {
        if (!editingInPlace) {
            return;
        }
        editingInPlace = false;
        graphicBox.getChildren()
                  .set(1, label);

        LibraryTreeNode node = getItem();
        String newName = editField.getText() == null ? "" : editField.getText()
                                                                     .trim();
        if (node == null || newName.isEmpty() || newName.equals(label.getText())) {
            return;
        }

        switch (node.payload()) {
            case NodePayload.CustomAlbumPayload(long customAlbumId) -> runOnDaemonThread("RenameCustomAlbum", () -> {
                customAlbumRepository.rename(customAlbumId, newName, timeProvider.now());
                eventPublisher.publishEvent(new TreeViewUpdateEvent(this,
                        new TreeViewUpdatePayload.CustomAlbumRename(customAlbumId, newName)));
            });
            case NodePayload.AlbumPayload(long albumId) -> runOnDaemonThread("RenameAlbum", () -> {
                albumRepository.rename(albumId, newName, timeProvider.now());
                eventPublisher.publishEvent(new TreeViewUpdateEvent(this,
                        new TreeViewUpdatePayload.AlbumRename(albumId, newName)));
            });
            default -> { /* not a renamable node — RENAMABLE_TYPES already filtered this out */ }
        }
    }

    private void onAddButtonClicked(ActionEvent event) {
        AlertHelper.promptText(addButton, "New Album", "Name the new album", "")
                   .ifPresent(name -> runOnDaemonThread("CreateCustomAlbum", () -> {
                       long id = customAlbumRepository.insert(name, timeProvider.now());
                       eventPublisher.publishEvent(new TreeViewUpdateEvent(this,
                               new TreeViewUpdatePayload.CustomAlbumCreate(id, name)));
                   }));
    }
}
