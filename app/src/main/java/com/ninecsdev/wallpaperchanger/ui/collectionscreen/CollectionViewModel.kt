package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for the Collection List screen.
 * Owns [CollectionUiState] and handles imports, edits, and preview loading.
 */
class CollectionViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = WallpaperRepository

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
        repository.serviceEvent.onStart { emit(Unit) }
    ) { collections, previews, modal, sort, _ ->
        val sorted = when (sort) {
            CollectionSortOrder.NAME -> collections.sortedBy { it.name.lowercase() }
            CollectionSortOrder.LAST_USED -> collections.sortedByDescending { it.lastUsedAt }
            CollectionSortOrder.DATE_CREATED -> collections.sortedByDescending { it.createdAt }
        }

        CollectionUiState(
            allCollections = sorted,
            previewStates = previews,
            serviceState = repository.getServiceState(),
            sortOrder = sort,
            isShowingCreateModal = modal.isShowingCreateModal,
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
    }

    fun setPendingPhotos(uris: List<Uri>) {
        pendingPhotosUris = uris
        pendingFolderUri = null
    }

    fun hasPendingFolder(): Boolean = pendingFolderUri != null
    fun hasPendingPhotos(): Boolean = pendingPhotosUris.isNotEmpty()

    // Collection CRUD

    fun finalizeFolderCollection(name: String, rule: CropRule, frequency: RotationFrequency, skipOnDnd: Boolean, onComplete: () -> Unit) {
        val uri = pendingFolderUri ?: return
        viewModelScope.launch {
            setProcessing(true)
            repository.importFolderAsCollection(name, uri, rule, frequency, skipOnDnd)
            pendingFolderUri = null
            setProcessing(false)
            onComplete()
        }
    }

    fun finalizeManualCollection(name: String, rule: CropRule, frequency: RotationFrequency, skipOnDnd: Boolean, onComplete: () -> Unit) {
        if (pendingPhotosUris.isEmpty()) return
        viewModelScope.launch {
            setProcessing(true)
            val appContext = getApplication<Application>().applicationContext
            val internalizedUris = ImageInternalizer.internalizeImages(appContext, pendingPhotosUris)
            repository.createManualCollection(name, internalizedUris, rule, frequency, skipOnDnd)
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
        rotationFrequency: RotationFrequency,
        skipOnDnd: Boolean
    ) {
        viewModelScope.launch {
            repository.updateCollection(collectionId, newName, cropRule, rotationFrequency, skipOnDnd)
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

    // Preview loading

    /**
     * Loads the 2×2 thumbnail previews for a collection.
     * Called once per visible grid item; cached in the map so it
     * doesn't re-fetch unless the screen is recreated.
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

    // Sort order

    fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    // Modal/navigation helpers

    fun toggleCreateModal(show: Boolean) {
        _screenState.update { it.copy(isShowingCreateModal = show) }
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
    val isShowingCreateModal: Boolean = false,
    val editingCollection: WallpaperCollection? = null,
    val isProcessing: Boolean = false
)
