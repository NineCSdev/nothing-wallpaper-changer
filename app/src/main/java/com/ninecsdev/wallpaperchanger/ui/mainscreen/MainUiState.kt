package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.net.Uri
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
 * Number of preview thumbnails shown for the active collection on the main screen.
 * Shared by [MainViewModel] (which truncates [MainUiState.previewImages] to this) and
 * [com.ninecsdev.wallpaperchanger.ui.mainscreen.components.WallpaperSelectionCard] (which derives placeholders and the "+N more" count from it).
 */
const val PREVIEW_IMAGE_COUNT = 3

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
    val revertToDefaultOnStop: Boolean = true,

    // Lost photos data
    val mediaAccessLostCount: Int = 0,

    // Active-collection picker data
    val pickerSheet: CollectionPickerSheetState = CollectionPickerSheetState()
) {
    val isStartEnabled: Boolean
        get() = serviceState is ServiceState.Stopped

    val isStopEnabled: Boolean
        get() = serviceState is ServiceState.Running || serviceState is ServiceState.Paused
}