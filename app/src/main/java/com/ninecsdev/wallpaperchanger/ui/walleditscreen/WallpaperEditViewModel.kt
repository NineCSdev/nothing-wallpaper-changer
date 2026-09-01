package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.ui.walleditscreen.components.matchesEditParams
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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

    private companion object {
        const val TAG = "WallpaperEditViewModel"
    }

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
    // TODO tests: see vault note tests/Busy-Flag Guard Tests
    fun save(zoom: Float, offsetX: Float, offsetY: Float) {
        val wp = _uiState.value.wallpaper ?: return

        if (matchesEditParams(wp.editParams, zoom, offsetX, offsetY)) {
            _uiState.update { it.copy(shouldExit = true, saveError = false) }
            return
        }

        // Clearing saveError here dismisses the banner: a retry is the only gesture the screen offers for it
        _uiState.update { it.copy(isSaving = true, saveError = false) }

        viewModelScope.launch {
            try {
                repository.saveWallpaperEdit(wp, zoom, offsetX, offsetY)
                _uiState.update { it.copy(shouldExit = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The edit is unsaved but the screen's gesture state survives, so the user can retry
                Log.e(TAG, "Save wallpaper edit failed", e)
                _uiState.update { it.copy(saveError = true) }
            } finally {
                _uiState.update { it.copy(isSaving = false) }
            }
        }
    }

    /**
     * Resets the edit: clears all edit parameters.
     * The wallpaper falls back to displaying the original URI.
     *
     * Note: not used due to new exit after undoing edit revise deletion
     * keep in case decided to use it in the future
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
            try {
                repository.resetWallpaperEdit(wp)
                _uiState.update { it.copy(shouldExit = true) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Reset wallpaper edit failed", e)
                _uiState.update { it.copy(saveError = true) }
            }
        }
    }
}
