package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Collection List screen.
 * Owns [CollectionUiState] and handles imports, edits, and reactive preview loading.
 */
@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val repository: WallpaperRepository,
    serviceStateManager: ServiceStateManager
) : ViewModel(), CollectionListActions {

    private companion object {
        const val TAG = "CollectionViewModel"
    }

    // Internal mutable state

    private var pendingFolderUri: Uri? = null
    private var pendingPhotosUris: List<Uri> = emptyList()

    /** Current sort order for the collection list. */
    private val _sortOrder = MutableStateFlow(CollectionSortOrder.LAST_USED)

    /** Modal/processing state managed by this screen. */
    private val _screenState = MutableStateFlow(ScreenModalState())

    /**
     * Grid previews, derived reactively from the DB. Each collection's 2×2 thumbnails and total
     * count update on their own when images are added/removed.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val previewsFlow: Flow<Map<Long, CollectionPreviewState>> =
        repository.getAllCollections()
            .map { collections -> collections.map { it.id } }
            .distinctUntilChanged()
            .flatMapLatest { ids ->
                if (ids.isEmpty()) {
                    flowOf(emptyMap())
                } else {
                    combine(
                        ids.map { id ->
                            combine(
                                repository.observePreviewImages(id),
                                repository.observeImageCount(id)
                            ) { images, count ->
                                id to CollectionPreviewState(images.map { it.uri }, count)
                            }
                        }
                    ) { entries -> entries.toMap() }
                }
            }

    /**
     * Combined public state built reactively.
     * Null until every source flow has emitted; the UI renders nothing until then so no
     * fabricated default (e.g. an empty collection list) can flash before the real data.
     * TODO tests: see vault note tests/ui-state-loading.md
     */
    val uiState: StateFlow<CollectionUiState?> = combine(
        repository.getAllCollections(),
        previewsFlow,
        _screenState,
        _sortOrder,
        serviceStateManager.serviceState
    ) { collections, previews, modal, sort, serviceState ->
        val sorted = when (sort) {
            CollectionSortOrder.NAME -> collections.sortedBy { it.name.lowercase() }
            CollectionSortOrder.LAST_USED -> collections.sortedByDescending { it.lastUsedAt }
            CollectionSortOrder.DATE_CREATED -> collections.sortedByDescending { it.createdAt }
        }

        CollectionUiState(
            allCollections = sorted,
            previewStates = previews,
            serviceState = serviceState,
            sortOrder = sort,
            isPickerMode = modal.isPickerMode,
            isShowingCreateModal = modal.isShowingCreateModal,
            hasPendingFolder = modal.hasPendingFolder,
            hasPendingPhotos = modal.hasPendingPhotos,
            editingCollection = modal.editingCollection,
            isProcessing = modal.isProcessing,
            importSummary = modal.importSummary,
            createError = modal.createError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
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

    // Collection CRUD

    /**
     * Creates the pending collection, folder or manual, whichever source is currently pending.
     */
    fun finalizeCollection(name: String, rule: CropRule, onComplete: (shouldStartService: Boolean) -> Unit) {
        _screenState.update { it.copy(createError = false) }
        if (pendingFolderUri != null) {
            finalizeFolderCollection(name, rule, onComplete)
        } else {
            finalizeManualCollection(name, rule, onComplete)
        }
    }

    private fun finalizeFolderCollection(name: String, rule: CropRule, onComplete: (shouldStartService: Boolean) -> Unit) {
        val uri = pendingFolderUri ?: return
        viewModelScope.launch {
            setProcessing(true)
            try {
                val shouldStartService = repository.createFolderCollection(name, uri, rule)
                pendingFolderUri = null
                onComplete(shouldStartService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create folder collection: ${e.message}")
                _screenState.update { it.copy(createError = true) }
            } finally {
                setProcessing(false)
            }
        }
    }

    private fun finalizeManualCollection(name: String, rule: CropRule, onComplete: (shouldStartService: Boolean) -> Unit) {
        if (pendingPhotosUris.isEmpty()) return
        viewModelScope.launch {
            setProcessing(true)
            try {
                val (shouldStartService, importResult) = repository.createManualCollection(name, pendingPhotosUris, rule)
                pendingPhotosUris = emptyList()
                _screenState.update { it.copy(importSummary = importResult) }
                onComplete(shouldStartService)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create manual collection: ${e.message}")
                _screenState.update { it.copy(createError = true) }
            } finally {
                setProcessing(false)
            }
        }
    }

    /** Clears the pick-import summary once the UI has shown it. */
    override fun clearImportSummary() {
        _screenState.update { it.copy(importSummary = null) }
    }

    /**
     * Deletes the collection currently open in the edit modal. [onDeleted] receives whether it was
     * the active collection.
     */
    fun deleteEditingCollection(onDeleted: (wasActive: Boolean) -> Unit) {
        val collection = _screenState.value.editingCollection ?: return
        val wasActive = collection.isActive
        viewModelScope.launch {
            repository.deleteCollection(collection)
            closeEditModal()
            onDeleted(wasActive)
        }
    }

    /** Updates the collection currently open in the edit modal. */
    override fun updateEditingCollection(
        newName: String,
        cropRule: CropRule,
        rotationFrequency: RotationFrequency
    ) {
        val collection = _screenState.value.editingCollection ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, newName, cropRule, rotationFrequency)
        }
    }

    /** Makes the collection currently open in the edit modal the active one. */
    override fun setActiveEditingCollection() {
        val collection = _screenState.value.editingCollection ?: return
        viewModelScope.launch {
            repository.setActiveCollection(collection.id)
        }
    }

    /** Manually re-syncs the **folder** collection currently open in the edit modal. */
    override fun syncEditingCollection() {
        val collection = _screenState.value.editingCollection ?: return
        viewModelScope.launch {
            setProcessing(true)
            repository.syncCollection(collection.id)
            setProcessing(false)
        }
    }

    // Sort order

    override fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    // Modal/navigation helpers

    fun setPickerMode(picker: Boolean) {
        _screenState.update { it.copy(isPickerMode = picker) }
    }

    override fun toggleCreateModal(show: Boolean) {
        if (!show) pendingFolderUri = null
        _screenState.update {
            if (show) it.copy(isShowingCreateModal = true, createError = false)
            else it.copy(isShowingCreateModal = false, hasPendingFolder = false, hasPendingPhotos = false, createError = false)
        }
    }

    /** Opens the edit modal for the collection with [collectionId], if it exists. */
    fun openEditModal(collectionId: Long) {
        val collection = uiState.value?.allCollections?.find { it.id == collectionId } ?: return
        _screenState.update { it.copy(editingCollection = collection) }
    }

    override fun closeEditModal() {
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
    val isProcessing: Boolean = false,
    val importSummary: PickImportResult? = null,
    val createError: Boolean = false
)