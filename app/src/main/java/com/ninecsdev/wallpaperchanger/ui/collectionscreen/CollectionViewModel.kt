package com.ninecsdev.wallpaperchanger.ui.collectionscreen

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.source.PickImportResult
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
import com.ninecsdev.wallpaperchanger.model.enums.CollectionSortOrder
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.ServiceLifecycle
import com.ninecsdev.wallpaperchanger.model.CollectionRotationSetting
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.pinnedFirst
import com.ninecsdev.wallpaperchanger.ui.components.CollectionPreviewState
import com.ninecsdev.wallpaperchanger.ui.ServiceCommand
import com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.CollectionGridWarmer
import com.ninecsdev.wallpaperchanger.ui.components.asPreviewStates
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
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

@HiltViewModel
class CollectionViewModel @Inject constructor(
    private val repository: WallpaperRepository,
    private val wallpaperSources: WallpaperSources,
    private val gridWarmer: CollectionGridWarmer,
    appDataStore: AppDataStore,
    serviceLifecycle: ServiceLifecycle
) : ViewModel(), CollectionListActions {

    private companion object {
        const val TAG = "CollectionViewModel"
    }

    private var pendingFolderUri: Uri? = null
    private var pendingPhotosUris: List<Uri> = emptyList()

    private var activateOnCreate = false
    private var createOnArrival = false

    private val _sortOrder = MutableStateFlow(CollectionSortOrder.LAST_USED)

    // No replay, so a notice raised while the screen is off-view is dropped rather than shown late
    // TODO tests: see vault note tests/One-Shot Event Delivery Tests
    private val _notices = MutableSharedFlow<PickImportResult>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val notices: SharedFlow<PickImportResult> = _notices.asSharedFlow()

    // Buffered, unlike the notices above: a start the user asked for is still wanted once
    // something is listening again, so it waits rather than being dropped.
    // TODO tests: see vault note tests/One-Shot Event Delivery Tests
    private val _serviceCommands = Channel<ServiceCommand>(Channel.BUFFERED)

    val serviceCommands: Flow<ServiceCommand> = _serviceCommands.receiveAsFlow()

    private val _screenState = MutableStateFlow(ScreenModalState())

    private val previewsFlow: Flow<Map<Long, CollectionPreviewState>> =
        repository.observeCollectionPreviews().map { it.asPreviewStates() }

    private data class ModalInputs(
        val modal: ScreenModalState,
        val exclusionCount: Int,
        val globalRotationPolicy: RotationPolicy,
        val defaultWallpaper: WallpaperImage?
    )

    // Folded into one flow because the combine below is at its five-flow limit.
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

    // Null until every source flow has emitted, so no fabricated default flashes before the real data.
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<CollectionUiState?> = combine(
        repository.getAllCollections(),
        previewsFlow,
        modalInputs,
        _sortOrder,
        serviceLifecycle.serviceState.filterNotNull()
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
            suggestedCollectionName = modalInput.modal.suggestedCollectionName,
            editingCollection = modalInput.modal.editingCollectionId?.let { id -> collections.find { it.id == id } },
            editingExclusionCount = modalInput.exclusionCount,
            editingDefaultWallpaper = modalInput.defaultWallpaper,
            globalRotationPolicy = modalInput.globalRotationPolicy,
            isProcessing = modalInput.modal.isProcessing,
            createError = modalInput.modal.createError
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = null
    )

    fun setPendingFolderUri(uri: Uri) {
        pendingFolderUri = uri
        pendingPhotosUris = emptyList()
        _screenState.update {
            it.copy(hasPendingFolder = true, hasPendingPhotos = false, suggestedCollectionName = null)
        }
        viewModelScope.launch {
            val name = wallpaperSources.folderDisplayName(uri.toString()) ?: return@launch
            if (pendingFolderUri != uri) return@launch
            _screenState.update { it.copy(suggestedCollectionName = name) }
        }
    }

    fun setPendingPhotos(uris: List<Uri>) {
        pendingPhotosUris = uris
        pendingFolderUri = null
        _screenState.update {
            it.copy(hasPendingFolder = false, hasPendingPhotos = true, suggestedCollectionName = null)
        }
    }

    fun finalizeCollection(name: String, rule: CropRule) {
        _screenState.update { it.copy(createError = false) }
        if (pendingFolderUri != null) {
            finalizeFolderCollection(name, rule)
        } else {
            finalizeManualCollection(name, rule)
        }
    }

    private fun finalizeFolderCollection(name: String, rule: CropRule) {
        val uri = pendingFolderUri ?: return
        launchProcessing(
            operation = "Create folder collection",
            onFailure = { _screenState.update { it.copy(createError = true) } }
        ) {
            val shouldStartService = repository.createFolderCollection(name, uri.toString(), rule, activateOnCreate)
            pendingFolderUri = null
            onCollectionCreated(shouldStartService)
        }
    }

    private fun finalizeManualCollection(name: String, rule: CropRule) {
        if (pendingPhotosUris.isEmpty()) return
        launchProcessing(
            operation = "Create manual collection",
            onFailure = { _screenState.update { it.copy(createError = true) } }
        ) {
            val (shouldStartService, importResult) = repository.createManualCollection(name, pendingPhotosUris.map { it.toString() }, rule, activateOnCreate)
            pendingPhotosUris = emptyList()
            _notices.tryEmit(importResult)
            onCollectionCreated(shouldStartService)
        }
    }

    private fun onCollectionCreated(shouldStartService: Boolean) {
        toggleCreateModal(false)
        if (shouldStartService) _serviceCommands.trySend(ServiceCommand.Start)
    }

    fun deleteEditingCollection() {
        val collection = editingCollection() ?: return
        val wasActive = collection.isActive
        viewModelScope.launch {
            repository.deleteCollection(collection)
            closeEditModal()
            if (wasActive) _serviceCommands.trySend(ServiceCommand.Stop)
        }
    }

    // Instant-apply: each field commits as soon as it changes.
    // TODO tests: see vault note tests/Edit Collection Card Tests

    override fun renameEditingCollection(newName: String) {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, newName, collection.defaultCropRule, collection.rotationPolicy)
        }
    }

    override fun setEditingCropRule(rule: CropRule) {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, collection.name, rule, collection.rotationPolicy)
        }
    }

    override fun setEditingRotationPolicy(setting: CollectionRotationSetting) {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.updateCollection(collection.id, collection.name, collection.defaultCropRule, setting)
        }
    }

    override fun clearEditingCollectionDefault() {
        val collection = editingCollection() ?: return
        viewModelScope.launch {
            repository.setCollectionDefaultWallpaper(collection.id, null)
        }
    }

    override fun syncEditingCollection() {
        val collection = editingCollection() ?: return
        launchProcessing(operation = "Sync collection") {
            repository.syncCollection(collection.id)
        }
    }

    /** Wipes the exclusion tombstones and re-syncs, so in-app-deleted images return with their edits. */
    override fun restoreRemovedImages() {
        val collection = editingCollection() ?: return
        launchProcessing(operation = "Restore removed images") {
            repository.restoreExcludedImages(collection.id)
        }
    }

    override fun togglePinned(collectionId: Long) {
        val collection = uiState.value?.allCollections?.find { it.id == collectionId } ?: return
        viewModelScope.launch {
            repository.setCollectionPinned(collectionId, !collection.isPinned)
        }
    }

    override fun setActiveCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.setActiveCollection(collectionId)
        }
    }

    override fun setSortOrder(order: CollectionSortOrder) {
        _sortOrder.value = order
    }

    fun requestCreateOnArrival() {
        createOnArrival = true
    }

    fun takeCreateOnArrival(): Boolean = createOnArrival.also { createOnArrival = false }

    /** Opens the create modal so the collection it creates also becomes the active one. */
    fun openCreateModalActivating() {
        activateOnCreate = true
        toggleCreateModal(true)
    }

    override fun toggleCreateModal(show: Boolean) {
        if (!show) {
            pendingFolderUri = null
            activateOnCreate = false
        }
        _screenState.update {
            if (show) it.copy(isShowingCreateModal = true, createError = false)
            else it.copy(
                isShowingCreateModal = false,
                hasPendingFolder = false,
                hasPendingPhotos = false,
                suggestedCollectionName = null,
                createError = false
            )
        }
    }

    fun prepareToOpen(collectionId: Long) = gridWarmer.warm(collectionId)

    fun openEditModal(collectionId: Long) {
        _screenState.update { it.copy(editingCollectionId = collectionId) }
    }

    override fun closeEditModal() {
        _screenState.update { it.copy(editingCollectionId = null) }
    }

    private fun editingCollection(): WallpaperCollection? = uiState.value?.editingCollection

    private fun setProcessing(loading: Boolean) {
        _screenState.update { it.copy(isProcessing = loading) }
    }

    /** Runs [block] behind the processing overlay, clearing it however it ends; failures go to [onFailure]. */
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

private data class ScreenModalState(
    val isShowingCreateModal: Boolean = false,
    val hasPendingFolder: Boolean = false,
    val hasPendingPhotos: Boolean = false,
    val suggestedCollectionName: String? = null,
    val editingCollectionId: Long? = null,
    val isProcessing: Boolean = false,
    val createError: Boolean = false
)