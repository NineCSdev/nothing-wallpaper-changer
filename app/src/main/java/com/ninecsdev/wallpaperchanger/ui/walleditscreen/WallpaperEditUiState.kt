package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Snapshot of the Wallpaper Edit screen state.
 * Owned entirely by [WallpaperEditViewModel].
 */
data class WallpaperEditUiState(
    val wallpaper: WallpaperImage? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val saveComplete: Boolean = false,
    val saveError: Boolean = false
)
