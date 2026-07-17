package com.ninecsdev.wallpaperchanger.ui.mainscreen

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.ServiceStateManager
import com.ninecsdev.wallpaperchanger.data.StartupMaintenance
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.source.WallpaperSources
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Main screen.
 * Owns [MainUiState], combining settings, the active collection, its previews, and the
 * service state manager's derived state flow.
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: WallpaperRepository,
    serviceStateManager: ServiceStateManager,
    private val appDataStore: AppDataStore,
    private val imageInternalizer: ImageInternalizer,
    private val wallpaperSources: WallpaperSources,
    startupMaintenance: StartupMaintenance,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        const val TAG = "MainViewModel"
    }

    init {
        // One-shot app-startup housekeeping (orphan file-registry GC + internal-file disk sweep).
        startupMaintenance.runOnce()
    }

    // For performance reasons the state is split into narrower flows that update at different
    // rates and are then combined. Service state comes from the manager's single derived flow.
    private val settingsFlow = combine(
        appDataStore.defaultWallpaperUriFlow(),
        appDataStore.revertToDefaultFlow(),
    ) { defaultUri, revert ->
        Pair(defaultUri, revert)
    }

    // Full active collection (distinct on the whole object) so renames and rule changes on the
    // active collection reach the main screen.
    private val activeCollectionFlow = repository.getAllCollections()
        .map { it.find { coll -> coll.isActive } }
        .distinctUntilChanged()

    // Distinct only on id, used to (re)subscribe the preview flow, which only needs to switch
    // when the active collection identity changes, not on every metadata edit.
    private val activeCollectionIdFlow = activeCollectionFlow
        .distinctUntilChangedBy { it?.id }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val previewsFlow: Flow<List<WallpaperImage>> = activeCollectionIdFlow
        .flatMapLatest { active ->
            if (active != null) {
                repository.getImagesForCollection(active.id)
            } else {
                flowOf(emptyList())
            }
        }

    // Permission state has no system callback, so it's refreshed by the Route on
    // resume and by the grant launcher's result (see refreshMediaAccess).
    private val hasMediaAccess = MutableStateFlow(wallpaperSources.hasMediaAccess())

    // How many MediaStore references are currently unreadable for lack of the permission
    private val mediaAccessLostFlow = combine(
        hasMediaAccess,
        repository.observeMediaStoreFileCount()
    ) { hasAccess, referenceCount ->
        if (hasAccess) 0 else referenceCount
    }

    // Null until every source flow has emitted; the UI renders nothing until then so no
    // fabricated default (e.g. revertToDefaultOnStop) can flash or animate to the real value.
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<MainUiState?> = combine(
        settingsFlow,
        serviceStateManager.serviceState,
        activeCollectionFlow,
        previewsFlow,
        mediaAccessLostFlow
    ) { (defaultUri, revert), serviceState, active, previews, mediaAccessLost ->
        MainUiState(
            serviceState = serviceState,
            activeCollection = active,
            previewImages = previews.take(PREVIEW_IMAGE_COUNT),
            activeCollectionSize = previews.size,
            defaultWallpaperUri = defaultUri,
            revertToDefaultOnStop = revert,
            mediaAccessLostCount = mediaAccessLost
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // Actions

    fun setActiveCollection(collectionId: Long) {
        viewModelScope.launch {
            repository.setActiveCollection(collectionId)
        }
    }

    /**
     * Re-reads the `READ_MEDIA_IMAGES` state (on resume and after the banner's grant request).
     * A missing→granted transition re-runs the MediaStore availability sweep immediately, so
     * every reference badged unavailable by the revocation self-heals without an app restart.
     */
    fun refreshMediaAccess() {
        val granted = wallpaperSources.hasMediaAccess()

        val wasGranted = hasMediaAccess.value
        hasMediaAccess.value = granted
        if (granted && !wasGranted) {
            viewModelScope.launch { repository.reconcileMediaStoreAvailability() }
        }
    }

    fun setRevertToDefault(isChecked: Boolean) {
        viewModelScope.launch { appDataStore.setRevertToDefault(isChecked) }
    }

    fun internalizeAndSaveDefaultWallpaper(uri: Uri) {
        viewModelScope.launch {
            // Internalize and persist the new default before deleting the old file, so a
            // failed internalization keeps the previous default working.
            val internalized = imageInternalizer.internalizeImages(context, listOf(uri))
            val newUri = internalized.firstOrNull()
            if (newUri == null) {
                Log.e(TAG, "Failed to internalize new default wallpaper, keeping previous one")
                return@launch
            }
            val previousUri = appDataStore.getDefaultWallpaperUri()
            appDataStore.saveDefaultWallpaperUri(newUri)
            if (previousUri != null) imageInternalizer.deleteInternalFile(previousUri.path)
        }
    }
}
