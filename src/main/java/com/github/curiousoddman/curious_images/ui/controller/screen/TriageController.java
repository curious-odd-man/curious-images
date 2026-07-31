package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumPhotoRecord;
import com.github.curiousoddman.curious_images.event.model.CustomAlbumUpdatedEvent;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.PhotoRefinementState;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.persistence.SceneGroupRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.GridCellController;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridManager;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Controller for {@code triage.fxml} — triage mode (album-refinement-feature-spec.md §5): a
 * focused review flow through {@code Unassigned} photos, one scene group at a time, via
 * drag-to-corner or a digit-key shortcut. Opened as a modal {@link Stage} by
 * {@code CustomAlbumController} (see {@link PersonDetailController#onBrowseFaces} for the same
 * load-into-a-new-Stage pattern).
 */
@Slf4j
@Component
@Lazy
@RequiredArgsConstructor
public class TriageController implements Initializable {

    private static final double TILE_SIZE = 160.0;

    private final FxmlLoader                 fxmlLoader;
    private final PhotoGridManager           photoGridManager;
    private final MediaRepository            mediaRepository;
    private final CustomAlbumPhotoRepository customAlbumPhotoRepository;
    private final SceneGroupRepository       sceneGroupRepository;
    private final ApplicationEventPublisher  eventPublisher;

    @FXML
    public Label     headerTitleLabel;
    @FXML
    public Label     progressLabel;
    @FXML
    public Button    nextGroupButton;
    @FXML
    public FlowPane  tilesPane;
    @FXML
    public GridPane  dragTargetsGrid;
    @FXML
    public StackPane targetTopLeft;
    @FXML
    public StackPane targetTopRight;
    @FXML
    public StackPane targetBottomLeft;
    @FXML
    public StackPane targetBottomRight;

    private Stage stage;

    private long       albumId;
    private List<Long> pendingGroupIds = List.of();
    private int        groupIndex;
    private long       currentGroupId;

    /**
     * photoId -> its tile node in the current group, so a drop/shortcut can remove just that one tile.
     */
    private final Map<Long, javafx.scene.Node> tileNodes = new HashMap<>();
    private       ResourceBundle               gridCellResources;

    public void setStage(Stage stage) {
        this.stage = stage;
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        this.gridCellResources = resources;
        wireDragTarget(targetTopLeft, PhotoRefinementState.RATHER_NO);
        wireDragTarget(targetBottomLeft, PhotoRefinementState.NO);
        wireDragTarget(targetTopRight, PhotoRefinementState.RATHER_YES);
        wireDragTarget(targetBottomRight, PhotoRefinementState.YES);
    }

    public void init(long albumId) {
        this.albumId = albumId;
        runOnDaemonThread("LoadTriageQueue", () -> {
            List<Long> groupIds = sceneGroupRepository.findGroupIdsWithUnassignedPhotos(albumId);
            runOnFxThread(() -> {
                pendingGroupIds = groupIds;
                groupIndex = 0;
                loadCurrentGroup();
            });
        });
    }

    private void loadCurrentGroup() {
        if (groupIndex >= pendingGroupIds.size()) {
            finishTriage();
            return;
        }
        currentGroupId = pendingGroupIds.get(groupIndex);
        progressLabel.setText("Group " + (groupIndex + 1) + " of " + pendingGroupIds.size());
        nextGroupButton.setVisible(false);
        nextGroupButton.setManaged(false);

        runOnDaemonThread("LoadTriageGroup", () -> {
            List<CustomAlbumPhotoRecord> rows = customAlbumPhotoRepository.findUnassignedByAlbumAndGroup(albumId, currentGroupId);
            List<Long> photoIds = rows.stream()
                                      .map(CustomAlbumPhotoRecord::getPhotoId)
                                      .toList();
            Map<Long, GridCellData> dataById = photoGridManager.createData(mediaRepository.findMediaByIdIn(photoIds))
                                                               .stream()
                                                               .collect(Collectors.toMap(GridCellData::mediaId, d -> d));
            runOnFxThread(() -> renderGroup(rows, dataById));
        });
    }

    private void renderGroup(List<CustomAlbumPhotoRecord> rows, Map<Long, GridCellData> dataById) {
        tilesPane.getChildren()
                 .clear();
        tileNodes.clear();
        for (CustomAlbumPhotoRecord row : rows) {
            GridCellData data = dataById.get(row.getPhotoId());
            if (data != null) {
                javafx.scene.Node tile = buildDraggableTile(row.getPhotoId(), data);
                tileNodes.put(row.getPhotoId(), tile);
                tilesPane.getChildren()
                         .add(tile);
            }
        }
        if (rows.isEmpty()) {
            // Nothing left Unassigned in this group (e.g. re-entering triage after a manual edit
            // elsewhere already resolved it) — skip straight to the next one.
            advanceGroup();
        }
    }

    private javafx.scene.Node buildDraggableTile(long photoId, GridCellData data) {
        LoadedFxml<GridCellController> loaded         = fxmlLoader.load(FxmlView.PHOTO_CELL, gridCellResources);
        GridCellController             cellController = loaded.controller();
        cellController.bindThumbnailSize(new SimpleDoubleProperty(TILE_SIZE));
        cellController.showPlaceholder(data);
        cellController.showImage(data);
        javafx.scene.Parent cellNode = loaded.parent();
        cellNode.setStyle("-fx-pref-width: " + TILE_SIZE + "; -fx-pref-height: " + TILE_SIZE + ";");
        cellNode.setFocusTraversable(true);

        cellNode.setOnDragDetected(e -> {
            var              db      = cellNode.startDragAndDrop(TransferMode.MOVE);
            ClipboardContent content = new ClipboardContent();
            content.putString(String.valueOf(photoId));
            db.setContent(content);
            cellNode.getStyleClass()
                    .add(CssClasses.DRAGGING_TILE);
            showDragTargets();
            e.consume();
        });
        cellNode.setOnDragDone(e -> {
            cellNode.getStyleClass()
                    .remove(CssClasses.DRAGGING_TILE);
            hideDragTargets();
            e.consume();
        });
        cellNode.setOnKeyPressed(e -> {
            PhotoRefinementState mapped = stateForShortcutKey(e.getCode());
            if (mapped != null) {
                applyState(photoId, mapped);
            }
        });

        return cellNode;
    }

    private PhotoRefinementState stateForShortcutKey(KeyCode code) {
        return switch (code) {
            case DIGIT1 -> PhotoRefinementState.RATHER_NO;
            case DIGIT2 -> PhotoRefinementState.NO;
            case DIGIT3 -> PhotoRefinementState.RATHER_YES;
            case DIGIT4 -> PhotoRefinementState.YES;
            default -> null;
        };
    }

    private void wireDragTarget(StackPane target, PhotoRefinementState state) {
        String activeClass = CssClasses.DRAG_TARGET_ACTIVE_PREFIX + state.getCssSuffix();
        target.setOnDragOver(e -> {
            if (e.getDragboard()
                 .hasString()) {
                e.acceptTransferModes(TransferMode.MOVE);
                if (!target.getStyleClass()
                           .contains(CssClasses.DRAG_TARGET_ACTIVE)) {
                    target.getStyleClass()
                          .addAll(CssClasses.DRAG_TARGET_ACTIVE, activeClass);
                }
            }
            e.consume();
        });
        target.setOnDragExited(e -> {
            target.getStyleClass()
                  .removeAll(CssClasses.DRAG_TARGET_ACTIVE, activeClass);
            e.consume();
        });
        target.setOnDragDropped(e -> {
            String string = e.getDragboard()
                             .getString();
            target.getStyleClass()
                  .removeAll(CssClasses.DRAG_TARGET_ACTIVE, activeClass);
            if (string != null) {
                applyState(Long.parseLong(string), state);
            }
            e.setDropCompleted(true);
            e.consume();
        });
    }

    private void showDragTargets() {
        dragTargetsGrid.setVisible(true);
        dragTargetsGrid.setManaged(true);
    }

    private void hideDragTargets() {
        dragTargetsGrid.setVisible(false);
        dragTargetsGrid.setManaged(false);
    }

    private void applyState(long photoId, PhotoRefinementState newState) {
        runOnDaemonThread("SetTriageState", () -> {
            customAlbumPhotoRepository.updateState(albumId, photoId, newState);
            eventPublisher.publishEvent(new CustomAlbumUpdatedEvent(this, albumId));
            runOnFxThread(() -> {
                javafx.scene.Node tile = tileNodes.remove(photoId);
                if (tile != null) {
                    tilesPane.getChildren()
                             .remove(tile);
                }
                if (newState == PhotoRefinementState.YES) {
                    // Spec §5: once any photo in the group is marked Yes, the user *may* choose
                    // to advance (remaining Unassigned photos become RatherNo if they do) —
                    // showing the button here, not auto-advancing immediately, is what makes
                    // that "may choose to" optional rather than forced.
                    nextGroupButton.setVisible(true);
                    nextGroupButton.setManaged(true);
                }
                if (tileNodes.isEmpty()) {
                    // Every photo in the group now has a state — auto-advance regardless of
                    // whether it happened via the Next Group button or by resolving them all
                    // individually (spec §5's auto-advance applies either way).
                    advanceGroup();
                }
            });
        });
    }

    @FXML
    public void onNextGroup() {
        Set<Long> remaining = Set.copyOf(tileNodes.keySet());
        runOnDaemonThread("SkipRemainingToRatherNo", () -> {
            if (!remaining.isEmpty()) {
                customAlbumPhotoRepository.updateStateBulk(albumId, remaining, PhotoRefinementState.RATHER_NO);
                eventPublisher.publishEvent(new CustomAlbumUpdatedEvent(this, albumId));
            }
            runOnFxThread(this::advanceGroup);
        });
    }

    private void advanceGroup() {
        groupIndex++;
        loadCurrentGroup();
    }

    private void finishTriage() {
        headerTitleLabel.setText("Triage complete");
        progressLabel.setText("");
        tilesPane.getChildren()
                 .clear();
        eventPublisher.publishEvent(new CustomAlbumUpdatedEvent(this, albumId));
        if (stage != null) {
            stage.close();
        }
    }

    @FXML
    public void onClose() {
        eventPublisher.publishEvent(new CustomAlbumUpdatedEvent(this, albumId));
        if (stage != null) {
            stage.close();
        }
    }
}
