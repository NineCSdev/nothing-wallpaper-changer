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

// The zoom/offset state is gesture-driven and lives in the composable; it reaches save() only on confirm.
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

    /** [zoom] 1.0 is cover/fill; offsets are normalized to -1..1. Exits without writing when nothing changed. */
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

    // Currently unused: the editor leaves through resetAndExit
    fun reset() {
        val wp = _uiState.value.wallpaper ?: return
        viewModelScope.launch {
            repository.resetWallpaperEdit(wp)
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
