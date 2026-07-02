package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Collection List screen.
 * Owns [CollectionUiState] and handles imports, edits, and preview loading.
 */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val repository: WallpaperRepository,
    private val serviceStateManager: ServiceStateManager
) : ViewModel() {

    // Internal mutable state

    private var pendingFolderUri: Uri? = null
    private var pendingPhotosUris: List<Uri> = emptyList()

    /** Preview thumbnails loaded on-demand for visible grid items. */
    private val _previewStates = MutableStateFlow<Map<Long, CollectionPreviewState>>(emptyMap())

    /** Current sort order for the collection list. */
    private val _sortOrder = MutableStateFlow(CollectionSortOrder.LAST_USED)

    /** Modal/processing state managed by this screen. */
    private val _screenState = MutableStateFlow(ScreenModalState())

    /** Combined public state built reactively. */
    val uiState: StateFlow<CollectionUiState> = combine(
        repository.getAllCollections(),
        _previewStates,
        _screenState,
        _sortOrder,
        serviceStateManager.serviceEvent.onStart { emit(Unit) }
    ) { collections, previews, modal, sort, _ ->
        val sorted = when (sort) {
            CollectionSortOrder.NAME -> collections.sortedBy { it.name.lowercase() }
            CollectionSortOrder.LAST_USED -> collections.sortedByDescending { it.lastUsedAt }
            CollectionSortOrder.DATE_CREATED -> collections.sortedByDescending { it.createdAt }
        }

        CollectionUiState(
            allCollections = sorted,
            previewStates = previews,
            serviceState = serviceStateManager.getServiceState(),
            sortOrder = sort,
            isPickerMode = modal.isPickerMode,
            isShowingCreateModal = modal.isShowingCreateModal,
            hasPendingFolder = modal.hasPendingFolder,
            hasPendingPhotos = modal.hasPendingPhotos,
            editingCollection = modal.editingCollection,
            isProcessing = modal.isProcessing
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = CollectionUiState()
    )

    // Pending source selection

    fun setPendingFolderUri(uri: Uri) {
        pendingFolderUri = uri
        pendingPhotosUris = emptyList()
        _screenState.update { it.copy(hasPendingFolder = true, hasPendingPhotos = false) }
    }

    fun setPendingPhotos(uris: List<Uri>) {
        pendingPhotosUris = uris
        pendingFolderUri = null
        _screenState.update { it.copy(hasPendingFolder = false, hasPendingPhotos = true) }
    }

    fun hasPendingFolder(): Boolean = pendingFolderUri != null
    fun hasPendingPhotos(): Boolean = pendingPhotosUris.isNotEmpty()

    // Collection CRUD

    fun finalizeFolderCollection(name: String, rule: CropRule, onComplete: () -> Unit) {
        val uri = pendingFolderUri ?: return
        viewModelScope.launch {
            setProcessing(true)
            repository.createFolderCollection(name, uri, rule)
            pendingFolderUri = null
            setProcessing(false)
            onComplete()
        }
    }

    fun finalizeManualCollection(name: String, rule: CropRule, onComplete: () -> Unit) {
        if (pendingPhotosUris.isEmpty()) return
        viewModelScope.launch {
            setProcessing(true)
            repository.createManualCollection(name, pendingPhotosUris, rule)
            pendingPhotosUris = emptyList()
            setProcessing(false)
            onComplete()
        }
    }

    fun deleteCollection(collection: WallpaperCollection, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.deleteCollection(collection)
            onComplete()
        }
    }

    fun updateCollection(
        collectionId: Long,
        newName: String,
        cropRule: CropRule,
        rotationFrequency: RotationFrequency
    ) {
        viewModelScope.launch {
            repository.updateCollection(collectionId, newName, cropRule, rotationFrequency)
        }
    }

    fun syncCollection(collectionId: Long, onComplete: () -> Unit) {
        viewModelScope.launch {
            setProcessing(true)
            repository.syncCollection(collectionId)
            setProcessing(false)
            onComplete()
        }
    }

    /**
     * Loads (or refreshes) the 2×2 thumbnail previews for a collection.
     * Called by the grid when a collection item becomes visible.
     */
    fun loadPreview(collectionId: Long) {
        if (_previewStates.value.containsKey(collectionId)) return
        viewModelScope.launch {
            val uris = repository.getImagesForCollectionOnce(collectionId)
                .take(4)
                .map { it.uri }
            val size = repository.getSizeOfCollection(collectionId)
            _previewStates.update { current ->
                current + (collectionId to CollectionPreviewState(uris, size))
            }
        }
    }

    /**
     * Evicts the cached preview for [collectionId] so the next [loadPreview] call
     * fetches fresh data. Call this when returning from the CollectionImageScreen.
     */
    fun invalidatePreview(collectionId: Long) {
        _previewStates.update { it - collectionId }
    }

    // Sort order

    fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    // Modal/navigation helpers

    fun setPickerMode(picker: Boolean) {
        _screenState.update { it.copy(isPickerMode = picker) }
    }

    fun toggleCreateModal(show: Boolean) {
        if (!show && pendingFolderUri != null) {
            repository.releaseFolderUriPermission(pendingFolderUri!!)
            pendingFolderUri = null
        }
        _screenState.update {
            if (show) it.copy(isShowingCreateModal = true)
            else it.copy(isShowingCreateModal = false, hasPendingFolder = false, hasPendingPhotos = false)
        }
    }

    fun openEditModal(collection: WallpaperCollection) {
        _screenState.update { it.copy(editingCollection = collection) }
    }

    fun closeEditModal() {
        _screenState.update { it.copy(editingCollection = null) }
    }

    private fun setProcessing(loading: Boolean) {
        _screenState.update { it.copy(isProcessing = loading) }
    }
}

/** Internal holder so modal flags can be combined as a single flow. */
private data class ScreenModalState(
    val isPickerMode: Boolean = false,
    val isShowingCreateModal: Boolean = false,
    val hasPendingFolder: Boolean = false,
    val hasPendingPhotos: Boolean = false,
    val editingCollection: WallpaperCollection? = null,
    val isProcessing: Boolean = false
)
