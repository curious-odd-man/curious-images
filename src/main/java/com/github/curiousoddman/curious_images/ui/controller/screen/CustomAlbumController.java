package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.CustomAlbumRecord;
import com.github.curiousoddman.curious_images.event.model.CustomAlbumUpdatedEvent;
import com.github.curiousoddman.curious_images.event.model.ThumbnailsReadyEvent;
import com.github.curiousoddman.curious_images.model.LoadedFxml;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.PhotoRefinementState;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumPhotoRepository;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumRepository;
import com.github.curiousoddman.curious_images.persistence.MediaRepository;
import com.github.curiousoddman.curious_images.ui.FxmlLoader;
import com.github.curiousoddman.curious_images.ui.FxmlView;
import com.github.curiousoddman.curious_images.ui.controller.custom.GridController;
import com.github.curiousoddman.curious_images.ui.controller.services.PhotoGridManager;
import com.github.curiousoddman.curious_images.ui.controller.services.ThumbnailReadyEventListener;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Label;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.BorderPane;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.ResourceBundle;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Controller for {@code custom_album.fxml} — the Refined/Unrefined shell for one custom album
 * (album-refinement-feature-spec.md §7). Injected into {@code LibraryController}'s
 * {@code contentStack} by {@code LibraryViewManager.showCustomAlbum}, the same way
 * {@link PersonDetailController} is.
 * <p>
 * Deliberately uses a single embedded {@link GridController} whose contents are swapped when the
 * toggle changes, rather than separate grids per view (see the FXML's header comment for why —
 * {@link PhotoGridManager} is a shared singleton, and {@code PersonDetailController} already
 * flags that as a one-manager-two-controllers problem; this doesn't add a second live instance
 * competing with it beyond the one PersonDetailController already introduces).
 * <p>
 * Scene-group sectioning and per-tile state-color highlighting (spec §7's "grouped by scene" /
 * "visually highlighted according to state") are NOT implemented in this shell — see the
 * implementation plan's Phase 6, where they land together as bespoke tiles rather than more
 * embedded grids.
 */
@Slf4j
@Component
@Lazy
@RequiredArgsConstructor
public class CustomAlbumController implements Initializable, ThumbnailReadyEventListener {

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
    public BorderPane   gridBorderPane;

    private GridController gridController;

    private long    currentAlbumId;
    private boolean refinedLocked = true;

    /**
     * All membership rows for the current album, refreshed by {@link #loadAlbum}. Kept around so
     * toggling Refined/Unrefined doesn't need a fresh DB round-trip each time.
     */
    private List<CustomAlbumPhotoRecord> currentPhotoRows = List.of();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        LoadedFxml<GridController> loaded = fxmlLoader.load(FxmlView.PHOTO_GRID, resources);
        gridController = loaded.controller();
        gridBorderPane.setCenter(loaded.parent());
    }

    /**
     * Loads everything for the given album and shows Unrefined by default. Safe to call from any
     * thread. {@code LibraryViewManager} calls this fresh each time the tree selection lands on a
     * {@code CustomAlbumPayload} — including re-selecting the album already showing, which is
     * why this always resets to Unrefined rather than trying to preserve the prior toggle state
     * across albums.
     */
    public void loadAlbum(long albumId) {
        currentAlbumId = albumId;
        long myGeneration = gridController.initiateChange();

        runOnDaemonThread("LoadCustomAlbum", () -> {
            Optional<CustomAlbumRecord>  albumOpt = customAlbumRepository.findById(albumId);
            List<CustomAlbumPhotoRecord> rows     = customAlbumPhotoRepository.findByAlbumId(albumId);

            runOnFxThread(() -> {
                if (myGeneration != gridController.currentChange() || albumId != currentAlbumId) {
                    return; // a newer loadAlbum() call has since superseded this one
                }
                albumOpt.ifPresent(a -> albumNameLabel.setText(a.getName()));
                currentPhotoRows = rows;
                applyLockState();
                showUnrefined();
                unrefinedToggle.setSelected(true);
            });
        });
    }

    /**
     * Re-checks the refined-view lock (spec §7) against the rows already loaded — cheap, no DB
     * call — and re-locks/unlocks live. Called after {@link #loadAlbum} and whenever a
     * {@link CustomAlbumUpdatedEvent} for this album arrives (e.g. scene grouping just finished,
     * or — once Phase 6 lands — a state edit changed the Yes count).
     */
    private void applyLockState() {
        boolean anyYes = currentPhotoRows.stream()
                                         .anyMatch(r -> r.getState() == PhotoRefinementState.YES.dbValue());
        refinedLocked = !anyYes;
        refinedToggle.setDisable(refinedLocked);
        lockedHintLabel.setVisible(refinedLocked);
        lockedHintLabel.setManaged(refinedLocked);
        if (refinedLocked && refinedToggle.isSelected()) {
            // The last remaining Yes photo just got changed away while Refined was showing —
            // re-lock immediately per spec §7, rather than leaving a stale Refined view up.
            unrefinedToggle.setSelected(true);
            showUnrefined();
        }
    }

    @FXML
    public void onShowRefined() {
        if (refinedLocked) {
            unrefinedToggle.setSelected(true);
            return;
        }
        showRefined();
    }

    @FXML
    public void onShowUnrefined() {
        showUnrefined();
    }

    private void showRefined() {
        List<Long> photoIds = currentPhotoRows.stream()
                                              .filter(r -> r.getState() == PhotoRefinementState.YES.dbValue())
                                              .map(CustomAlbumPhotoRecord::getPhotoId)
                                              .toList();
        populateGrid(photoIds);
    }

    private void showUnrefined() {
        List<Long> photoIds = currentPhotoRows.stream()
                                              .map(CustomAlbumPhotoRecord::getPhotoId)
                                              .toList();
        populateGrid(photoIds);
    }

    private void populateGrid(List<Long> photoIds) {
        if (photoIds.isEmpty()) {
            gridController.populatePhotoGrid(List.of());
            return;
        }
        long myGeneration = gridController.initiateChange();
        runOnDaemonThread("LoadCustomAlbumPhotos", () -> {
            List<Media> photos = mediaRepository.findMediaByIdIn(photoIds)
                                                .stream()
                                                .sorted(Comparator.comparing(Media::getCaptureDate,
                                                        Comparator.nullsLast(Comparator.naturalOrder())))
                                                .toList();
            runOnFxThread(() -> {
                if (myGeneration != gridController.currentChange()) {
                    return;
                }
                gridController.populatePhotoGrid(photoGridManager.createData(photos));
            });
        });
    }

    @Override
    public void onThumbnailReady(ThumbnailsReadyEvent event) {
        if (gridController != null) {
            gridController.onThumbnailReady(event);
        }
    }

    /**
     * Refreshes if this event is for whichever album is currently open — e.g. the scene-grouping
     * sweep just finished processing it, or (once Phase 6 lands) a state edit changed its Yes
     * count. A no-op for every other album, so this doesn't do work while a different album (or
     * no custom album at all) is showing.
     */
    @EventListener
    public void onCustomAlbumUpdated(CustomAlbumUpdatedEvent event) {
        if (event.getCustomAlbumId() != currentAlbumId) {
            return;
        }
        runOnDaemonThread("RefreshCustomAlbumAfterUpdate", () -> {
            List<CustomAlbumPhotoRecord> rows = customAlbumPhotoRepository.findByAlbumId(currentAlbumId);
            runOnFxThread(() -> {
                if (event.getCustomAlbumId() != currentAlbumId) {
                    return; // superseded by a different album being opened while this was loading
                }
                currentPhotoRows = rows;
                applyLockState();
            });
        });
    }
}
