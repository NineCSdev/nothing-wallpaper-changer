package com.ninecsdev.wallpaperchanger.ui.walleditscreen

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
 * ViewModel for the Wallpaper Edit screen.
 *
 * Loads the wallpaper to edit, exposes save/reset actions,
 * and coordinates with the repository.
 *
 * The actual zoom/offset state lives in the composable (gesture-driven),
 * and is passed to [save] only when the user confirms.
 */
@HiltViewModel
class WallpaperEditViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val repository: WallpaperRepository,
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
     * Persists the edit params (zoom + offset) for this wallpaper.
     * If the params are unchanged (no-op edit), exits immediately without writing.
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
            repository.saveWallpaperEdit(wp, zoom, offsetX, offsetY)
            _uiState.update { it.copy(isSaving = false, shouldExit = true) }
        }
    }

    private fun isNoOpEdit(
        wallpaper: WallpaperImage,
        zoom: Float,
        offsetX: Float,
        offsetY: Float
    ): Boolean {
        val baseZoom = wallpaper.editParams?.zoom ?: 1f
        val baseOffsetX = wallpaper.editParams?.offsetX ?: 0f
        val baseOffsetY = wallpaper.editParams?.offsetY ?: 0f

        return isCloseEnough(zoom, baseZoom) &&
            isCloseEnough(offsetX, baseOffsetX) &&
            isCloseEnough(offsetY, baseOffsetY)
    }

    /**
     * Resets the edit: clears all edit parameters.
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
