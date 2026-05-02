package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * ViewModel for the Settings screen.
 * Reads the app version from PackageInfo and combines all
 * settings flows into a single [SettingsUiState].
 */
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = WallpaperRepository
    private val context = application.applicationContext

    private val appVersion: String = try {
        context.packageManager
            .getPackageInfo(context.packageName, 0)
            .versionName ?: ""
    } catch (_: Exception) { "" }

    val uiState: StateFlow<SettingsUiState> = combine(
        AppDataStore.screenOffDelayFlow(context),
        AppDataStore.startOnBootFlow(context),
        AppDataStore.compressionQualityHighFlow(context),
        AppDataStore.compressionQualityLowFlow(context)
    ) { delay, boot, qualityHigh, qualityLow ->
        SettingsUiState(
            screenOffDelayMs = delay,
            startOnBoot = boot,
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
}
