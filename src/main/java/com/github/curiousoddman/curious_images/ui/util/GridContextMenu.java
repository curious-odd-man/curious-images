package com.github.curiousoddman.curious_images.ui.util;

import com.github.curiousoddman.curious_images.domain.common.MediaRotationService;
import com.github.curiousoddman.curious_images.domain.common.MetadataEditService;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.Rotate;
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
import java.util.Set;

import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_CLOCKWISE;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_COUNTERCLOCKWISE;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.ARROW_REPEAT;
import static org.kordamp.ikonli.bootstrapicons.BootstrapIcons.FOLDER_SYMLINK;

@Component
@RequiredArgsConstructor
public class GridContextMenu {

    private final MediaRotationService photoRotationService;
    private final MetadataEditService  metadataEditService;

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

        contextMenu.getItems()
                   .addAll(rotateCw, rotateCcw, rotate180, setRotation, new SeparatorMenuItem(),
                           setCaptureDate, new SeparatorMenuItem(), reveal);
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
        int count = mediaIds.size();

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
        contextMenu.show(parent, e.getScreenX(), e.getScreenY());
        return contextMenu;
    }

    private void rotateCurrentPhoto(Media media, Rotate deltaDegrees) {
        runOnDaemonThread("RotatePhoto", () -> photoRotationService.rotateAndClearAiResults(media.getId(), deltaDegrees));
    }
}

