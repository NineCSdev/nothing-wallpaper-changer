package com.ninecsdev.wallpaperchanger.ui.mainscreen

import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState

/** [collections] and [previewStates] are only populated while the sheet is open. */
data class CollectionPickerSheetState(
    val isOpen: Boolean = false,
    val collections: List<WallpaperCollection> = emptyList(),
    val previewStates: Map<Long, CollectionPreviewState> = emptyMap()
)

data class MainUiState(
    val serviceState: ServiceState = ServiceState.Loading,

    val activeCollection: WallpaperCollection? = null,
    val collectionImages: List<WallpaperImage> = emptyList(),

    val defaultWallpaper: WallpaperImage? = null,
    val revertToDefaultOnStop: Boolean = true,
    val isApplyingDefault: Boolean = false,

    val mediaAccessLostCount: Int = 0,

    val pickerSheet: CollectionPickerSheetState = CollectionPickerSheetState()
) {
    val isStartEnabled: Boolean
        get() = serviceState is ServiceState.Stopped

    val isStopEnabled: Boolean
        get() = serviceState is ServiceState.Running ||
            serviceState is ServiceState.Paused ||
            serviceState is ServiceState.PausedDnd
}