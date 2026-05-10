package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

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
    val previewWallpaper: WallpaperImage? = null
)

