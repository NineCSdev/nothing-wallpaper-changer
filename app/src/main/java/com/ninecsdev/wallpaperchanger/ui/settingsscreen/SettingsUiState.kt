package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix

/**
 * A selectable app-language entry in the settings picker.
 * [tag] is the BCP-47 locale tag, or "" for "System default".
 * [nativeName] is the language's name in that language itself (primary label).
 * [displayName] is its name in the device's current locale (secondary label; null for
 * the system-default entry).
 */
data class LanguageOption(
    val tag: String,
    val nativeName: String,
    val displayName: String?
)

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
    val availableLanguages: List<LanguageOption> = emptyList(),
    val selectedLanguageTag: String = "",
    val appVersion: String = ""
)
