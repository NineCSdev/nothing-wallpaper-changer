package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Snapshot of the Collection Image screen state.
 * Owned entirely by [CollectionImageViewModel].
 */
data class CollectionImageUiState(
    val collectionName: String = "",
    val wallpapers: List<WallpaperImage> = emptyList(),
    val isLoading: Boolean = true,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val previewWallpaper: WallpaperImage? = null,
    /** One-shot summary of the last pick import; cleared via [CollectionImageViewModel.clearImportSummary]. */
    val importSummary: PickImportResult? = null,
    /** The unavailable image the user tapped to re-link; Null when no re-link is in progress. */
    val relinkTarget: WallpaperImage? = null,
    /** One-shot flag: the last re-link attempt failed; drives the error snackbar. */
    val relinkFailed: Boolean = false
) {
    /** The wallpaper the edit action targets: the single selection, or null when edit doesn't apply. */
    val selectedWallpaper: WallpaperImage?
        get() = if (isSelectionMode && selectedIds.size == 1) {
            wallpapers.find { it.id in selectedIds }
        } else {
            null
        }

    /** True when the delete action applies: selection mode with at least one wallpaper selected. */
    val canDeleteSelection: Boolean
        get() = isSelectionMode && selectedIds.isNotEmpty()
}