package com.ninecsdev.wallpaperchanger.model.enums

/**
 * How the app accesses the bytes of a [WallpaperFile][com.ninecsdev.wallpaperchanger.model.WallpaperFile].
 * Decides the cleanup when the file is orphaned (no collection references it).
 */
enum class SourceType {
    /** A stable `content://media/external/images/media/<id>` URI derived at pick time from the photo-picker URI. Orphan: nothing to clean up. */
    MEDIA_STORE,

    /** An app-private WebP copy under `files/internal_wallpapers/`. Orphan: the file is deleted. */
    INTERNALIZED,

    /** A document URI under a user-selected folder tree; the grant is tied to the collection's `rootUri`. Orphan: nothing file-specific to clean up. */
    FOLDER_DOC
}
