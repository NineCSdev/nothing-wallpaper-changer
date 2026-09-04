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
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.pinnedFirst
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.components.asPreviewStates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    appDataStore: AppDataStore,
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

    /** Grid previews, derived reactively from the DB (see [WallpaperRepository.observeCollectionPreviews]). */
    private val previewsFlow: Flow<Map<Long, CollectionPreviewState>> =
        repository.observeCollectionPreviews().map { it.asPreviewStates() }

    /** Everything the edit modal needs beyond the collection row itself. */
    private data class ModalInputs(
        val modal: ScreenModalState,
        val exclusionCount: Int,
        val globalRotationPolicy: RotationPolicy,
        val defaultWallpaper: WallpaperImage?
    )

    /**
     * Modal state plus its reactive companions: the exclusion-tombstone count of the folder
     * collection open in the edit modal (0 otherwise), which lets the "Restore removed images (N)"
     * row hide itself; the global rotation policy the card seeds a fresh override from; and the
     * collection's default-wallpaper override, whose row collapses when there is none.
     * Bundled here because [combine] below is already at its five-flow limit.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val modalInputs: Flow<ModalInputs> = combine(
        _screenState,
        // Observed by id regardless of type as a manual collection's count is a constant 0.
        _screenState
            .map { it.editingCollectionId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(0) else repository.observeExclusionCount(id) },
        appDataStore.rotationPolicyFlow(),
        _screenState
            .map { it.editingCollectionId }
            .distinctUntilChanged()
            .flatMapLatest { id -> if (id == null) flowOf(null) else repository.collectionDefaultWallpaperFlow(id) }
    ) { modal, count, globalPolicy, default -> ModalInputs(modal, count, globalPolicy, default) }

    /**
     * Combined public state built reactively.
     * Null until every source flow has emitted; the UI renders nothing until then so no
     * fabricated default (e.g. an empty collection list) can flash before the real data.
     */
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<CollectionUiState?> = combine(
        repository.getAllCollections(),
        previewsFlow,
        modalInputs,
        _sortOrder,
        serviceStateManager.serviceState
    ) { collections, previews, modalInput, sort, serviceState ->
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
            isShowingCreateModal = modalInput.modal.isShowingCreateModal,
            hasPendingFolder = modalInput.modal.hasPendingFolder,
            hasPendingPhotos = modalInput.modal.hasPendingPhotos,
            editingCollection = modalInput.modal.editingCollectionId?.let { id -> collections.find { it.id == id } },
            editingExclusionCount = modalInput.exclusionCount,
            editingDefaultWallpaper = modalInput.defaultWallpaper,
            globalRotationPolicy = modalInput.globalRotationPolicy,
            isProcessing = modalInput.modal.isProcessing,
            importSummary = modalInput.modal.importSummary,
            createError = modalInput.modal.createError
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
        launchProcessing(
            operation = "Create folder collection",
            onFailure = { _screenState.update { it.copy(createError = true) } }
        ) {
            val shouldStartService = repository.createFolderCollection(name, uri, rule)
            pendingFolderUri = null
            onComplete(shouldStartService)
        }
    }

    private fun finalizeManualCollection(name: String, rule: CropRule, onComplete: (shouldStartService: Boolean) -> Unit) {
        if (pendingPhotosUris.isEmpty()) return
        launchProcessing(
            operation = "Create manual collection",
            onFailure = { _screenState.update { it.copy(createError = true) } }
        ) {
            val (shouldStartService, importResult) = repository.createManualCollection(name, pendingPhotosUris, rule)
            pendingPhotosUris = emptyList()
            _screenState.update { it.copy(importSummary = importResult) }
            onComplete(shouldStartService)
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
        val collection = editingCollection() ?: return
        val wasActive = collection.isActive
        viewModelScope.launch {
            repository.deleteCollection(collection)
            closeEditModal()
            onDeleted(wasActive)
        }
    }

    // Edit-card instant-apply intents: each field commits immediately decide if update the DAO to
    // have separate updating func for each the name, crop rule and frequency.
    // TODO tests: see vault note tests/Edit Collection Card Tests

    /** Renames the collection currently open in the edit modal. */
    override fun renameEditingCollection(newName: String) {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, newName, collection.defaultCropRule, collection.rotationPolicy)
        }
    }

    /** Sets the default crop rule of the collection currently open in the edit modal. */
    override fun setEditingCropRule(rule: CropRule) {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, collection.name, rule, collection.rotationPolicy)
        }
    }

    /** Sets the rotation setting of the collection currently open in the edit modal. */
    override fun setEditingRotationPolicy(setting: CollectionRotationSetting) {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, collection.name, collection.defaultCropRule, setting)
        }
    }

    /** Drops the editing collection's default-wallpaper override, returning it to the global one. */
    override fun clearEditingCollectionDefault() {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.setCollectionDefaultWallpaper(collection.id, null)
        }
    }

    /** Manually re-syncs the **folder** collection currently open in the edit modal. */
    override fun syncEditingCollection() {
        val collection = editingCollection() ?: return
        launchProcessing(operation = "Sync collection") {
            repository.syncCollection(collection.id)
        }
    }

    /**
     * "Restore removed images" for the **folder** collection currently open in the edit modal:
     * wipes its exclusion tombstones and re-syncs, bringing the in-app-deleted images back with
     * their edits. The dialog stays open; its restore row hides itself once the count hits zero.
     */
    override fun restoreRemovedImages() {
        val collection = editingCollection() ?: return
        launchProcessing(operation = "Restore removed images") {
            repository.restoreExcludedImages(collection.id)
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

    /** Makes the given collection the active one */
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

    /** Opens the edit modal for the collection with [collectionId]; renders once the id resolves. */
    fun openEditModal(collectionId: Long) {
        _screenState.update { it.copy(editingCollectionId = collectionId) }
    }

    override fun closeEditModal() {
        _screenState.update { it.copy(editingCollectionId = null) }
    }

    /** The live row of the collection open in the edit modal, resolved through [uiState]. */
    private fun editingCollection(): WallpaperCollection? = uiState.value?.editingCollection

    private fun setProcessing(loading: Boolean) {
        _screenState.update { it.copy(isProcessing = loading) }
    }

    /**
     * Runs [block] behind the screen's processing overlay, clearing it however [block] ends.
     * Failures are logged against [operation] and surfaced through [onFailure]; cancellation
     * propagates
     */
    // TODO tests: see vault note tests/Busy-Flag Guard Tests
    private fun launchProcessing(
        operation: String,
        onFailure: () -> Unit = {},
        block: suspend () -> Unit
    ) {
        viewModelScope.launch {
            setProcessing(true)
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "$operation failed", e)
                onFailure()
            } finally {
                setProcessing(false)
            }
        }
    }
}

/** Internal holder so modal flags can be combined as a single flow. */
private data class ScreenModalState(
    val isShowingCreateModal: Boolean = false,
    val hasPendingFolder: Boolean = false,
    val hasPendingPhotos: Boolean = false,
    val editingCollectionId: Long? = null,
    val isProcessing: Boolean = false,
    val importSummary: PickImportResult? = null,
    val createError: Boolean = false
)