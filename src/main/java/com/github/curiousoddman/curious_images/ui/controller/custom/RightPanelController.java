package com.github.curiousoddman.curious_images.ui.controller.custom;

import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaPhotoRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.MediaTagRecord;
import com.github.curiousoddman.curious_images.dbobj.tables.records.TagEmbeddingRecord;
import com.github.curiousoddman.curious_images.domain.common.MediaRotationService;
import com.github.curiousoddman.curious_images.domain.common.MetadataEditService;
import com.github.curiousoddman.curious_images.model.GridCellData;
import com.github.curiousoddman.curious_images.model.Media;
import com.github.curiousoddman.curious_images.model.PersonDetails;
import com.github.curiousoddman.curious_images.model.Rotate;
import com.github.curiousoddman.curious_images.ui.util.MetadataEditDialogs;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.collections.ObservableList;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Separator;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.kordamp.ikonli.javafx.FontIcon;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxManage;
import static com.github.curiousoddman.curious_images.ui.util.UiUtils.fxUnmanage;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.gps;
import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static java.util.Objects.requireNonNullElse;

@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class RightPanelController implements Initializable {
    private final MediaRotationService mediaRotationService;
    private final MetadataEditService  metadataEditService;

    @FXML
    public VBox      rootVbox;
    @FXML
    public Label     fileNameLabel;
    @FXML
    public Label     extensionLabel;
    @FXML
    public Label     pathLabel;
    @FXML
    public Label     fileSizeLabel;
    @FXML
    public Label     importedAtLabel;
    @FXML
    public Label     resolutionLabel;
    @FXML
    public Label     orientationLabel;
    @FXML
    public Label     captureDateLabel;
    @FXML
    public Label     captureDateSourceLabel;
    @FXML
    public Label     cameraMakeLabel;
    @FXML
    public Label     cameraModelLabel;
    @FXML
    public Label     lensModelLabel;
    @FXML
    public FontIcon  gpsIcon;
    @FXML
    public Label     gpsTitleLabel;
    @FXML
    public HBox      gpsBox;
    @FXML
    public Label     gpsLabel;
    @FXML
    public Button    openMapButton;
    @FXML
    public Separator tagsSeparator;
    @FXML
    public VBox      tagsSection;
    @FXML
    public FlowPane  tagsPane;
    @FXML
    public Separator personsSeparator;
    @FXML
    public VBox      personsSection;
    @FXML
    public FlowPane  personsPane;

    private Timeline showAnimation;
    private Timeline hideAnimation;

    private Media  currentMedia;
    private String lat;
    private String lon;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        showAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(rootVbox.prefWidthProperty(), 0)),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(rootVbox.prefWidthProperty(), 500))
        );

        hideAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(rootVbox.prefWidthProperty(), rootVbox.getWidth())),
                new KeyFrame(Duration.millis(200),
                        new KeyValue(rootVbox.prefWidthProperty(), 0))
        );
        hideAnimation.setOnFinished(e -> {
            log.debug("Unmanage....");
            fxUnmanage(rootVbox);
        });
    }

    @FXML
    public void onOpenMap(ActionEvent event) {
        log.debug("Open map requested {} : {}", lat, lon);
        try {
            Desktop.getDesktop()
                   .browse(
                           URI.create(
                                   "https://www.openstreetmap.org/?mlat="
                                           + lat +
                                           "&mlon="
                                           + lon +
                                           "#map=16/"
                                           + lat +
                                           "/"
                                           + lon));
        } catch (IOException e) {
            log.error(e.getMessage(), e);
        }
    }

    public void showDetails(GridCellData gridCellData) {
        log.debug("Show details requested {}", gridCellData.mediaId());
        rootVbox.setPrefWidth(0);
        fxManage(rootVbox);

        Media media = gridCellData.media();
        currentMedia = media;
        fileNameLabel.setText(media.getFilename());
        extensionLabel.setText(media.getExtension());
        pathLabel.setText(media.getAbsolutePath());
        fileSizeLabel.setText(size(media.getFileSize()));
        importedAtLabel.setText(media.getImportedAt()
                                     .toString());
        resolutionLabel.setText(media.getWidth() + " × " + media.getHeight() + " px");
        Integer rotationDegrees = media.getRotationDegrees();
        orientationLabel.setText("Rotate " + requireNonNullElse(rotationDegrees, 0) + "°");
        if (gridCellData.isPhoto()) {
            MediaPhotoRecord photo = gridCellData.photo();
            lensModelLabel.setText(photo.getLensModel());
        } else {
            lensModelLabel.setText(null);
        }
        captureDateLabel.setText(media.getCaptureDate()
                                      .toString());
        captureDateSourceLabel.setText(media.getCaptureDateSource());
        cameraMakeLabel.setText(media.getCameraMake());
        cameraModelLabel.setText(media.getCameraModel());
        Double  gpsLon = media.getGpsLon();
        Double  gpsLat = media.getGpsLat();
        boolean hasGps = gpsLat != null && gpsLon != null;
        fxManage(hasGps, gpsIcon, gpsTitleLabel, gpsBox, gpsLabel, openMapButton);
        gpsLabel.setText(gps(gpsLat, gpsLon));
        if (hasGps) {
            lat = gpsLat.toString();
            lon = gpsLon.toString();
        }
        Map<MediaTagRecord, TagEmbeddingRecord> tags = gridCellData.tags();
        fxManage(!tags.isEmpty(), tagsSeparator, tagsSection);
        ObservableList<Node> tagsChildren = tagsPane.getChildren();
        tagsChildren.clear();

        for (Map.Entry<MediaTagRecord, TagEmbeddingRecord> tag : tags.entrySet()) {
            Node chip = createChip(tag.getValue()
                                      .getTag(), "%.2f".formatted(tag.getKey()
                                                                     .getConfidence()));
            tagsChildren.add(chip);
        }

        List<PersonDetails> persons = gridCellData.persons();
        fxManage(!persons.isEmpty(), personsSeparator, personsSection);

        ObservableList<Node> personsChildren = personsPane.getChildren();
        personsChildren.clear();
        for (PersonDetails person : persons) {
            Node chip = createChip(requireNonNullElse(person.personName(), "unnamed"), "");
            personsChildren.add(chip);
        }

        showAnimation.playFromStart();
    }

    private static Node createChip(String name, String confidence) {
        log.debug("Create chip {} : {}", name, confidence);
        Label nameLabel = new Label(name);
        nameLabel.getStyleClass()
                 .add("tag-name");

        Label score = new Label(confidence);
        score.getStyleClass()
             .add("tag-score");

        HBox chip = new HBox(4, nameLabel, score);
        chip.getStyleClass()
            .add("tag-chip");
        return chip;
    }

    @FXML
    public void onHideDetails() {
        log.debug("Hide details requested");
        currentMedia = null;
        hideAnimation.playFromStart();
    }

    @FXML
    public void onEditRotation(ActionEvent event) {
        if (currentMedia == null) {
            return;
        }
        long mediaId = currentMedia.getId();
        int  current = requireNonNullElse(currentMedia.getRotationDegrees(), 0);
        MetadataEditDialogs.askRotationDegrees(rootVbox.getScene()
                                                       .getWindow(), current)
                           .ifPresent(degrees -> {
                               Rotate rotate = Rotate.of(degrees);
                               runOnDaemonThread("SetRotation", () -> mediaRotationService.rotateAbsolute(mediaId, rotate));
                               orientationLabel.setText("Rotate " + degrees + "°");
                           });
    }

    @FXML
    public void onEditCaptureDate(ActionEvent event) {
        if (currentMedia == null) {
            return;
        }
        long mediaId = currentMedia.getId();
        MetadataEditDialogs.askCaptureDate(rootVbox.getScene()
                                                   .getWindow(), currentMedia.getCaptureDate())
                           .ifPresent(dateTime -> {
                               runOnDaemonThread("SetCaptureDate", () -> metadataEditService.setCaptureDate(mediaId, dateTime));
                               captureDateLabel.setText(dateTime.toString());
                               captureDateSourceLabel.setText("MANUAL_EDIT");
                           });
    }
}
