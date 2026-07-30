package com.github.curiousoddman.curious_images.event.payload;

public interface TreeViewUpdatePayload {

    record PersonDelete(long personId) implements TreeViewUpdatePayload {
    }

    record PersonRename(long personId, String newName) implements TreeViewUpdatePayload {
    }

    record AlbumRename(long albumId, String newName) implements TreeViewUpdatePayload {
    }

    record CustomAlbumRename(long customAlbumId, String newName) implements TreeViewUpdatePayload {
    }

    record CustomAlbumCreate(long customAlbumId, String name) implements TreeViewUpdatePayload {
    }

}
