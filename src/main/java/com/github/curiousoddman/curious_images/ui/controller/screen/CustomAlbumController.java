package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumRecord;
import com.github.curiousoddman.curious_images.event.model.CustomAlbumUpdatedEvent;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.PhotoRefinementState;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.GridCellController;
import com.github.curiousoddman.curious_images.ui.controller.custom.GridController;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridManager;
import com.github.curiousoddman.curious_images.ui.controller.services.ThumbnailReadyEventListener;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxManage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxUnmanage;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Controller for {@code custom_album.fxml} — the Refined/Unrefined shell for one custom album
 * (album-refinement-feature-spec.md §7).
 * <p>
 * Refined view reuses a single embedded {@link GridController} (Phase 5). Unrefined view
 * (Phase 6) is built entirely from scene-group {@link TitledPane} sections containing
 * {@code photo_cell.fxml} tiles ({@link GridCellController} is {@code @Scope("prototype")}, so
 * many instances are safe — see the FXML header comment for why this deliberately does NOT use
 * more {@link GridController} instances instead).
 * <p>
 * Keyboard shortcuts (digits 1–5 while a tile has focus) and the exact bulk-action button set are
 * a reasonable first pass — the implementation plan flags exact key bindings as still open.
 */
@Slf4j
@Component
@Lazy
@RequiredArgsConstructor
public class CustomAlbumController implements Initializable, ThumbnailReadyEventListener {

    private static final PhotoRefinementState[] ALL_STATES = PhotoRefinementState.values();
    private static final double                 TILE_SIZE  = 140.0;

    private final FxmlLoader                 fxmlLoader;
    private final PhotoGridManager           photoGridManager;
    private final MediaRepository            mediaRepository;
    private final CustomAlbumRepository      customAlbumRepository;
    private final CustomAlbumPhotoRepository customAlbumPhotoRepository;

    @FXML
    public Label        albumNameLabel;
    @FXML
    public Label        lockedHintLabel;
    @FXML
    public ToggleButton unrefinedToggle;
    @FXML
    public ToggleButton refinedToggle;
    @FXML
    public BorderPane   refinedGridBorderPane;
    @FXML
    public ScrollPane   unrefinedScrollPane;
    @FXML
    public VBox         unrefinedSectionsBox;

    private GridController gridController;

    private long    currentAlbumId;
    private boolean refinedLocked = true;

    /**
     * All membership rows for the current album; mutated in place as states change so toggling/refreshing doesn't need a DB round-trip.
     */
    private List<CustomAlbumPhotoRecord> currentPhotoRows = new ArrayList<>();

    /**
     * photoId -> its currently-rendered tile's GridCellController, for thumbnail-ready refresh.
     */
    private final Map<Long, GridCellController> unrefinedTileCells    = new HashMap<>();
    /**
     * photoId -> its currently-rendered tile's wrapper node, for state-border/selection styling.
     */
    private final Map<Long, VBox>               unrefinedTileWrappers = new HashMap<>();
    /**
     * Selection used only by the "mark unselected as No" bulk action (spec §7) — cleared on every unrefined rebuild.
     */
    private final Set<Long>                     selectedPhotoIds      = new HashSet<>();

    private ResourceBundle gridCellResources;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        this.gridCellResources = resources;
        LoadedFxml<GridController> loaded = fxmlLoader.load(FxmlView.PHOTO_GRID, resources);
        gridController = loaded.controller();
        refinedGridBorderPane.setCenter(loaded.parent());
    }

    public void loadAlbum(long albumId) {
        currentAlbumId = albumId;
        long myGeneration = gridController.initiateChange();

        runOnDaemonThread("LoadCustomAlbum", () -> {
            Optional<CustomAlbumRecord>  albumOpt = customAlbumRepository.findById(albumId);
            List<CustomAlbumPhotoRecord> rows     = new ArrayList<>(customAlbumPhotoRepository.findByAlbumId(albumId));

            runOnFxThread(() -> {
                if (myGeneration != gridController.currentChange() || albumId != currentAlbumId) {
                    return; // a newer loadAlbum() call has since superseded this one
                }
                albumOpt.ifPresent(a -> albumNameLabel.setText(a.getName()));
                currentPhotoRows = rows;
                applyLockState();
                unrefinedToggle.setSelected(true);
                switchToUnrefined();
                renderUnrefined();
            });
        });
    }

    private void applyLockState() {
        refinedLocked = currentPhotoRows.stream()
                                        .noneMatch(r -> r.getState() == PhotoRefinementState.YES.getDbValue());
        refinedToggle.setDisable(refinedLocked);
        fxManage(!refinedLocked, lockedHintLabel);
        if (refinedLocked && refinedToggle.isSelected()) {
            // The last remaining Yes photo just got changed away while Refined was showing —
            // re-lock immediately per spec §7, rather than leaving a stale Refined view up.
            unrefinedToggle.setSelected(true);
            switchToUnrefined();
            renderUnrefined();
        }
    }

    @FXML
    public void onShowRefined() {
        if (refinedLocked) {
            unrefinedToggle.setSelected(true);
            return;
        }
        switchToRefined();
        renderRefined();
    }

    @FXML
    public void onShowUnrefined() {
        switchToUnrefined();
        renderUnrefined();
    }

    private void switchToRefined() {
        fxManage(refinedGridBorderPane);
        fxUnmanage(unrefinedScrollPane);
    }

    private void switchToUnrefined() {
        fxManage(unrefinedScrollPane);
        fxUnmanage(refinedGridBorderPane);
    }

    // ── Refined view ────────────────────────────────────────────────────────

    private void renderRefined() {
        List<Long> photoIds = currentPhotoRows.stream()
                                              .filter(r -> r.getState() == PhotoRefinementState.YES.getDbValue())
                                              .map(CustomAlbumPhotoRecord::getPhotoId)
                                              .toList();
        if (photoIds.isEmpty()) {
            gridController.populatePhotoGrid(List.of());
            return;
        }
        long myGeneration = gridController.initiateChange();
        runOnDaemonThread("LoadCustomAlbumRefinedPhotos", () -> {
            List<Media> photos = sortedByCaptureDate(mediaRepository.findMediaByIdIn(photoIds));
            runOnFxThread(() -> {
                if (myGeneration != gridController.currentChange()) {
                    return;
                }
                gridController.populatePhotoGrid(photoGridManager.createData(photos));
            });
        });
    }

    // ── Unrefined view (scene-group sections + state editing, Phase 6) ─────

    private void renderUnrefined() {
        unrefinedSectionsBox.getChildren()
                            .clear();
        unrefinedTileCells.clear();
        unrefinedTileWrappers.clear();
        selectedPhotoIds.clear();

        if (currentPhotoRows.isEmpty()) {
            return;
        }

        List<Long> allIds = currentPhotoRows.stream()
                                            .map(CustomAlbumPhotoRecord::getPhotoId)
                                            .toList();

        runOnDaemonThread("LoadCustomAlbumUnrefinedPhotos", () -> {
            Map<Long, GridCellData> dataById = photoGridManager.createData(mediaRepository.findMediaByIdIn(allIds))
                                                               .stream()
                                                               .collect(Collectors.toMap(GridCellData::mediaId, d -> d));
            runOnFxThread(() -> buildSections(dataById));
        });
    }

    private void buildSections(Map<Long, GridCellData> dataById) {
        // Group by scene_group_id; null (not yet clustered by SceneGroupingJob) gets its own
        // "Processing..." bucket rather than being hidden or crashing on a null key.
        Map<Long, List<CustomAlbumPhotoRecord>> bySceneGroup = new LinkedHashMap<>();
        List<CustomAlbumPhotoRecord>            pending      = new ArrayList<>();
        for (CustomAlbumPhotoRecord row : currentPhotoRows) {
            if (row.getSceneGroupId() == null) {
                pending.add(row);
            } else {
                bySceneGroup.computeIfAbsent(row.getSceneGroupId(), k -> new ArrayList<>())
                            .add(row);
            }
        }

        List<TitledPane> sections = new ArrayList<>();
        int              index    = 1;
        for (Map.Entry<Long, List<CustomAlbumPhotoRecord>> entry : bySceneGroup.entrySet()) {
            sections.add(buildSection("Scene Group " + index++, entry.getValue(), dataById));
        }
        if (!pending.isEmpty()) {
            sections.add(buildSection("Processing...", pending, dataById));
        }
        unrefinedSectionsBox.getChildren()
                            .setAll(sections);
    }

    private TitledPane buildSection(String title, List<CustomAlbumPhotoRecord> rows, Map<Long, GridCellData> dataById) {
        HBox bulkActionsRow = buildBulkActionsRow(rows);

        FlowPane tilesPane = new FlowPane(8, 8);
        for (CustomAlbumPhotoRecord row : rows) {
            GridCellData data = dataById.get(row.getPhotoId());
            if (data != null) {
                tilesPane.getChildren()
                         .add(buildTile(row, data));
            }
        }

        VBox       content = new VBox(8, bulkActionsRow, tilesPane);
        TitledPane pane    = new TitledPane(title + " (" + rows.size() + " photo" + (rows.size() == 1 ? "" : "s") + ")", content);
        pane.setExpanded(true);
        return pane;
    }

    private HBox buildBulkActionsRow(List<CustomAlbumPhotoRecord> rows) {
        Set<Long> groupPhotoIds = rows.stream()
                                      .map(CustomAlbumPhotoRecord::getPhotoId)
                                      .collect(Collectors.toSet());

        HBox  row      = new HBox(6);
        Label setLabel = new Label("Set group:");
        row.getChildren()
           .add(setLabel);
        for (PhotoRefinementState state : ALL_STATES) {
            Button button = new Button(state.getDisplayLabel());
            button.getStyleClass()
                  .add(CssClasses.STATE_BADGE_PREFIX + state.getCssSuffix());
            button.setOnAction(e -> runOnDaemonThread("BulkSetGroupState", () -> {
                customAlbumPhotoRepository.updateStateBulk(currentAlbumId, groupPhotoIds, state);
                refreshRowsAndRerender();
            }));
            row.getChildren()
               .add(button);
        }

        Button exceptSelectedButton = getButton(rows, groupPhotoIds);
        row.getChildren()
           .add(exceptSelectedButton);

        return row;
    }

    private Button getButton(List<CustomAlbumPhotoRecord> rows, Set<Long> groupPhotoIds) {
        Button exceptSelectedButton = new Button("Mark unselected as No");
        exceptSelectedButton.setOnAction(e -> {
            Set<Long> keep = new HashSet<>(selectedPhotoIds);
            keep.retainAll(groupPhotoIds);
            runOnDaemonThread("BulkMarkExceptSelected", () -> {
                // updateStateForGroupExcept is scoped by scene_group_id, so this is a deliberate
                // no-op for the "Processing..." (not-yet-clustered, scene_group_id NULL) section
                // — there's no group to scope the bulk action to until SceneGroupingJob assigns
                // one, which normally happens within moments of the photos being added.
                Long anySceneGroupId = rows.stream()
                                           .map(CustomAlbumPhotoRecord::getSceneGroupId)
                                           .filter(java.util.Objects::nonNull)
                                           .findFirst()
                                           .orElse(null);
                if (anySceneGroupId != null) {
                    customAlbumPhotoRepository.updateStateForGroupExcept(currentAlbumId, anySceneGroupId, keep, PhotoRefinementState.NO);
                    refreshRowsAndRerender();
                }
            });
        });
        return exceptSelectedButton;
    }

    private void refreshRowsAndRerender() {
        List<CustomAlbumPhotoRecord> rows = new ArrayList<>(customAlbumPhotoRepository.findByAlbumId(currentAlbumId));
        runOnFxThread(() -> {
            currentPhotoRows = rows;
            applyLockState();
            if (unrefinedScrollPane.isVisible()) {
                renderUnrefined();
            }
        });
    }

    private VBox buildTile(CustomAlbumPhotoRecord row, GridCellData data) {
        long photoId = row.getPhotoId();

        LoadedFxml<GridCellController> loaded         = fxmlLoader.load(FxmlView.PHOTO_CELL, gridCellResources);
        GridCellController             cellController = loaded.controller();
        cellController.bindThumbnailSize(new javafx.beans.property.SimpleDoubleProperty(TILE_SIZE));
        cellController.showPlaceholder(data);
        cellController.showImage(data);
        Parent cellNode = loaded.parent();
        cellNode.setStyle("-fx-pref-width: " + TILE_SIZE + "; -fx-pref-height: " + TILE_SIZE + ";");

        ToggleGroup          stateGroup   = new ToggleGroup();
        HBox                 stateButtons = new HBox(2);
        PhotoRefinementState currentState = PhotoRefinementState.fromDbValue(row.getState());
        for (PhotoRefinementState state : ALL_STATES) {
            ToggleButton tb = new ToggleButton(state.getShortLabel());
            Tooltip.install(tb, new Tooltip(state.getDisplayLabel()));
            tb.setToggleGroup(stateGroup);
            tb.setSelected(state == currentState);
            tb.setOnAction(e -> onTileStateChanged(photoId, state));
            stateButtons.getChildren()
                        .add(tb);
        }

        VBox tile = new VBox(4, cellNode, stateButtons);
        tile.getStyleClass()
            .addAll(CssClasses.PHOTO_TILE, CssClasses.STATE_BORDER_PREFIX + currentState.getCssSuffix());
        tile.setFocusTraversable(true);
        tile.setOnMouseClicked(e -> toggleTileSelection(photoId));
        tile.setOnKeyPressed(e -> {
            PhotoRefinementState mapped = stateForDigitKey(e.getCode());
            if (mapped != null) {
                onTileStateChanged(photoId, mapped);
            }
        });

        unrefinedTileCells.put(photoId, cellController);
        unrefinedTileWrappers.put(photoId, tile);
        return tile;
    }

    private PhotoRefinementState stateForDigitKey(KeyCode code) {
        return switch (code) {
            case DIGIT1 -> PhotoRefinementState.NO;
            case DIGIT2 -> PhotoRefinementState.RATHER_NO;
            case DIGIT3 -> PhotoRefinementState.UNASSIGNED;
            case DIGIT4 -> PhotoRefinementState.RATHER_YES;
            case DIGIT5 -> PhotoRefinementState.YES;
            default -> null;
        };
    }

    private void toggleTileSelection(long photoId) {
        VBox tile = unrefinedTileWrappers.get(photoId);
        if (tile == null) {
            return;
        }
        if (!selectedPhotoIds.remove(photoId)) {
            selectedPhotoIds.add(photoId);
        }
        boolean selected = selectedPhotoIds.contains(photoId);
        if (selected) {
            tile.getStyleClass()
                .add(CssClasses.PHOTO_TILE_SELECTED);
        } else {
            tile.getStyleClass()
                .remove(CssClasses.PHOTO_TILE_SELECTED);
        }
    }

    private void onTileStateChanged(long photoId, PhotoRefinementState newState) {
        runOnDaemonThread("SetPhotoRefinementState", () -> {
            customAlbumPhotoRepository.updateState(currentAlbumId, photoId, newState);
            runOnFxThread(() -> {
                currentPhotoRows.stream()
                                .filter(r -> r.getPhotoId() == photoId)
                                .findFirst()
                                .ifPresent(r -> r.setState((short) newState.getDbValue()));
                applyBorderClass(photoId, newState);
                applyLockState();
            });
        });
    }

    private void applyBorderClass(long photoId, PhotoRefinementState newState) {
        VBox tile = unrefinedTileWrappers.get(photoId);
        if (tile == null) {
            return;
        }
        tile.getStyleClass()
            .removeIf(c -> c.startsWith(CssClasses.STATE_BORDER_PREFIX) && !c.startsWith(CssClasses.STATE_BADGE_PREFIX));
        tile.getStyleClass()
            .add(CssClasses.STATE_BORDER_PREFIX + newState.getCssSuffix());
    }

    private static List<Media> sortedByCaptureDate(List<Media> photos) {
        return photos.stream()
                     .sorted(Comparator.comparing(Media::getCaptureDate, Comparator.nullsLast(Comparator.naturalOrder())))
                     .toList();
    }

    @Override
    public void onThumbnailReady(ThumbnailsReadyEvent event) {
        if (gridController != null) {
            gridController.onThumbnailReady(event);
        }
        if (unrefinedScrollPane.isVisible()) {
            boolean overlaps = event.getPhotoIds()
                                    .stream()
                                    .anyMatch(unrefinedTileCells::containsKey);
            if (overlaps) {
                renderUnrefined();
            }
        }
    }

    /**
     * Refreshes if this event is for whichever album is currently open — e.g. the scene-grouping
     * sweep just finished processing it. A no-op for every other album.
     */
    @EventListener
    public void onCustomAlbumUpdated(CustomAlbumUpdatedEvent event) {
        if (event.getCustomAlbumId() != currentAlbumId) {
            return;
        }
        refreshRowsAndRerenderAsync();
    }

    private void refreshRowsAndRerenderAsync() {
        long albumId = currentAlbumId;
        runOnDaemonThread("RefreshCustomAlbumAfterUpdate", () -> {
            List<CustomAlbumPhotoRecord> rows = new ArrayList<>(customAlbumPhotoRepository.findByAlbumId(albumId));
            runOnFxThread(() -> {
                if (albumId != currentAlbumId) {
                    return; // superseded by a different album being opened while this was loading
                }
                currentPhotoRows = rows;
                applyLockState();
                if (unrefinedScrollPane.isVisible()) {
                    renderUnrefined();
                }
            });
        });
    }
}
