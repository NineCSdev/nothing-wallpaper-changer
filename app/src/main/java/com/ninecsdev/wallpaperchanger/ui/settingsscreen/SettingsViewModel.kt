package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/**
 * ViewModel for the Settings screen.
 * Reads the app version from PackageInfo and combines all
 * settings flows into a single [SettingsUiState].
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val appVersion: String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: ""
    } catch (_: Exception) { "" }

    val uiState: StateFlow<SettingsUiState> = combine(
        appDataStore.screenOffDelayFlow(),
        appDataStore.startOnBootFlow(),
        appDataStore.compressionQualityHighFlow(),
        appDataStore.compressionQualityLowFlow(),
        appDataStore.batterySaverPolicyFlow()
    ) { delay, boot, qualityHigh, qualityLow, batterySaverPolicy ->
        SettingsUiState(
            screenOffDelayMs = delay,
            startOnBoot = boot,
            batterySaverPolicy = batterySaverPolicy,
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
        repository.setScreenOffDelay(delayMs)
    }

    fun setStartOnBoot(enabled: Boolean) {
        repository.setStartOnBoot(enabled)
    }

    fun setCompressionQualityHigh(quality: Int) {
        repository.setCompressionQualityHigh(quality)
    }

    fun setCompressionQualityLow(quality: Int) {
        repository.setCompressionQualityLow(quality)
    }

    fun setBatterySaverPolicy(policy: BatterySaverPolicy) {
        repository.setBatterySaverPolicy(policy)
    }
}
