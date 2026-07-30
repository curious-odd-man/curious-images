package com.github.curiousoddman.curious_images.ui.util;

import com.github.curiousoddman.curious_images.domain.common.MediaRotationService;
import com.github.curiousoddman.curious_images.domain.common.MetadataEditService;
import com.github.curiousoddman.curious_images.domain.customalbum.CustomAlbumPhotoAdditionService;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.Rotate;
import com.github.curiousoddman.curious_images.persistence.CustomAlbumRepository;
import com.github.curiousoddman.curious_images.util.ExplorerUtils;
import javafx.scene.Parent;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.input.ContextMenuEvent;
import lombok.RequiredArgsConstructor;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_CLOCKWISE;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_COUNTERCLOCKWISE;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_REPEAT;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.FOLDER_SYMLINK;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.IMAGES;

@Component
@RequiredArgsConstructor
public class GridContextMenu {

    private final MediaRotationService            photoRotationService;
    private final MetadataEditService             metadataEditService;
    private final CustomAlbumRepository           customAlbumRepository;
    private final CustomAlbumPhotoAdditionService customAlbumPhotoAdditionService;

    public ContextMenu show(Media media, Parent parent, ContextMenuEvent e) {
        if (media == null) {
            return null;
        }
        ContextMenu contextMenu = new ContextMenu();

        FontIcon graphicArrowClockwise = new FontIcon(ARROW_CLOCKWISE);
        MenuItem rotateCw              = new MenuItem("Rotate 90°", graphicArrowClockwise);
        rotateCw.setOnAction(ev -> rotateCurrentPhoto(media, Rotate.ROTATE_CW));

        FontIcon graphicArrowCounterClockwise = new FontIcon(ARROW_COUNTERCLOCKWISE);
        MenuItem rotateCcw                    = new MenuItem("Rotate 90°", graphicArrowCounterClockwise);
        rotateCcw.setOnAction(ev -> rotateCurrentPhoto(media, Rotate.ROTATE_CCW));

        MenuItem rotate180 = new MenuItem("Rotate 180°", new FontIcon(ARROW_REPEAT));
        rotate180.setOnAction(ev -> rotateCurrentPhoto(media, Rotate.ROTATE_180));

        MenuItem setRotation = new MenuItem("Set rotation...", new FontIcon(ARROW_REPEAT));
        setRotation.setOnAction(ev -> MetadataEditDialogs
                .askRotationDegrees(parent.getScene()
                                          .getWindow(), media.getRotationDegrees() != null ? media.getRotationDegrees() : 0)
                .ifPresent(degrees -> runOnDaemonThread("SetRotation",
                        () -> photoRotationService.rotateAbsolute(media.getId(), Rotate.of(degrees)))));

        MenuItem setCaptureDate = new MenuItem("Set capture date...", new FontIcon("bi-calendar-event"));
        setCaptureDate.setOnAction(ev -> MetadataEditDialogs
                .askCaptureDate(parent.getScene()
                                      .getWindow(), media.getCaptureDate())
                .ifPresent(dateTime -> runOnDaemonThread("SetCaptureDate",
                        () -> metadataEditService.setCaptureDate(media.getId(), dateTime))));

        MenuItem reveal = new MenuItem("Reveal in Explorer", new FontIcon(FOLDER_SYMLINK));
        reveal.setOnAction(ev -> ExplorerUtils.revealInExplorer(media.getAbsolutePath()));

        MenuItem addToAlbum = new MenuItem("Add to Album...", new FontIcon(IMAGES));
        // Custom albums only ever hold photos (album-refinement-feature-spec.md) — videos never
        // get this option rather than silently no-op-ing if clicked.
        addToAlbum.setDisable(media.isVideo());
        addToAlbum.setOnAction(ev -> promptAndAddToAlbum(parent, Set.of(media.getId())));

        contextMenu.getItems()
                   .addAll(rotateCw, rotateCcw, rotate180, setRotation, new SeparatorMenuItem(),
                           setCaptureDate, new SeparatorMenuItem(), reveal, addToAlbum);
        contextMenu.show(parent, e.getScreenX(), e.getScreenY());
        return contextMenu;
    }

    /**
     * Shown instead of {@link #show} when the right-clicked cell is part of a larger multi-selection
     * (Ctrl/Shift+click in the grid — see {@code GridController#handleSelectionClick}). Relative
     * rotation applies the same delta to every selected media (each normalized from its own current
     * value); absolute rotation and capture date apply the same target value to all of them.
     */
    public ContextMenu showBulk(Set<Long> mediaIds, Parent parent, ContextMenuEvent e) {
        if (mediaIds.isEmpty()) {
            return null;
        }
        ContextMenu contextMenu = new ContextMenu();
        int         count       = mediaIds.size();

        MenuItem rotateCw = new MenuItem("Rotate 90° (" + count + " selected)", new FontIcon(ARROW_CLOCKWISE));
        rotateCw.setOnAction(ev -> runOnDaemonThread("BulkRotate",
                () -> photoRotationService.rotateRelativeBulk(mediaIds, Rotate.ROTATE_CW)));

        MenuItem rotateCcw = new MenuItem("Rotate 90° CCW (" + count + " selected)", new FontIcon(ARROW_COUNTERCLOCKWISE));
        rotateCcw.setOnAction(ev -> runOnDaemonThread("BulkRotate",
                () -> photoRotationService.rotateRelativeBulk(mediaIds, Rotate.ROTATE_CCW)));

        MenuItem rotate180 = new MenuItem("Rotate 180° (" + count + " selected)", new FontIcon(ARROW_REPEAT));
        rotate180.setOnAction(ev -> runOnDaemonThread("BulkRotate",
                () -> photoRotationService.rotateRelativeBulk(mediaIds, Rotate.ROTATE_180)));

        MenuItem setRotation = new MenuItem("Set rotation... (" + count + " selected)", new FontIcon(ARROW_REPEAT));
        setRotation.setOnAction(ev -> MetadataEditDialogs
                .askRotationDegrees(parent.getScene()
                                          .getWindow(), 0)
                .ifPresent(degrees -> runOnDaemonThread("BulkSetRotation",
                        () -> photoRotationService.rotateAbsoluteBulk(mediaIds, Rotate.of(degrees)))));

        MenuItem setCaptureDate = new MenuItem("Set capture date... (" + count + " selected)", new FontIcon("bi-calendar-event"));
        setCaptureDate.setOnAction(ev -> MetadataEditDialogs
                .askCaptureDate(parent.getScene()
                                      .getWindow(), LocalDateTime.now())
                .ifPresent(dateTime -> runOnDaemonThread("BulkSetCaptureDate",
                        () -> metadataEditService.setCaptureDateBulk(mediaIds, dateTime))));

        contextMenu.getItems()
                   .addAll(rotateCw, rotateCcw, rotate180, setRotation, new SeparatorMenuItem(), setCaptureDate);

        // Which ids are actually photos isn't known without a DB read, and doing that read here
        // would block the FX thread while the menu opens. So this item is always enabled for a
        // non-empty selection; CustomAlbumPhotoAdditionService does the photo/video filtering
        // asynchronously and reports back via CustomAlbumVideosSkipped if anything was dropped.
        MenuItem addToAlbum = new MenuItem("Add to Album... (" + count + " selected)", new FontIcon(IMAGES));
        addToAlbum.setOnAction(ev -> promptAndAddToAlbum(parent, mediaIds));
        contextMenu.getItems()
                   .add(addToAlbum);

        contextMenu.show(parent, e.getScreenX(), e.getScreenY());
        return contextMenu;
    }

    private void rotateCurrentPhoto(Media media, Rotate deltaDegrees) {
        runOnDaemonThread("RotatePhoto", () -> photoRotationService.rotateAndClearAiResults(media.getId(), deltaDegrees));
    }

    /**
     * Shared by {@link #show} and {@link #showBulk}: loads the existing custom albums (off the FX
     * thread — this is a DB read triggered by a menu click, not menu construction), shows
     * {@link AlbumPickerDialog} on the FX thread, then performs the actual add (existing album) or
     * create-and-add (new album) back off the FX thread.
     */
    private void promptAndAddToAlbum(Parent parent, Set<Long> mediaIds) {
        runOnDaemonThread("LoadCustomAlbumsForPicker", () -> {
            List<AlbumPickerDialog.AlbumChoice> choices = customAlbumRepository.findAll()
                                                                               .stream()
                                                                               .map(a -> new AlbumPickerDialog.AlbumChoice(a.getId(), a.getName()))
                                                                               .toList();
            runOnFxThread(() -> AlbumPickerDialog.ask(parent.getScene()
                                                            .getWindow(), choices)
                                                 .ifPresent(result -> runOnDaemonThread("AddToAlbum", () -> {
                                                     switch (result) {
                                                         case AlbumPickerDialog.Result.Existing(long albumId) ->
                                                                 customAlbumPhotoAdditionService.addPhotos(albumId, mediaIds);
                                                         case AlbumPickerDialog.Result.New(String name) ->
                                                                 customAlbumPhotoAdditionService.createAlbumAndAddPhotos(name, mediaIds);
                                                     }
                                                 })));
        });
    }
}

