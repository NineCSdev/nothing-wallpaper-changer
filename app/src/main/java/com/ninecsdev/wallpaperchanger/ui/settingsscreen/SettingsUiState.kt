package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix

/**
 * Snapshot of the Settings screen state.
 * Owned entirely by [SettingsViewModel].
 */
data class SettingsUiState(
    val screenOffDelayMs: Long = 250L,
    val startOnBoot: Boolean = true,
    val batterySaverPolicy: BatterySaverPolicy = BatterySaverPolicy.PAUSE,
    val wallpaperDestination: WallpaperDestination = WallpaperDestination.LOCK,
    val wallpaperZoomFix: WallpaperZoomFix = WallpaperZoomFix.OFF,
    val compressionQualityHigh: Int = 95,
    val compressionQualityLow: Int = 80,
    val keepLocalCopies: Boolean = false,
    val appVersion: String = ""
)
