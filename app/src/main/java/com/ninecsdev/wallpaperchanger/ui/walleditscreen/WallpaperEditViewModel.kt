package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.WallpaperEditRenderer
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Wallpaper Edit screen.
 *
 * Loads the wallpaper to edit, exposes save/reset actions,
 * and coordinates with the renderer and repository.
 *
 * The actual zoom/offset state lives in the composable (gesture-driven),
 * and is passed to [save] only when the user confirms.
 */
@HiltViewModel
class WallpaperEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: WallpaperRepository,
    private val renderer: WallpaperEditRenderer
) : ViewModel() {

    private val wallpaperId: Long = checkNotNull(savedStateHandle["wallpaperId"])

    private val _uiState = MutableStateFlow(WallpaperEditUiState())
    val uiState: StateFlow<WallpaperEditUiState> = _uiState.asStateFlow()

    init {
        loadWallpaper()
    }

    private fun loadWallpaper() {
        viewModelScope.launch {
            val wp = repository.getWallpaperById(wallpaperId)
            _uiState.update { it.copy(wallpaper = wp, isLoading = false) }
        }
    }

    /**
     * Renders the edited wallpaper from the original image and saves it.
     * On failure, sets [WallpaperEditUiState.saveError] instead of exiting.
     *
     * @param zoom Zoom factor (1.0 = cover/fill).
     * @param offsetX Normalized X offset (-1..1).
     * @param offsetY Normalized Y offset (-1..1).
     */
    fun save(zoom: Float, offsetX: Float, offsetY: Float) {
        val wp = _uiState.value.wallpaper ?: return

        if (isNoOpEdit(wp, zoom, offsetX, offsetY)) {
            _uiState.update { it.copy(shouldExit = true, saveError = false) }
            return
        }

        _uiState.update { it.copy(isSaving = true, saveError = false) }

        viewModelScope.launch {
            // Always render from the ORIGINAL uri to avoid quality loss
            val editedUri = renderer.renderAndSave(wp.uri, zoom, offsetX, offsetY)

            if (editedUri != null) {
                repository.saveWallpaperEdit(wp, editedUri, zoom, offsetX, offsetY)
                _uiState.update { it.copy(isSaving = false, shouldExit = true) }
            } else {
                // Render failed -> stay in the editor and show error
                _uiState.update { it.copy(isSaving = false, saveError = true) }
            }
        }
    }

    private fun isNoOpEdit(
        wallpaper: WallpaperImage,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): Boolean {
        val baseZoom = wallpaper.editZoom ?: 1f
        val baseOffsetX = wallpaper.editOffsetX ?: 0f
        val baseOffsetY = wallpaper.editOffsetY ?: 0f

        return isCloseEnough(zoom, baseZoom) &&
            isCloseEnough(offsetX, baseOffsetX) &&
            isCloseEnough(offsetY, baseOffsetY)
    }

    /**
     * Resets the edit: deletes the edited file and clears all edit parameters.
     * The wallpaper falls back to displaying the original URI.
     *
     * Note: not used due to new exit after undoing edit revise deletion
     */
    fun reset() {
        val wp = _uiState.value.wallpaper ?: return
        viewModelScope.launch {
            repository.resetWallpaperEdit(wp)
            // Reload to get the fresh state with cleared edit params
            val refreshed = repository.getWallpaperById(wallpaperId)
            _uiState.update { it.copy(wallpaper = refreshed) }
        }
    }

    fun resetAndExit() {
        val wp = _uiState.value.wallpaper ?: return
        viewModelScope.launch {
            repository.resetWallpaperEdit(wp)
            _uiState.update { it.copy(shouldExit = true) }
        }
    }

    /** Clears the save error flag after the user dismisses it. */
    fun clearError() {
        _uiState.update { it.copy(saveError = false) }
    }
}
