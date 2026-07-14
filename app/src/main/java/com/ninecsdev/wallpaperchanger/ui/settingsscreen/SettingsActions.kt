package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix

/**
 * ViewModel-owned intents of the Settings screen, implemented by [SettingsViewModel].
 * Navigation and activity-level callbacks are deliberately not part of this contract;
 * they stay as plain parameters on [SettingsScreen] / [SettingsRoute].
 * TODO tests: see vault note tests/screen-actions-routes.md
 */
interface SettingsActions {
    fun setScreenOffDelay(delayMs: Long)
    fun setStartOnBoot(enabled: Boolean)
    fun setBatterySaverPolicy(policy: BatterySaverPolicy)
    fun setWallpaperDestination(destination: WallpaperDestination)
    fun setWallpaperZoomFix(zoomFix: WallpaperZoomFix)
    fun setCompressionQualityHigh(quality: Int)
    fun setCompressionQualityLow(quality: Int)
    fun setKeepLocalCopies(enabled: Boolean)
    fun setAppLanguage(tag: String)
}
