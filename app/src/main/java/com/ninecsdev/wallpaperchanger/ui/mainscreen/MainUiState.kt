package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.net.Uri
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage

/**
 * Snapshot of the Main screen state.
 * Owned entirely by [MainViewModel].
 */
data class MainUiState(
    // Service & System State
    val serviceState: ServiceState = ServiceState.Loading,

    // Active collection card data
    val activeCollection: WallpaperCollection? = null,
    val previewImages: List<WallpaperImage> = emptyList(),
    val activeCollectionSize: Int = 0,

    // Default wallpaper data
    val defaultWallpaperUri: Uri? = null,
    val revertToDefaultOnStop: Boolean = true
) {
    val isStartEnabled: Boolean
        get() = serviceState is ServiceState.Stopped

    val isStopEnabled: Boolean
        get() = serviceState is ServiceState.Running || serviceState is ServiceState.Paused
}