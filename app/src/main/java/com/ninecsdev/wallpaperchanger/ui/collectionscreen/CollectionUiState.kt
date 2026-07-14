package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.ServiceState
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState

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
    val isProcessing: Boolean = false,
    /** One-shot summary of the last manual-collection pick import; cleared via [CollectionViewModel.clearImportSummary]. */
    val importSummary: PickImportResult? = null,
    /** True when the last collection-creation attempt failed; reset on the next attempt or when the modal closes. */
    val createError: Boolean = false
)
