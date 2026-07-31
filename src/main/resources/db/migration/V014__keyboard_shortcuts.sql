-- Dedicated table for user-customizable keyboard shortcuts. Deliberately its own table rather
-- than reusing the generic user_preferences key-value store: shortcuts are a distinct concept
-- (action id -> key combination, with conflict-detection semantics — see ShortcutRegistry) that
-- benefits from its own typed access rather than being just another loose string pref.
--
-- Only rows that override an action's compiled-in default (see ShortcutAction.java) are stored
-- here. An action with no row uses its default — this table is a customization layer, not the
-- source of truth for "what actions exist."
CREATE TABLE keyboard_shortcut
(
    action_id        VARCHAR(100) NOT NULL PRIMARY KEY, -- matches ShortcutAction#id(), e.g. "refine.yes"
    key_combination  VARCHAR(100) NOT NULL,              -- javafx.scene.input.KeyCombination#getName() form, e.g. "Ctrl+Shift+Digit1"
    updated_at       TIMESTAMP    NOT NULL
);
