package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Main screen.
 * Owns [MainUiState] and handles service state refreshes.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: WallpaperRepository,
    private val imageInternalizer: ImageInternalizer,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _serviceRefresh = MutableStateFlow(0L)

    // For performance reasons the state flow has been separated into 3 flows
    // depending on how much they update that are then combined
    private val baseStateFlow = combine(
        repository.defaultWallpaperUriFlow,
        repository.revertToDefaultFlow,
        repository.serviceEvent.onStart { emit(Unit) },
        _serviceRefresh
    ) { defaultUri, revert, _, _ ->
        Triple(defaultUri, revert, repository.getServiceState())
    }
    private val activeCollectionFlow = repository.getAllCollections()
        .map { it.find { coll -> coll.isActive } }
        .distinctUntilChangedBy { it?.id }
    @OptIn(ExperimentalCoroutinesApi::class)
    private val previewsFlow: Flow<List<WallpaperImage>> = activeCollectionFlow
        .flatMapLatest { active ->
            if (active != null) {
                repository.getImagesForCollection(active.id)
            } else {
                flowOf(emptyList())
            }
        }

    val uiState: StateFlow<MainUiState> = combine(
        baseStateFlow,
        activeCollectionFlow,
        previewsFlow
    ) { (defaultUri, revert, serviceState), active, previews ->
        MainUiState(
            serviceState = serviceState,
            activeCollection = active,
            previewImages = previews.take(3),
            activeCollectionSize = previews.size,
            defaultWallpaperUri = defaultUri,
            revertToDefaultOnStop = revert
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = MainUiState()
    )

    // Actions

    fun setActiveCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.setActiveCollection(collectionId)
        }
    }

    fun setRevertToDefault(isChecked: Boolean) {
        repository.setRevertToDefault(isChecked)
    }

    fun internalizeAndSaveDefaultWallpaper(uri: Uri) {
        viewModelScope.launch {
            val previousUri = repository.getDefaultWallpaperUri()
            if (previousUri != null) imageInternalizer.deleteInternalFile(previousUri.path)
            val internalized = imageInternalizer.internalizeImages(context, listOf(uri))
            internalized.firstOrNull()?.let { repository.saveDefaultWallpaperUri(it) }
        }
    }

    fun refreshServiceState() {
        _serviceRefresh.value = System.nanoTime()
    }
}
