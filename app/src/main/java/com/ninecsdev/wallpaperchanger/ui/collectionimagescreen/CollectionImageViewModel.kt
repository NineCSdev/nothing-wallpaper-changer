package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    }

    private fun loadCollectionMetadata() {
        viewModelScope.launch {
            val collection = repository.getCollectionById(collectionId)
            _uiState.update { it.copy(collectionName = collection?.name ?: "") }
        }
    }

    private fun observeImages() {
        viewModelScope.launch {
            repository.getImagesForCollection(collectionId)
                .collect { images ->
                    _uiState.update { it.copy(wallpapers = images, isLoading = false) }
                }
        }
    }

    // Add wallpapers

    /**
     * Adds wallpapers from the photo picker to this collection.
     * Images are always internalized since picked URIs are temporary.
     */
    fun addWallpapers(uris: List<Uri>) {
        viewModelScope.launch {
            repository.addImagesToCollection(collectionId, uris)
        }
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
            repository.deleteImagesById(toDelete)
            exitSelectionMode()
        }
    }

    // Full-screen preview

    fun openPreview(wallpaper: WallpaperImage) {
        _uiState.update { it.copy(previewWallpaper = wallpaper) }
    }

    fun closePreview() {
        _uiState.update { it.copy(previewWallpaper = null) }
    }
}
