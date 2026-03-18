package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.DelayLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for the Main Dashboard screen.
 * Builds [MainUiState] reactively by combining independent flows:
 *   1. Collections (Room) - active collection + previews
 *   2. Default wallpaper URI preference
 *   3. Revert-on-stop preference
 *   4. Service events - service state changes
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private data class UiInputs(
        val collections: List<com.ninecsdev.wallpaperchanger.model.WallpaperCollection>,
        val defaultWallpaperUri: Uri?,
        val revertToDefaultOnStop: Boolean,
        val delayLabel: DelayLabel
    )

    private val repository = WallpaperRepository

    /**
     * Internal trigger that forces a re-computation of the service state.
     * Merged with the repository's serviceEvent so both external (service)
     * and internal (user tap) triggers feed the same pipeline.
     */
    private val _serviceRefresh = MutableStateFlow(0L)

    /** Lightweight navigation flag — only field the VM mutates directly. */
    private val _showLists = MutableStateFlow(false)

    private val uiInputs = combine(
        combine(
            repository.getAllCollections(),
            repository.defaultWallpaperUriFlow,
            repository.revertToDefaultFlow,
            repository.delayLabelFlow
        ) { collections, defaultWallpaperUri, revertToDefaultOnStop, delayLabel ->
            UiInputs(
                collections = collections,
                defaultWallpaperUri = defaultWallpaperUri,
                revertToDefaultOnStop = revertToDefaultOnStop,
                delayLabel = delayLabel
            )
        },
        repository.serviceEvent.onStart { emit(Unit) },
        _serviceRefresh
    ) { inputs, _, _ ->
        inputs
    }

    val uiState: StateFlow<MainUiState> = combine(
        uiInputs,
        _showLists
    ) { inputs, showLists ->

        val collections = inputs.collections
        val active = collections.find { it.isActive }
        val previews = if (active != null) {
            repository.getImagesForCollectionOnce(active.id)
        } else {
            emptyList()
        }
        
        MainUiState(
            serviceState = repository.getServiceState(),
            activeCollection = active,
            previewImages = previews.take(3),
            activeCollectionSize = previews.size,
            defaultWallpaperUri = inputs.defaultWallpaperUri,
            revertToDefaultOnStop = inputs.revertToDefaultOnStop,
            delayLabel = inputs.delayLabel,
            isShowingLists = showLists
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = MainUiState()
    )

    // Actions

    fun setActiveCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.setActiveCollection(collectionId)
        }
    }

    fun setShowLists(show: Boolean) {
        _showLists.value = show
    }

    fun setRevertToDefault(isChecked: Boolean) {
        repository.setRevertToDefault(isChecked)
    }

    fun setDelayLabel(label: DelayLabel) {
        repository.setDelayLabel(label)
    }

    fun internalizeAndSaveDefaultWallpaper(uri: Uri) {
        viewModelScope.launch {
            val previousUri = repository.getDefaultWallpaperUri()
            if (previousUri != null) ImageInternalizer.deleteInternalFile(previousUri.path)
            val internalized = ImageInternalizer.internalizeImages(getApplication(), listOf(uri))
            internalized.firstOrNull()?.let { repository.saveDefaultWallpaperUri(it) }
        }
    }

    /**
     * Called by MainActivity when start/stop intents are fired
     * to provide instant visual feedback.
     * Also useful for onResume() to catch external state changes.
     */
    fun refreshServiceState() {
        _serviceRefresh.value = System.nanoTime()
    }
}
