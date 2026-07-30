package com.github.curiousoddman.curious_images.ui.nodes;

import org.kordamp.ikonli.Ikon;
import org.kordamp.ikonli.materialdesign2.MaterialDesignA;
import org.kordamp.ikonli.materialdesign2.MaterialDesignC;
import org.kordamp.ikonli.materialdesign2.MaterialDesignD;
import org.kordamp.ikonli.materialdesign2.MaterialDesignF;
import org.kordamp.ikonli.materialdesign2.MaterialDesignG;
import org.kordamp.ikonli.materialdesign2.MaterialDesignM;

/**
 * Value object backing nodes in the library {@link javafx.scene.control.TreeView}.
 *
 * @param displayName text shown in the tree
 * @param payload     what to load when this node is selected, or {@code null} for pure grouping
 *                    nodes (FOLDERS_ROOT, TIMELINE_ROOT, TIMELINE_YEAR, ALBUMS_ROOT,
 *                    ALBUM_EVENT_ROOT, ALBUM_LOCATION_ROOT, ALBUM_SIMILARITY_ROOT,
 *                    ALBUM_CUSTOM_ROOT, PERSONS_ROOT, DUPLICATES_ROOT) that show nothing.
 *                    ALBUM_CUSTOM_ROOT is also where the tree's inline "+" (new album) control
 *                    lives — see {@code LibraryTreeCell} — since unlike the other album-kind
 *                    roots, its children are user-created rather than AI-generated.
 *                    DUPLICATES_FILE_ROOT and
 *                    DUPLICATES_FOLDER_ROOT also carry a {@code null} payload but are a special
 *                    case, same as the old DUPLICATES_ROOT was: selecting either shows a
 *                    duplicates-review view (see {@code LibraryController#onTreeSelectionChanged}) —
 *                    the file-level one for DUPLICATES_FILE_ROOT, the folder-level one for
 *                    DUPLICATES_FOLDER_ROOT. DUPLICATES_ROOT itself is just the grouping parent of
 *                    those two, exactly like ALBUMS_ROOT groups the album kinds. IMPORT_STATS is
 *                    the same kind of special case: {@code null} payload, but selecting it shows
 *                    the Last Import stats view (see {@code ImportStatsController}).
 * @param type        kind of node — drives icon selection and selection behaviour
 */
public record LibraryTreeNode(String displayName, NodePayload payload, NodeType type) {

    public enum NodeType {
        // Folder tree
        FOLDERS_ROOT,
        IMPORT_ROOT,
        FOLDER,
        // Last Import stats — a sibling of the IMPORT_ROOT entries under FOLDERS_ROOT, always
        // present (even before any import has ever run) and kept live-updated while a job runs.
        IMPORT_STATS,
        // Timeline
        TIMELINE_ROOT,
        TIMELINE_YEAR,
        TIMELINE_MONTH,
        TIMELINE_DAY,
        TIMELINE_UNDATED,
        // Albums
        ALBUMS_ROOT,
        ALBUM_EVENT_ROOT,
        ALBUM_LOCATION_ROOT,
        ALBUM_SIMILARITY_ROOT,
        ALBUM_EVENT,
        ALBUM_LOCATION,
        ALBUM_SIMILARITY,
        // Custom (user-created) albums — see album-refinement-feature-spec.md. Unlike the
        // three AI-generated kinds above, ALBUM_CUSTOM_ROOT carries the inline "+" control and
        // ALBUM_CUSTOM nodes support double-click rename.
        ALBUM_CUSTOM_ROOT,
        ALBUM_CUSTOM,
        // Persons (face-based)
        PERSONS_ROOT,
        PERSON,
        // Duplicates review — a pure grouping parent (DUPLICATES_ROOT) with two selectable
        // children: file-level (per-media) and folder-level (per-folder-pair) resolution.
        DUPLICATES_ROOT,
        DUPLICATES_FILE_ROOT,
        DUPLICATES_FOLDER_ROOT
    }

    /**
     * Returns the Ikonli icon for this node type.
     */
    public Ikon icon() {
        return switch (type) {
            case FOLDERS_ROOT -> MaterialDesignF.FOLDER_MULTIPLE;
            case IMPORT_ROOT -> MaterialDesignD.DATABASE;
            case FOLDER -> MaterialDesignF.FOLDER;
            case IMPORT_STATS -> MaterialDesignC.CHART_BOX;
            case TIMELINE_ROOT -> MaterialDesignC.CLOCK_OUTLINE;
            case TIMELINE_YEAR -> MaterialDesignC.CALENDAR_BLANK;
            case TIMELINE_MONTH -> MaterialDesignC.CALENDAR;
            case TIMELINE_DAY -> MaterialDesignC.CALENDAR_TODAY;
            case TIMELINE_UNDATED -> MaterialDesignC.CALENDAR_REMOVE;
            case ALBUMS_ROOT -> MaterialDesignA.ALBUM;
            case ALBUM_EVENT_ROOT, ALBUM_EVENT -> MaterialDesignC.CAMERA_BURST;
            case ALBUM_LOCATION_ROOT, ALBUM_LOCATION -> MaterialDesignM.MAP_MARKER_MULTIPLE;
            case ALBUM_SIMILARITY_ROOT, ALBUM_SIMILARITY -> MaterialDesignG.GOOGLE_PHOTOS;
            case ALBUM_CUSTOM_ROOT, ALBUM_CUSTOM -> MaterialDesignF.FOLDER_STAR;
            case PERSONS_ROOT -> MaterialDesignA.ACCOUNT_GROUP;
            case PERSON -> MaterialDesignA.ACCOUNT;
            case DUPLICATES_ROOT -> MaterialDesignC.CONTENT_DUPLICATE;
            case DUPLICATES_FILE_ROOT -> MaterialDesignF.FILE_IMAGE;
            case DUPLICATES_FOLDER_ROOT -> MaterialDesignF.FOLDER_MULTIPLE_IMAGE;
        };
    }

    @Override
    public String toString() {
        return displayName;
    }
}
