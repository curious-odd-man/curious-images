package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.PersonDetails;
import com.github.curiousoddman.curious_images.model.bundle.GridCellResources;
import com.github.curiousoddman.curious_images.ui.styles.CssClasses;
import com.github.curiousoddman.curious_images.ui.util.GridContextMenu;
import javafx.beans.value.ObservableValue;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.io.File;
import java.net.URL;
import java.util.Comparator;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxManage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxToggleClass;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxUnmanage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.registerHoverTooltip;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.registerZoomInOnHover;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.gps;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.rate;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;

@Slf4j
@Component
@Scope("prototype")
@RequiredArgsConstructor
public class GridCellController implements Initializable {
    private final Tooltip iconsTooltip = new Tooltip();

    private final GridContextMenu gridContextMenu;

    @FXML
    public  BorderPane cellRoot;
    @FXML
    public  Label      typeImageIcon;
    @FXML
    public  Label      tagIcon;
    @FXML
    public  Label      gpsIcon;
    @FXML
    public  Label      faceCountLabel;
    @FXML
    public  Label      faceIcon;
    @FXML
    public  FontIcon   duplicateIcon;
    @FXML
    public  FontIcon   showInfoIcon;
    @FXML
    public  HBox       iconsHbox;
    @FXML
    private FontIcon   videoPlayOverlay;
    @FXML
    private FontIcon   selectionCheckIcon;
    @FXML
    private MediaView  hoverMediaView;
    @FXML
    private StackPane  imageSlot;
    @FXML
    private Rectangle  placeholderRect;
    @FXML
    private Label      placeholderLabel;
    @FXML
    private ImageView  imageView;

    @Setter
    private Consumer<Media> onPhotoClicked;

    @Setter
    private BiConsumer<GridCellData, Boolean> onSelectionClick;

    @Setter
    private Supplier<Set<Long>> selectedMediaIdsSupplier;

    @Getter
    private GridCellData gridCellData;

    private Consumer<GridCellData> imageDetailsConsumer;

    private Tooltip cellTooltip;

    /**
     * The currently-playing hover-preview player, or {@code null} when nothing is playing. At
     * most one exists per cell, and at most one cell is ever hovered at a time — this is what
     * caps concurrent {@link MediaPlayer} instances to the currently-hovered cell (implementation
     * plan §4): {@code MediaPlayer} is a real native resource, not free.
     */
    private MediaPlayer hoverPlayer;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        log.debug("Initialize");
        registerZoomInOnHover(imageView, showInfoIcon);
        cellTooltip = new Tooltip("");
        cellTooltip.getStyleClass()
                   .add("monospace-text");
        Tooltip.install(imageView, cellTooltip);
        cellTooltip.setShowDelay(Duration.millis(500));
        imageView.setPreserveRatio(true);
        hoverMediaView.setPreserveRatio(true);

        cellRoot.setOnContextMenuRequested(e -> {
            Set<Long> selection = selectedMediaIdsSupplier != null ? selectedMediaIdsSupplier.get() : Set.of();
            if (selection.size() > 1 && gridCellData != null && selection.contains(gridCellData.mediaId())) {
                gridContextMenu.showBulk(selection, cellRoot, e);
            } else {
                gridContextMenu.show(gridCellData.media(), cellRoot, e);
            }
        });
        if (resources instanceof GridCellResources cellResources) {
            imageDetailsConsumer = cellResources.getImageDetailsConsumer();
        }
    }

    public void bindThumbnailSize(ObservableValue<? extends Number> size) {
        log.debug("binding dimensions");
        cellRoot.prefWidthProperty()
                .bind(size);
        imageSlot.prefWidthProperty()
                 .bind(size);
        imageSlot.prefHeightProperty()
                 .bind(size);
        placeholderRect.widthProperty()
                       .bind(size);
        placeholderRect.heightProperty()
                       .bind(size);
        imageView.fitWidthProperty()
                 .bind(size);
        imageView.fitHeightProperty()
                 .bind(size);
        hoverMediaView.fitWidthProperty()
                      .bind(size);
        hoverMediaView.fitHeightProperty()
                      .bind(size);
    }

    public void showPlaceholder(GridCellData data) {
        log.debug("Placeholder.... {}", data.mediaId());
        stopHoverPreview();
        this.gridCellData = data;
        fxManage(cellRoot, placeholderRect, placeholderLabel);
        fxUnmanage(imageView, iconsHbox, videoPlayOverlay);
        cellTooltip.setText(data.tooltipText());
        imageView.setImage(null);
        setSelected(false);
    }

    public void showEmpty() {
        log.debug("Disappear {}", gridCellData == null ? null : gridCellData.mediaId());
        stopHoverPreview();
        this.gridCellData = null;
        fxUnmanage(cellRoot, iconsHbox, videoPlayOverlay);
        imageView.setImage(null);
        setSelected(false);
    }

    public void showImage(GridCellData data) {
        log.debug("Showing all data... {}", gridCellData.mediaId());
        Media media = data.media();
        if (gridCellData.media() != media) {
            log.debug("oops, media changed..");
            return;
        }

        if (data.image() == null) {
            fxManage(placeholderRect, placeholderLabel);
            fxUnmanage(imageView);
        } else {
            fxManage(imageView);
            fxUnmanage(placeholderRect, placeholderLabel);
            imageView.setImage(data.image());
        }

        fxManage(imageView, iconsHbox);
        // Grid cell gets a small play-icon overlay to distinguish video tiles (implementation
        // plan §3); hover-preview playback (§4) temporarily swaps this out for hoverMediaView —
        // see onCellHoverStart/stopHoverPreview.
        fxManage(data.isVideo(), videoPlayOverlay);

        fxManage(!data.tags()
                      .isEmpty(), tagIcon);
        boolean hasGps = media.getGpsAltitude() != null || media.getGpsLat() != null || media.getGpsLon() != null;
        fxManage(hasGps, gpsIcon);
        if (data.persons()
                .size() > 1) {
            faceCountLabel.setText(String.valueOf(data.persons()
                                                      .size()));
            fxManage(faceCountLabel);
        } else {
            fxUnmanage(faceCountLabel);
        }
        fxManage(!data.persons()
                      .isEmpty(), faceIcon);
        fxManage(data.hasDuplicates(), duplicateIcon);

        registerHoverTooltip(iconsTooltip, "Has duplicates", duplicateIcon);
        registerHoverTooltip(iconsTooltip, media.getExtension() + ": " + size(media.getFileSize()), typeImageIcon);
        registerHoverTooltip(iconsTooltip, data.tags()
                                               .entrySet()
                                               .stream()
                                               .map(v -> new TagData(v.getValue()
                                                                      .getTag(), v.getKey()
                                                                                  .getConfidence()))
                                               .sorted(Comparator.comparing(TagData::score))
                                               .map(e -> e.name() + " (" + rate(e.score()) + ")")
                                               .collect(Collectors.joining("\n")), tagIcon);
        registerHoverTooltip(iconsTooltip, gps(media.getGpsLat(), media.getGpsLon()), gpsIcon);
        registerHoverTooltip(iconsTooltip, data.persons()
                                               .stream()
                                               .map(PersonDetails::personName)
                                               .collect(Collectors.joining("\n")), faceCountLabel, faceIcon);
    }

    public void setSelected(boolean selected) {
        fxManage(selected, selectionCheckIcon);
        fxToggleClass(cellRoot, CssClasses.GRID_CELL_SELECTED, selected);
    }

    private record TagData(String name, double score) {

    }

    @FXML
    private void onCellClicked(MouseEvent e) {
        if (gridCellData == null) {
            return;
        }
        boolean selectionModifier = e.isControlDown() || e.isMetaDown() || e.isShiftDown();
        if (selectionModifier) {
            if (onSelectionClick != null) {
                onSelectionClick.accept(gridCellData, e.isShiftDown());
            }
            return;
        }
        if (e.getClickCount() == 1 && e.getButton() == MouseButton.PRIMARY && onPhotoClicked != null) {
            onPhotoClicked.accept(gridCellData.media());
        }
    }

    /**
     * Hover-preview playback (implementation plan §4): plays the actual video file muted and
     * looped, directly via JavaFX {@code MediaPlayer} — no proxy-transcode step, since accepted
     * formats are restricted at import time to what JavaFX can already decode.
     */
    @FXML
    private void onCellHoverStart(MouseEvent e) {
        if (gridCellData != null && gridCellData.isVideo() && gridCellData.media()
                                                                          .getAbsolutePath() != null) {
            startHoverPreview();
        }
    }

    @FXML
    private void onCellHoverEnd(MouseEvent e) {
        stopHoverPreview();
    }

    private void startHoverPreview() {
        if (hoverPlayer != null) {
            return; // already playing (e.g. duplicate enter event)
        }
        String absolutePath = gridCellData.media()
                                          .getAbsolutePath();
        try {
            javafx.scene.media.Media media = new javafx.scene.media.Media(new File(absolutePath).toURI()
                                                                                                .toString());
            MediaPlayer player = new MediaPlayer(media);
            player.setMute(true);
            player.setCycleCount(MediaPlayer.INDEFINITE);
            player.setOnError(() -> {
                log.warn("Hover-preview playback failed for {}: {}", absolutePath, player.getError()
                                                                                         .getMessage());
                stopHoverPreview();
            });
            hoverMediaView.setMediaPlayer(player);
            hoverPlayer = player;

            fxManage(hoverMediaView);
            fxUnmanage(imageView, videoPlayOverlay);
            player.play();
        } catch (Exception ex) {
            log.warn("Could not start hover preview for {}", absolutePath, ex);
        }
    }

    private void stopHoverPreview() {
        if (hoverPlayer == null) {
            return;
        }
        MediaPlayer player = hoverPlayer;
        hoverPlayer = null;
        hoverMediaView.setMediaPlayer(null);
        try {
            player.stop();
            player.dispose();
        } catch (Exception ex) {
            log.debug("Error disposing hover-preview player", ex);
        }

        fxUnmanage(hoverMediaView);
        if (gridCellData != null) {
            fxManage(imageView);
            fxManage(gridCellData.isVideo(), videoPlayOverlay);
        }
    }

    @FXML
    public void onShowInfo(ActionEvent event) {
        imageDetailsConsumer.accept(gridCellData);
    }
}
