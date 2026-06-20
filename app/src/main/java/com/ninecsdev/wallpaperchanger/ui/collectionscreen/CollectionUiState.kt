package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection

/**
 * Snapshot of the Collection List screen state.
 * Owned entirely by [CollectionViewModel].
 */
data class CollectionUiState(
    val allCollections: List<WallpaperCollection> = emptyList(),
    val previewStates: Map<Long, CollectionPreviewState> = emptyMap(),
    val serviceState: ServiceState = ServiceState.Loading,
    val sortOrder: CollectionSortOrder = CollectionSortOrder.LAST_USED,
    val isPickerMode: Boolean = false,
    val isShowingCreateModal: Boolean = false,
    val hasPendingFolder: Boolean = false,
    val hasPendingPhotos: Boolean = false,
    val editingCollection: WallpaperCollection? = null,
    val isProcessing: Boolean = false
)
