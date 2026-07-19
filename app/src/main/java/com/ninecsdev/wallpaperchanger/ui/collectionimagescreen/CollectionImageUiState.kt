package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import com.ninecsdev.wallpaperchanger.data.TransferResult
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/** Whether a cross-collection transfer copies or moves the selection. */
enum class TransferMode { COPY, MOVE }

/** A candidate destination collection shown in the transfer target picker. */
data class TransferTarget(
    val collectionId: Long,
    val name: String,
    val previewUris: List<Uri>,
    val imageCount: Int = 0,
    val isPinned: Boolean = false
)

/**
 * One-shot outcome of the last transfer; drives the summary snackbar.
 * Counts follow [TransferResult]'s rule: [transferred] is operations that took effect
 * (insert or edit-adoption), [alreadyPresent] is nothing-changed duplicates.
 */
data class TransferSummary(
    val mode: TransferMode,
    val transferred: Int,
    val alreadyPresent: Int,
    val targetName: String
)

/**
 * Snapshot of the Collection Image screen state.
 * Owned entirely by [CollectionImageViewModel].
 */
data class CollectionImageUiState(
    val collectionName: String = "",
    val collectionType: CollectionType? = null,
    val isFavoritesCollection: Boolean = false,
    val wallpapers: List<WallpaperImage> = emptyList(),
    val favoriteFileIds: Set<Long> = emptySet(),
    val isLoading: Boolean = true,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val previewWallpaper: WallpaperImage? = null,
    /** One-shot summary of the last pick import; cleared via [CollectionImageViewModel.clearImportSummary]. */
    val importSummary: PickImportResult? = null,
    /** The unavailable image the user tapped to re-link; Null when no re-link is in progress. */
    val relinkTarget: WallpaperImage? = null,
    /** One-shot flag: the last re-link attempt failed; drives the error snackbar. */
    val relinkFailed: Boolean = false,
    /** The transfer being configured (target picker open); null when no transfer is in progress. */
    val transferMode: TransferMode? = null,
    /** Destination candidates for the open target picker (all collections except this one). */
    val transferTargets: List<TransferTarget> = emptyList(),
    /** One-shot summary of the last transfer; cleared via [CollectionImageViewModel.clearTransferSummary]. */
    val transferSummary: TransferSummary? = null
) {
    /** The wallpaper the edit action targets: the single selection, or null when edit doesn't apply. */
    val selectedWallpaper: WallpaperImage?
        get() = if (isSelectionMode && selectedIds.size == 1) {
            wallpapers.find { it.id in selectedIds }
        } else {
            null
        }

    /**
     * Whether every selected wallpaper is already favourite — the bulk action then un-favourites
     * them (and the toolbar heart shows filled); otherwise it favourites the ones that aren't yet.
     */
    val isSelectionAllFavorites: Boolean
        get() = isSelectionMode && selectedIds.isNotEmpty() &&
            wallpapers.filter { it.id in selectedIds }.all { it.fileId in favoriteFileIds }
}
