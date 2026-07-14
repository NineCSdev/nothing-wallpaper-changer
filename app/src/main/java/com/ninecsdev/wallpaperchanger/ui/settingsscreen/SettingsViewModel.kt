package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.ImageInternalizer
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Reads the app version from PackageInfo and combines all
 * settings flows into a single [SettingsUiState].
 *
 * Reads and writes settings directly through [AppDataStore]
 * Note: maybe in the future add a repository for this.
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appDataStore: AppDataStore,
    private val imageInternalizer: ImageInternalizer,
    @param:ApplicationContext private val context: Context
) : ViewModel(), SettingsActions {

    private val appVersion: String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: ""
    } catch (_: Exception) { "" }

    // Separate in 2 flows as combine max is 5
    private val lockscreenSettingsFlow = combine(
        appDataStore.batterySaverPolicyFlow(),
        appDataStore.wallpaperZoomFixFlow(),
        appDataStore.wallpaperDestinationFlow()
    ) { batterySaverPolicy, wallpaperZoomFix, wallpaperDestination ->
        Triple(batterySaverPolicy, wallpaperZoomFix, wallpaperDestination)
    }

    // Nested so the outer combine (5-arg max) still has a free slot for keepLocalCopies.
    private val lockscreenAndStorageSettingsFlow = combine(
        lockscreenSettingsFlow,
        appDataStore.keepLocalCopiesFlow()
    ) { lockscreenSettings, keepLocalCopies -> lockscreenSettings to keepLocalCopies }

    // Null until every settings flow has emitted; the UI renders nothing until then so no
    // fabricated default can flash or animate to the real persisted value.
    // TODO tests: see vault note tests/ui-state-loading.md
    val uiState: StateFlow<SettingsUiState?> = combine(
        appDataStore.screenOffDelayFlow(),
        appDataStore.startOnBootFlow(),
        appDataStore.compressionQualityHighFlow(),
        appDataStore.compressionQualityLowFlow(),
        lockscreenAndStorageSettingsFlow
    ) { delay, boot, qualityHigh, qualityLow, (lockscreenSettings, keepLocalCopies) ->
        SettingsUiState(
            screenOffDelayMs = delay,
            startOnBoot = boot,
            batterySaverPolicy = lockscreenSettings.first,
            wallpaperZoomFix = lockscreenSettings.second,
            wallpaperDestination = lockscreenSettings.third,
            compressionQualityHigh = qualityHigh,
            compressionQualityLow = qualityLow,
            keepLocalCopies = keepLocalCopies,
            appVersion = appVersion
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // Kept out of [uiState] on purpose: it's a filesystem-derived display value, not a setting,
    // and folding it into the combine would gate the whole screen's render on a directory walk.
    val storageUsage: StateFlow<StorageUsage?> = flow {
        emit(imageInternalizer.getStorageUsage(context))
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = null
    )

    // Actions

    override fun setScreenOffDelay(delayMs: Long) {
        viewModelScope.launch { appDataStore.setScreenOffDelay(delayMs) }
    }

    override fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setStartOnBoot(enabled) }
    }

    override fun setCompressionQualityHigh(quality: Int) {
        viewModelScope.launch { appDataStore.setCompressionQualityHigh(quality) }
    }

    override fun setCompressionQualityLow(quality: Int) {
        viewModelScope.launch { appDataStore.setCompressionQualityLow(quality) }
    }

    override fun setBatterySaverPolicy(policy: BatterySaverPolicy) {
        viewModelScope.launch { appDataStore.setBatterySaverPolicy(policy) }
    }

    override fun setWallpaperDestination(destination: WallpaperDestination) {
        viewModelScope.launch { appDataStore.setWallpaperDestination(destination) }
    }

    override fun setWallpaperZoomFix(zoomFix: WallpaperZoomFix) {
        viewModelScope.launch { appDataStore.setWallpaperZoomFix(zoomFix) }
    }

    override fun setKeepLocalCopies(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setKeepLocalCopies(enabled) }
    }
}
