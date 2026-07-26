package com.github.curiousoddman.curious_images.ui.controller.screen;

import com.github.curiousoddman.curious_images.domain.imports.data.ImportFileIssue;
import com.github.curiousoddman.curious_images.event.model.ImportStatsUpdatedEvent;
import com.github.curiousoddman.curious_images.model.ImportJobStats;
import com.github.curiousoddman.curious_images.persistence.ImportJobStatsRepository;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.Accordion;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TitledPane;
import javafx.scene.layout.VBox;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.ResourceBundle;

import static com.github.curiousoddman.curious_images.util.HumanReadableUtils.size;
import static com.github.curiousoddman.curious_images.util.async.ThreadUtils.runOnDaemonThread;
import static com.sun.javafx.util.Utils.runOnFxThread;

/**
 * Shows the "Last Import" stats — the most recent {@code IMPORT_JOB_STATS} row (every run gets
 * its own id — real history — see {@code ImportJobStatsRepository}), kept live while a job is
 * running (see {@code ImportJob}/{@code ImportStatsTracker} and {@link ImportStatsUpdatedEvent}).
 * Selected from the tree via the always-present IMPORT_STATS node sitting alongside the
 * IMPORT_ROOT entries under Folders (see {@code TreeManager}).
 * <p>
 * Skipped (unsupported codec/extension only — not routine "unchanged" skips) and failed files are
 * shown as two separate sections, each grouped by reason — see {@link #renderIssueSection}.
 */
@Lazy
@Slf4j
@Component
@RequiredArgsConstructor
public class ImportStatsController implements Initializable {
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ImportJobStatsRepository importJobStatsRepository;

    @FXML
    public VBox emptyState;
    @FXML
    public VBox statsContent;

    @FXML
    public Label jobTypeLabel;
    @FXML
    public Label rootPathsLabel;
    @FXML
    public Label statusLabel;
    @FXML
    public Label startedAtLabel;
    @FXML
    public Label durationLabel;

    @FXML
    public Label photoImportedLabel;
    @FXML
    public Label photoUpdatedLabel;
    @FXML
    public Label videoImportedLabel;
    @FXML
    public Label videoUpdatedLabel;
    @FXML
    public Label sizeImportedLabel;

    @FXML
    public Label skippedUnchangedLabel;
    @FXML
    public Label unsupportedCodecLabel;
    @FXML
    public Label unsupportedExtensionLabel;

    @FXML
    public Label     skippedIssueCountLabel;
    @FXML
    public Accordion skippedAccordion;

    @FXML
    public Label     failedCountLabel;
    @FXML
    public Accordion failedAccordion;

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // Populated on-demand by refresh()/the live-update listener below; nothing to do at
        // FXML-load time since the pane may not even be visible yet (see LibraryViewManager).
    }

    /**
     * Called by {@code LibraryViewManager#showImportStats()} every time the tree node is
     * (re-)selected — loads the latest persisted snapshot, since it may have changed since this
     * pane was last shown.
     */
    public void refresh() {
        runOnDaemonThread("LoadImportStats", () -> {
            ImportJobStats stats = importJobStatsRepository.findLast()
                                                           .orElse(null);
            runOnFxThread(() -> render(stats));
        });
    }

    /**
     * Live updates while a job is running — cheap enough (a handful of labels + rebuilding the two
     * accordions) to just always apply, whether or not this view happens to be the one currently
     * showing; next time it's shown it'll already be current.
     */
    @EventListener
    public void onImportStatsUpdated(ImportStatsUpdatedEvent event) {
        runOnFxThread(() -> render(event.getStats()));
    }

    private void render(ImportJobStats stats) {
        boolean hasStats = stats != null;
        emptyState.setVisible(!hasStats);
        emptyState.setManaged(!hasStats);
        statsContent.setVisible(hasStats);
        statsContent.setManaged(hasStats);
        if (!hasStats) {
            return;
        }

        jobTypeLabel.setText(switch (stats.jobType()) {
            case NEW_ROOT -> "Add new root";
            case COPY -> "Copy into library";
            case RESCAN -> "Rescan";
        });
        rootPathsLabel.setText(String.join("\n", stats.rootPaths()));
        statusLabel.setText(switch (stats.status()) {
            case RUNNING -> "Running…";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case INTERRUPTED -> "Interrupted";
            case INTERRUPT_REQUESTED -> "Stopping…";
            case NEVER_RUN -> "Never run";
        });
        startedAtLabel.setText(stats.startedAt()
                                    .format(TIMESTAMP_FORMAT));
        LocalDateTime end = stats.finishedAt() != null ? stats.finishedAt() : LocalDateTime.now();
        durationLabel.setText(formatDuration(Duration.between(stats.startedAt(), end)));

        photoImportedLabel.setText(String.valueOf(stats.photoImportedCount()));
        photoUpdatedLabel.setText(String.valueOf(stats.photoUpdatedCount()));
        videoImportedLabel.setText(String.valueOf(stats.videoImportedCount()));
        videoUpdatedLabel.setText(String.valueOf(stats.videoUpdatedCount()));
        sizeImportedLabel.setText(size(stats.bytesImported()));

        skippedUnchangedLabel.setText(String.valueOf(stats.skippedUnchangedCount()));
        unsupportedCodecLabel.setText(String.valueOf(stats.unsupportedCodecCount()));
        unsupportedExtensionLabel.setText(String.valueOf(stats.unsupportedExtensionCount()));

        skippedIssueCountLabel.setText(String.valueOf(stats.skippedIssueCount()));
        failedCountLabel.setText(String.valueOf(stats.failedCount()));

        renderIssueSection(stats.skippedIssues(), skippedAccordion);
        renderIssueSection(stats.failedIssues(), failedAccordion);
    }

    /**
     * Groups a list of same-type issues by {@link ImportFileIssue#reason()} and renders one
     * {@code TitledPane} per reason, each containing a {@code ListView} of the individual file
     * paths — used identically for both the Skipped and Failed sections.
     */
    private void renderIssueSection(List<ImportFileIssue> issues, Accordion accordion) {
        Map<String, List<String>> byReason = new LinkedHashMap<>();
        for (ImportFileIssue issue : issues) {
            byReason.computeIfAbsent(issue.reason(), r -> new ArrayList<>())
                    .add(issue.absolutePath());
        }

        List<TitledPane> panes = new ArrayList<>();
        for (var entry : byReason.entrySet()) {
            ListView<String> pathsList = new ListView<>();
            pathsList.getItems()
                     .setAll(entry.getValue());
            pathsList.setPrefHeight(Math.min(200, 32 + entry.getValue()
                                                            .size() * 24));

            TitledPane pane = new TitledPane(entry.getKey() + " (" + entry.getValue()
                                                                          .size() + ")", pathsList);
            panes.add(pane);
        }
        accordion.getPanes()
                 .setAll(panes);
    }

    private String formatDuration(Duration duration) {
        long totalSeconds = Math.max(0, duration.toSeconds());
        long hours        = totalSeconds / 3600;
        long minutes      = (totalSeconds % 3600) / 60;
        long seconds      = totalSeconds % 60;
        if (hours > 0) {
            return "%dh %dm %ds".formatted(hours, minutes, seconds);
        }
        if (minutes > 0) {
            return "%dm %ds".formatted(minutes, seconds);
        }
        return "%ds".formatted(seconds);
    }
}
