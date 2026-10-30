package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import com.ninecsdev.wallpaperchanger.data.TransferResult
import com.ninecsdev.wallpaperchanger.data.local.PreviewFile
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

enum class TransferMode { COPY, MOVE }

data class TransferTarget(
    val collectionId: Long,
    val name: String,
    val previewFiles: List<PreviewFile>,
    val imageCount: Int = 0,
    val isPinned: Boolean = false
)

/** [transferred] counts operations that took effect (insert or edit-adoption); [alreadyPresent] counts no-op duplicates. */
data class TransferSummary(
    val mode: TransferMode,
    val transferred: Int,
    val alreadyPresent: Int,
    val targetName: String
)

data class CollectionImageUiState(
    val collectionName: String = "",
    val collectionType: CollectionType? = null,
    val isFavoritesCollection: Boolean = false,
    val collectionCropRule: CropRule = WallpaperCollection.DEFAULT_CROP_RULE,
    val wallpapers: List<WallpaperImage> = emptyList(),
    val favoriteFileIds: Set<Long> = emptySet(),
    val defaultWallpaperId: Long? = null,
    val isLoading: Boolean = true,
    val isSelectionMode: Boolean = false,
    val selectedIds: Set<Long> = emptySet(),
    val previewWallpaper: WallpaperImage? = null,
    /** The unavailable image being re-linked. */
    val relinkTarget: WallpaperImage? = null,
    /** Non-null while the target picker is open. */
    val transferMode: TransferMode? = null,
    /** All collections except this one. */
    val transferTargets: List<TransferTarget> = emptyList(),
    val isNamingNewTarget: Boolean = false
) {
    /** The wallpaper the edit action targets: the single selection, or null when edit doesn't apply. */
    val selectedWallpaper: WallpaperImage?
        get() = if (isSelectionMode && selectedIds.size == 1) {
            wallpapers.find { it.id in selectedIds }
        } else {
            null
        }

    /** When true the bulk favourite action un-favourites; otherwise it favourites the rest. */
    val isSelectionAllFavorites: Boolean
        get() = isSelectionMode && selectedIds.isNotEmpty() &&
            wallpapers.filter { it.id in selectedIds }.all { it.fileId in favoriteFileIds }

    /** When true the default action clears the default instead of setting it. */
    val isSelectionTheDefault: Boolean
        get() = defaultWallpaperId != null && selectedWallpaper?.id == defaultWallpaperId
}
