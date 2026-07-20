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
    val hasMediaAccess: Boolean = true,
    val hasPartialMediaAccess: Boolean = false,
    val availableLanguages: List<LanguageOption> = emptyList(),
    val selectedLanguageTag: String = "",
    val appVersion: String = ""
) {
    /**
     * What "keep local copies" actually does right now: without `READ_MEDIA_IMAGES` every pick is
     * internalized regardless of the stored preference (a partial "selected photos" grant counts
     * as without as [hasPartialMediaAccess] only picks the row's copy), so the toggle renders ON
     * and disabled.
     * The stored [keepLocalCopies] is never overwritten so granting the permission restores user's choice.
     */
    val effectiveKeepLocalCopies: Boolean
        get() = keepLocalCopies || !hasMediaAccess
}
