package com.ninecsdev.wallpaperchanger.ui.mainscreen

import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState

/**
 * State slice for the active-collection picker sheet. [collections] and [previewStates] are
 * only populated while the sheet is open (see MainViewModel.pickerSheetFlow).
 */
data class CollectionPickerSheetState(
    val isOpen: Boolean = false,
    val collections: List<WallpaperCollection> = emptyList(),
    val previewStates: Map<Long, CollectionPreviewState> = emptyMap()
)

/**
 * Snapshot of the Main screen state.
 * Owned entirely by [MainViewModel].
 */
data class MainUiState(
    // Service & System State
    val serviceState: ServiceState = ServiceState.Loading,

    // Active collection card data
    val activeCollection: WallpaperCollection? = null,
    val collectionImages: List<WallpaperImage> = emptyList(),

    // Default wallpaper data
    val defaultWallpaper: WallpaperImage? = null,
    val revertToDefaultOnStop: Boolean = true,
    val isApplyingDefault: Boolean = false,

    // Lost photos data
    val mediaAccessLostCount: Int = 0,

    // Active-collection picker data
    val pickerSheet: CollectionPickerSheetState = CollectionPickerSheetState()
) {
    val isStartEnabled: Boolean
        get() = serviceState is ServiceState.Stopped

    val isStopEnabled: Boolean
        get() = serviceState is ServiceState.Running ||
            serviceState is ServiceState.Paused ||
            serviceState is ServiceState.PausedDnd
}