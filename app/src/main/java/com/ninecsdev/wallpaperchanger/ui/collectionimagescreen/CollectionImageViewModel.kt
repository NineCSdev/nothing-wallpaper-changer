package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.RelinkResult
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Collection Image screen.
 * Owns [CollectionImageUiState] and loads wallpapers for the given collection.
 */
@HiltViewModel
class CollectionImageViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: WallpaperRepository
) : ViewModel() {

    private val collectionId: Long = checkNotNull(savedStateHandle["collectionId"])

    private val _uiState = MutableStateFlow(CollectionImageUiState())
    val uiState: StateFlow<CollectionImageUiState> = _uiState.asStateFlow()

    init {
        loadCollectionMetadata()
        observeImages()
        // Silent re-probe: files marked unavailable may be readable again (source restored,
        // permission re-granted, connectivity back), clear the flag if so.
        viewModelScope.launch { repository.reprobeUnavailableFiles(collectionId) }
    }

    private fun loadCollectionMetadata() {
        viewModelScope.launch {
            val collection = repository.getCollectionById(collectionId)
            _uiState.update {
                it.copy(
                    collectionName = collection?.name ?: "",
                    collectionType = collection?.type
                )
            }
        }
    }

    private fun observeImages() {
        viewModelScope.launch {
            repository.getImagesForCollection(collectionId)
                .collect { images ->
                    _uiState.update { state ->
                        // Update preview as after saving an edit we go back to the preview
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

    // Add wallpapers

    /**
     * Adds wallpapers from the photo picker to this collection. Each image is kept as an external
     * reference or internalized depending on the "keep local copies" setting and probe success;
     * the resulting counts are surfaced as a one-shot summary.
     */
    fun addWallpapers(uris: List<Uri>) {
        viewModelScope.launch {
            val result = repository.addImagesToCollection(collectionId, uris)
            _uiState.update { it.copy(importSummary = result) }
        }
    }

    /** Clears the pick-import summary once the UI has shown it. */
    fun clearImportSummary() {
        _uiState.update { it.copy(importSummary = null) }
    }

    // Re-link unavailable image

    /** Marks [wallpaper] as the re-link target, opening the confirm dialog. */
    fun requestRelink(wallpaper: WallpaperImage) {
        _uiState.update { it.copy(relinkTarget = wallpaper) }
    }

    fun cancelRelink() {
        _uiState.update { it.copy(relinkTarget = null) }
    }

    /**
     * Handles the photo-picker result for a re-link. A null [uri] (picker canceled) just closes the
     * flow; otherwise the picked source is rebound onto the target's file row.
     * A failure raises a one-shot snackbar flag.
     */
    fun onRelinkPicked(uri: Uri?) {
        val target = _uiState.value.relinkTarget
        if (uri == null || target == null) {
            _uiState.update { it.copy(relinkTarget = null) }
            return
        }
        viewModelScope.launch {
            val result = repository.relinkUnavailableFile(target, uri)
            _uiState.update {
                it.copy(
                    relinkTarget = null,
                    relinkFailed = result == RelinkResult.FAILED
                )
            }
        }
    }

    /** Clears the re-link failure flag once the UI has shown the snackbar. */
    fun clearRelinkFailed() {
        _uiState.update { it.copy(relinkFailed = false) }
    }

    // Selection mode

    /** Enters selection mode with [id] as the first selected wallpaper. */
    fun enterSelectionMode(id: Long) {
        _uiState.update {
            it.copy(isSelectionMode = true, selectedIds = setOf(id))
        }
    }

    /** Toggles a wallpaper's selection. If nothing remains selected, exits selection mode. */
    fun toggleSelection(id: Long) {
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

    /** Exits selection mode and clears all selections. */
    fun exitSelectionMode() {
        _uiState.update {
            it.copy(isSelectionMode = false, selectedIds = emptySet())
        }
    }

    /** Deletes all currently selected wallpapers from the collection. */
    fun deleteSelectedWallpapers() {
        val state = _uiState.value
        if (state.selectedIds.isEmpty()) return

        val toDelete = state.wallpapers.filter { it.id in state.selectedIds }
        viewModelScope.launch {
            repository.deleteImagesFromCollection(toDelete)
            exitSelectionMode()
        }
    }

    // Copy/move to another collection

    /**
     * Opens the transfer target picker for the current selection. Destinations are a one-shot
     * snapshot of every other collection with a few preview thumbnails (the dialog is static;
     * no need to keep observing while it's open).
     */
    fun requestTransfer(mode: TransferMode) {
        if (_uiState.value.selectedIds.isEmpty()) return
        viewModelScope.launch {
            val targets = repository.getAllCollections().first()
                .filter { it.id != collectionId }
                .map { collection ->
                    TransferTarget(
                        collectionId = collection.id,
                        name = collection.name,
                        previewUris = repository.observePreviewImages(collection.id, limit = 4)
                            .first()
                            .map { it.uri },
                        imageCount = repository.observeImageCount(collection.id).first()
                    )
                }
            _uiState.update { it.copy(transferMode = mode, transferTargets = targets) }
        }
    }

    /** Closes the transfer target picker without transferring. */
    fun cancelTransfer() {
        _uiState.update { it.copy(transferMode = null, transferTargets = emptyList()) }
    }

    /** Runs the pending copy/move of the selected wallpapers into [target], then exits selection. */
    fun transferToCollection(target: TransferTarget) {
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
            _uiState.update {
                it.copy(
                    transferMode = null,
                    transferTargets = emptyList(),
                    transferSummary = TransferSummary(
                        mode = mode,
                        transferred = result.transferred,
                        alreadyPresent = result.alreadyPresent,
                        targetName = target.name
                    ),
                    isSelectionMode = false,
                    selectedIds = emptySet()
                )
            }
        }
    }

    /** Clears the transfer summary once the UI has shown the snackbar. */
    fun clearTransferSummary() {
        _uiState.update { it.copy(transferSummary = null) }
    }

    // Full-screen preview

    fun openPreview(wallpaper: WallpaperImage) {
        _uiState.update { it.copy(previewWallpaper = wallpaper) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewWallpaper = null) }
    }
}
