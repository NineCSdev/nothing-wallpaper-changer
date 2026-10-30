package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import com.ninecsdev.wallpaperchanger.model.WallpaperImage

data class WallpaperEditUiState(
    val wallpaper: WallpaperImage? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val shouldExit: Boolean = false,
    val saveError: Boolean = false
)
