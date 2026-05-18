package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.LockscreenZoomFix
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
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
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val appVersion: String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: ""
    } catch (_: Exception) { "" }

    // Separate in 2 flows as combine max is 5
    private val lockscreenSettingsFlow = combine(
        appDataStore.batterySaverPolicyFlow(),
        appDataStore.lockscreenZoomFixFlow()
    ) { batterySaverPolicy, lockscreenZoomFix ->
        batterySaverPolicy to lockscreenZoomFix
    }

    val uiState: StateFlow<SettingsUiState> = combine(
        appDataStore.screenOffDelayFlow(),
        appDataStore.startOnBootFlow(),
        appDataStore.compressionQualityHighFlow(),
        appDataStore.compressionQualityLowFlow(),
        lockscreenSettingsFlow
    ) { delay, boot, qualityHigh, qualityLow, lockscreenSettings ->
        SettingsUiState(
            screenOffDelayMs = delay,
            startOnBoot = boot,
            batterySaverPolicy = lockscreenSettings.first,
            lockscreenZoomFix = lockscreenSettings.second,
            compressionQualityHigh = qualityHigh,
            compressionQualityLow = qualityLow,
            appVersion = appVersion
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SettingsUiState(appVersion = appVersion)
    )

    // Actions

    fun setScreenOffDelay(delayMs: Long) {
        viewModelScope.launch { appDataStore.setScreenOffDelay(delayMs) }
    }

    fun setStartOnBoot(enabled: Boolean) {
        viewModelScope.launch { appDataStore.setStartOnBoot(enabled) }
    }

    fun setCompressionQualityHigh(quality: Int) {
        viewModelScope.launch { appDataStore.setCompressionQualityHigh(quality) }
    }

    fun setCompressionQualityLow(quality: Int) {
        viewModelScope.launch { appDataStore.setCompressionQualityLow(quality) }
    }

    fun setBatterySaverPolicy(policy: BatterySaverPolicy) {
        viewModelScope.launch { appDataStore.setBatterySaverPolicy(policy) }
    }

    fun setLockscreenZoomFix(zoomFix: LockscreenZoomFix) {
        viewModelScope.launch { appDataStore.setLockscreenZoomFix(zoomFix) }
    }
}
