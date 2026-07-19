package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CollectionType
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.RotationFrequency
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.pinnedFirst
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.components.collectionPreviewsFlow
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

    /** Grid previews, derived reactively from the DB (see [collectionPreviewsFlow]). */
    private val previewsFlow: Flow<Map<Long, CollectionPreviewState>> =
        repository.collectionPreviewsFlow()

    /**
     * Exclusion-tombstone count of the folder collection open in the edit modal (0 otherwise).
     * Observed reactively so the "Restore removed images (N)" row hides itself.
     * Paired with the modal state here because [combine] below is already at its five-flow limit.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val modalWithExclusionCount: Flow<Pair<ScreenModalState, Int>> = combine(
        _screenState,
        _screenState
            .map { it.editingCollection?.takeIf { c -> c.type == CollectionType.FOLDER }?.id }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(0) else repository.observeExclusionCount(id) }
    ) { modal, count -> modal to count }

    /**
     * Combined public state built reactively.
     * Null until every source flow has emitted; the UI renders nothing until then so no
     * fabricated default (e.g. an empty collection list) can flash before the real data.
     */
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<CollectionUiState?> = combine(
        repository.getAllCollections(),
        previewsFlow,
        modalWithExclusionCount,
        _sortOrder,
        serviceStateManager.serviceState
    ) { collections, previews, (modal, exclusionCount), sort, serviceState ->
        val sorted = when (sort) {
            CollectionSortOrder.NAME -> collections.sortedBy { it.name.lowercase() }
            CollectionSortOrder.LAST_USED -> collections.sortedByDescending { it.lastUsedAt }
            CollectionSortOrder.DATE_CREATED -> collections.sortedByDescending { it.createdAt }
        }.pinnedFirst() // central pinned-first rule, applied on top of the user's chosen sort

        CollectionUiState(
            allCollections = sorted,
            previewStates = previews,
            serviceState = serviceState,
            sortOrder = sort,
            isShowingCreateModal = modal.isShowingCreateModal,
            hasPendingFolder = modal.hasPendingFolder,
            hasPendingPhotos = modal.hasPendingPhotos,
            editingCollection = modal.editingCollection,
            editingExclusionCount = exclusionCount,
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

    /**
     * "Restore removed images" for the **folder** collection currently open in the edit modal:
     * wipes its exclusion tombstones and re-syncs, bringing the in-app-deleted images back with
     * their edits. The dialog stays open; its restore row hides itself once the count hits zero.
     */
    override fun restoreRemovedImages() {
        val collection = _screenState.value.editingCollection ?: return
        viewModelScope.launch {
            setProcessing(true)
            repository.restoreExcludedImages(collection.id)
            setProcessing(false)
        }
    }

    // Long-press context menu intents

    /** Flips the pin flag of the given collection; the list reorders reactively. */
    override fun togglePinned(collectionId: Long) {
        val collection = uiState.value?.allCollections?.find { it.id == collectionId } ?: return
        viewModelScope.launch {
            repository.setCollectionPinned(collectionId, !collection.isPinned)
        }
    }

    /** Makes the given collection the active one (menu sibling of [setActiveEditingCollection]). */
    override fun setActiveCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.setActiveCollection(collectionId)
        }
    }

    // Sort order

    override fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    // Modal/navigation helpers

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
    val isShowingCreateModal: Boolean = false,
    val hasPendingFolder: Boolean = false,
    val hasPendingPhotos: Boolean = false,
    val editingCollection: WallpaperCollection? = null,
    val isProcessing: Boolean = false,
    val importSummary: PickImportResult? = null,
    val createError: Boolean = false
)