package com.github.curiousoddman.curious_images.ui.styles;

import lombok.experimental.UtilityClass;

@UtilityClass
public class CssClasses {

    // Duplicate-resolution hover preview (DuplicatesController)
    public static final String KEEP_PREVIEW = "keep-preview";
    public static final String DROP_PREVIEW = "drop-preview";

    // Face-picker grid cell selection border (FacePickerCellController toggles this dynamically;
    // the base "face-cell" class itself is applied statically in face_picker_cell.fxml)
    public static final String FACE_CELL_SELECTED = "face-cell-selected";

    // Inline-editable Name/DoB/Notes fields (PersonDetailController)
    public static final String EDITABLE_FIELD        = "editable-field";
    public static final String EDITABLE_FIELD_ACTIVE = "editable-field-active";

    public static final String GRID_CELL_UNDERLINE = "grid-cell-underline";
    public static final String ERROR_TEXT          = "error-text";

    public static final String ERROR_NOTIFICATION_ICON   = "error-notification-icon";
    public static final String WARNING_NOTIFICATION_ICON = "warning-notification-icon";

    // Photo grid cell selection border (GridCellController#setSelected toggles this dynamically)
    public static final String GRID_CELL_SELECTED = "grid-cell-selected";

    // Notification detail/message text (NotificationMenuItemController)
    public static final String NOTIFICATION_TEXT = "notification-text";

    // Theme picker popup panel (ThemePickerButton)
    public static final String THEME_PICKER_PANEL  = "theme-picker-panel";
    public static final String THEME_PICKER_HEADER = "theme-picker-header";
    public static final String THEME_PICKER_ROW    = "theme-picker-row";
    public static final String THEME_PICKER_SWATCH = "theme-picker-swatch";
    public static final String THEME_PICKER_CHECK  = "theme-picker-check";
    public static final String BOLD_LABEL          = "bold-label";

    // Custom-album Refined/Unrefined toggle bar wrapper (CustomAlbumController) — see
    // theme-5-default.css's ".album-view-toggle-bar" block; not yet mirrored in the other themes.
    public static final String ALBUM_VIEW_TOGGLE_BAR = "album-view-toggle-bar";
}
