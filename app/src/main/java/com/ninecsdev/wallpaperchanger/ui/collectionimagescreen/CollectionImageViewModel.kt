package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.RelinkResult
import com.ninecsdev.wallpaperchanger.data.TransferResult
import com.ninecsdev.wallpaperchanger.data.local.CollectionPreview
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.source.SourceDetails
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.pinnedFirst
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionImageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: WallpaperRepository
) : ViewModel(), CollectionImageActions {

    private val collectionId: Long = checkNotNull(savedStateHandle["collectionId"])

    private val _uiState = MutableStateFlow(CollectionImageUiState())
    val uiState: StateFlow<CollectionImageUiState> = _uiState.asStateFlow()

    // No replay, so a notice raised while the screen is off-view is dropped rather than shown late
    // TODO tests: see vault note tests/One-Shot Event Delivery Tests
    private val _notices = MutableSharedFlow<CollectionImageNotice>(
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val notices: SharedFlow<CollectionImageNotice> = _notices.asSharedFlow()

    init {
        loadCollectionMetadata()
        observeImages()
        observeFavorites()
        observeCollectionDefault()
        // Files marked unavailable may be readable again; clear the flag if so
        viewModelScope.launch { repository.reprobeUnavailableFiles(collectionId) }
    }

    private fun loadCollectionMetadata() {
        viewModelScope.launch {
            val collection = repository.getCollectionById(collectionId)
            _uiState.update {
                it.copy(
                    collectionName = collection?.name ?: "",
                    collectionType = collection?.type,
                    isFavoritesCollection = collection?.isFavorites == true,
                    collectionCropRule = collection?.defaultCropRule ?: WallpaperCollection.DEFAULT_CROP_RULE
                )
            }
        }
    }

    private fun observeFavorites() {
        viewModelScope.launch {
            repository.favoriteFileIdsFlow().collect { ids ->
                _uiState.update { it.copy(favoriteFileIds = ids) }
            }
        }
    }

    private fun observeCollectionDefault() {
        viewModelScope.launch {
            repository.collectionDefaultWallpaperIdFlow(collectionId).collect { id ->
                _uiState.update { it.copy(defaultWallpaperId = id) }
            }
        }
    }

    private fun observeImages() {
        viewModelScope.launch {
            repository.getImagesForCollection(collectionId)
                .collect { images ->
                    _uiState.update { state ->
                        // After saving an edit we return to the preview, so refresh it
                        val previewId = state.previewWallpaper?.id
                        val updatedPreview = previewId?.let { id ->
                            images.find { it.id == id }
                        }
                        state.copy(
                            wallpapers = images,
                            isLoading = false,
                            previewWallpaper = updatedPreview
                        )
                    }
                }
        }
    }

    fun addWallpapers(uris: List<Uri>) {
        viewModelScope.launch {
            val result = repository.addImagesToCollection(collectionId, uris.map { it.toString() })
            _notices.tryEmit(CollectionImageNotice.Import(result))
        }
    }

    override fun requestRelink(wallpaper: WallpaperImage) {
        _uiState.update { it.copy(relinkTarget = wallpaper) }
    }

    override fun cancelRelink() {
        _uiState.update { it.copy(relinkTarget = null) }
    }

    fun onRelinkPicked(uri: Uri?) {
        val target = _uiState.value.relinkTarget
        if (uri == null || target == null) {
            _uiState.update { it.copy(relinkTarget = null) }
            return
        }
        viewModelScope.launch {
            val result = repository.relinkUnavailableFile(target, uri.toString())
            _uiState.update { it.copy(relinkTarget = null) }
            if (result == RelinkResult.FAILED) {
                _notices.tryEmit(CollectionImageNotice.RelinkFailed)
            }
        }
    }

    override fun enterSelectionMode(id: Long) {
        _uiState.update {
            it.copy(isSelectionMode = true, selectedIds = setOf(id))
        }
    }

    /** Exits selection mode when the last selection is cleared. */
    override fun toggleSelection(id: Long) {
        _uiState.update { state ->
            val updated = if (id in state.selectedIds) {
                state.selectedIds - id
            } else {
                state.selectedIds + id
            }
            if (updated.isEmpty()) {
                state.copy(isSelectionMode = false, selectedIds = emptySet())
            } else {
                state.copy(selectedIds = updated)
            }
        }
    }

    override fun exitSelectionMode() {
        _uiState.update {
            it.copy(isSelectionMode = false, selectedIds = emptySet())
        }
    }

    override fun deleteSelectedWallpapers() {
        val state = _uiState.value
        if (state.selectedIds.isEmpty()) return

        val toDelete = state.wallpapers.filter { it.id in state.selectedIds }
        viewModelScope.launch {
            repository.deleteImagesFromCollection(toDelete)
            exitSelectionMode()
        }
    }

    /** Destinations are a one-shot snapshot: the picker is static, so nothing observes while it is open. */
    override fun requestTransfer(mode: TransferMode) {
        if (_uiState.value.selectedIds.isEmpty()) return
        viewModelScope.launch {
            val previews = repository.observeCollectionPreviews().first()
            val targets = repository.getAllCollections().first()
                .filter { it.id != collectionId && !it.isFavorites }
                .sortedByDescending { it.lastUsedAt }
                .pinnedFirst()
                .map { collection ->
                    // An empty collection has no preview row but is still a valid target
                    val preview = previews[collection.id] ?: CollectionPreview()
                    TransferTarget(
                        collectionId = collection.id,
                        name = collection.name,
                        previewFiles = preview.previewFiles,
                        imageCount = preview.imageCount,
                        isPinned = collection.isPinned
                    )
                }
            _uiState.update { it.copy(transferMode = mode, transferTargets = targets) }
        }
    }

    override fun cancelTransfer() {
        _uiState.update { it.copy(transferMode = null, transferTargets = emptyList(), isNamingNewTarget = false) }
    }

    override fun requestNewTransferTarget() {
        if (_uiState.value.transferMode == null) return
        _uiState.update { it.copy(isNamingNewTarget = true) }
    }

    override fun transferToNewCollection(name: String) {
        val state = _uiState.value
        val mode = state.transferMode ?: return
        val images = state.wallpapers.filter { it.id in state.selectedIds }
        if (images.isEmpty() || name.isBlank()) {
            cancelTransfer()
            return
        }
        viewModelScope.launch {
            val result = repository.createCollectionWithImages(
                name = name,
                images = images,
                sourceCollectionId = collectionId,
                removeFromSource = mode == TransferMode.MOVE
            )
            finishTransfer(mode, result, name)
        }
    }

    override fun transferToCollection(target: TransferTarget) {
        val state = _uiState.value
        val mode = state.transferMode ?: return
        val images = state.wallpapers.filter { it.id in state.selectedIds }
        if (images.isEmpty()) {
            cancelTransfer()
            return
        }
        viewModelScope.launch {
            val result = when (mode) {
                TransferMode.COPY -> repository.copyImagesToCollection(images, target.collectionId)
                TransferMode.MOVE -> repository.moveImagesToCollection(images, target.collectionId)
            }
            finishTransfer(mode, result, target.name)
        }
    }

    private fun finishTransfer(mode: TransferMode, result: TransferResult, targetName: String) {
        _uiState.update {
            it.copy(
                transferMode = null,
                transferTargets = emptyList(),
                isNamingNewTarget = false,
                isSelectionMode = false,
                selectedIds = emptySet()
            )
        }
        _notices.tryEmit(
            CollectionImageNotice.Transfer(
                TransferSummary(
                    mode = mode,
                    transferred = result.transferred,
                    alreadyPresent = result.alreadyPresent,
                    targetName = targetName
                )
            )
        )
    }

    override fun toggleFavoriteSelected() {
        val state = _uiState.value
        val selected = state.wallpapers.filter { it.id in state.selectedIds }
        if (selected.isEmpty()) return
        viewModelScope.launch {
            // Same derived property the toolbar heart renders from, so icon and action can't drift.
            if (state.isSelectionAllFavorites) {
                repository.removeFavorites(selected.map { it.fileId })
            } else {
                repository.addFavorites(selected.filter { it.fileId !in state.favoriteFileIds })
            }
            exitSelectionMode()
        }
    }

    override fun toggleFavorite(wallpaper: WallpaperImage) {
        val isFavorite = wallpaper.fileId in _uiState.value.favoriteFileIds
        viewModelScope.launch {
            if (isFavorite) {
                repository.removeFavorites(listOf(wallpaper.fileId))
            } else {
                repository.addFavorites(listOf(wallpaper))
            }
        }
    }

    override fun toggleCollectionDefaultSelected() {
        val wallpaper = _uiState.value.selectedWallpaper ?: return
        viewModelScope.launch {
            setCollectionDefault(wallpaper)
            exitSelectionMode()
        }
    }

    override fun toggleCollectionDefault(wallpaper: WallpaperImage) {
        viewModelScope.launch { setCollectionDefault(wallpaper) }
    }

    private suspend fun setCollectionDefault(wallpaper: WallpaperImage) {
        val alreadyDefault = wallpaper.id == _uiState.value.defaultWallpaperId
        repository.setCollectionDefaultWallpaper(collectionId, wallpaper.id.takeUnless { alreadyDefault })
    }

    override fun openPreview(wallpaper: WallpaperImage) {
        _uiState.update { it.copy(previewWallpaper = wallpaper) }
    }

    override fun closePreview() {
        _uiState.update { it.copy(previewWallpaper = null) }
    }

    // Read once per source, so reopening the sheet on a page does not reopen its file
    private val sourceDetailsCache = mutableMapOf<String, SourceDetails>()

    override suspend fun sourceDetails(wallpaper: WallpaperImage): SourceDetails =
        sourceDetailsCache[wallpaper.uriString] ?: repository.sourceDetails(wallpaper).also {
            // A failed read is not kept, so it is retried next time
            if (!it.isEmpty) sourceDetailsCache[wallpaper.uriString] = it
        }
}
