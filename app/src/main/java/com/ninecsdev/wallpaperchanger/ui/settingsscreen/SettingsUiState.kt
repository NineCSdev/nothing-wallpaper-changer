package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import com.ninecsdev.wallpaperchanger.model.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.LockscreenZoomFix
import com.ninecsdev.wallpaperchanger.model.WallpaperDestination

/**
 * Snapshot of the Settings screen state.
 * Owned entirely by [SettingsViewModel].
 */
data class SettingsUiState(
    val screenOffDelayMs: Long = 250L,
    val startOnBoot: Boolean = true,
    val batterySaverPolicy: BatterySaverPolicy = BatterySaverPolicy.PAUSE,
    val wallpaperDestination: WallpaperDestination = WallpaperDestination.LOCK,
    val lockscreenZoomFix: LockscreenZoomFix = LockscreenZoomFix.OFF,
    val compressionQualityHigh: Int = 95,
    val compressionQualityLow: Int = 80,
    val appVersion: String = ""
)
