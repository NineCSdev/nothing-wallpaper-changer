package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
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
    private val serviceStateManager: ServiceStateManager,
    private val appDataStore: AppDataStore,
    private val imageInternalizer: ImageInternalizer,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    // TODO(v0.3.3): temporary cleanup, remove this init block along with
    // WallpaperRepository.releaseOrphanedFolderUriPermissions() once installs
    // have had a chance to run it
    init {
        viewModelScope.launch { repository.releaseOrphanedFolderUriPermissions() }
    }

    private val _serviceRefresh = MutableStateFlow(0L)

    // For performance reasons the state flow has been separated into 3 flows
    // depending on how much they update that are then combined.
    // Service state is evaluated in the outer combine so it reacts to
    // collection changes without needing imperative notifications.
    private val settingsFlow = combine(
        appDataStore.defaultWallpaperUriFlow(),
        appDataStore.revertToDefaultFlow(),
    ) { defaultUri, revert ->
        Pair(defaultUri, revert)
    }
    private val serviceRefreshFlow = combine(
        serviceStateManager.serviceEvent.onStart { emit(Unit) },
        _serviceRefresh
    ) { _, _ -> }

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
        settingsFlow,
        serviceRefreshFlow,
        activeCollectionFlow,
        previewsFlow
    ) { (defaultUri, revert), _, active, previews ->
        MainUiState(
            serviceState = serviceStateManager.getServiceState(),
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
        viewModelScope.launch { appDataStore.setRevertToDefault(isChecked) }
    }

    fun internalizeAndSaveDefaultWallpaper(uri: Uri) {
        viewModelScope.launch {
            val previousUri = appDataStore.getDefaultWallpaperUri()
            if (previousUri != null) imageInternalizer.deleteInternalFile(previousUri.path)
            val internalized = imageInternalizer.internalizeImages(context, listOf(uri))
            internalized.firstOrNull()?.let { appDataStore.saveDefaultWallpaperUri(it) }
        }
    }

    fun refreshServiceState() {
        _serviceRefresh.value = System.nanoTime()
    }
}
