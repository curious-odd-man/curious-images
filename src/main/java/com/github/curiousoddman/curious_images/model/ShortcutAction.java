package com.github.curiousoddman.curious_images.model;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;

import java.util.Arrays;
import java.util.Optional;

/**
 * Registry of every rebindable keyboard shortcut action in the app. General-purpose by design —
 * any screen can define an action here and look up its current binding via
 * {@code ShortcutRegistry.resolve(action)} — but, per the initial rollout, only the
 * album-refinement actions (unrefined-view state buttons, triage mode) are wired up so far.
 * <p>
 * {@link #id()} is the stable string persisted in {@code keyboard_shortcut.action_id} — it must
 * never change for an existing action once shipped, or a user's saved override for it becomes
 * orphaned. Adding a new action is just adding a new constant; removing one should be treated as
 * a deprecation (leave the id reserved) rather than deleted outright, for the same reason.
 */
public enum ShortcutAction {

    REFINE_MARK_NO("refine.no", "Unrefined view — mark No",
            new KeyCodeCombination(KeyCode.DIGIT1)),
    REFINE_MARK_RATHER_NO("refine.rather_no", "Unrefined view — mark Rather No",
            new KeyCodeCombination(KeyCode.DIGIT2)),
    REFINE_MARK_UNASSIGNED("refine.unassigned", "Unrefined view — mark Unassigned",
            new KeyCodeCombination(KeyCode.DIGIT3)),
    REFINE_MARK_RATHER_YES("refine.rather_yes", "Unrefined view — mark Rather Yes",
            new KeyCodeCombination(KeyCode.DIGIT4)),
    REFINE_MARK_YES("refine.yes", "Unrefined view — mark Yes",
            new KeyCodeCombination(KeyCode.DIGIT5)),

    TRIAGE_MARK_RATHER_NO("triage.rather_no", "Triage mode — mark Rather No",
            new KeyCodeCombination(KeyCode.DIGIT1)),
    TRIAGE_MARK_NO("triage.no", "Triage mode — mark No",
            new KeyCodeCombination(KeyCode.DIGIT2)),
    TRIAGE_MARK_RATHER_YES("triage.rather_yes", "Triage mode — mark Rather Yes",
            new KeyCodeCombination(KeyCode.DIGIT3)),
    TRIAGE_MARK_YES("triage.yes", "Triage mode — mark Yes",
            new KeyCodeCombination(KeyCode.DIGIT4));

    private final String         id;
    private final String         displayLabel;
    private final KeyCombination defaultCombination;

    ShortcutAction(String id, String displayLabel, KeyCombination defaultCombination) {
        this.id = id;
        this.displayLabel = displayLabel;
        this.defaultCombination = defaultCombination;
    }

    public String id() {
        return id;
    }

    /** Grouped/labelled for the Keyboard Shortcuts settings tab, e.g. "Unrefined view — mark No". */
    public String displayLabel() {
        return displayLabel;
    }

    public KeyCombination defaultCombination() {
        return defaultCombination;
    }

    public static Optional<ShortcutAction> fromId(String id) {
        return Arrays.stream(values())
                     .filter(a -> a.id.equals(id))
                     .findFirst();
    }
}
