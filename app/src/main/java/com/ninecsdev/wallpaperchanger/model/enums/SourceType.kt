package com.ninecsdev.wallpaperchanger.model.enums

/**
 * How the app accesses the physical bytes of a [WallpaperFile][com.ninecsdev.wallpaperchanger.model.WallpaperFile].
 *
 * Determines the cleanup action when the file becomes orphaned (no collection references it):
 * delete the app-private copy, release the persisted read grant, or nothing.
 */
enum class SourceType {
    /**
     * A photo-picker `content://` URI kept alive via `takePersistableUriPermission`.
     * The bytes live in the user's storage. On orphan, we release the grant.
     */
    PICKER_GRANT,

    /**
     * An app-private WebP copy under `files/internal_wallpapers/` produced by
     * [ImageInternalizer][com.ninecsdev.wallpaperchanger.logic.ImageInternalizer].
     * On orphan, we delete the file.
     */
    INTERNALIZED,

    /**
     * A document URI from a user-selected folder tree (`ACTION_OPEN_DOCUMENT_TREE`).
     * The app does not own the bytes; the grant is tied to the collection's `rootUri`, so on
     * orphan there is nothing file-specific to clean up.
     */
    FOLDER_DOC
}
