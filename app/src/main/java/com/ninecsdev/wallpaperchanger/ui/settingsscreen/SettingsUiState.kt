package com.ninecsdev.wallpaperchanger.ui.settingsscreen

/**
 * Snapshot of the Settings screen state.
 * Owned entirely by [SettingsViewModel].
 */
data class SettingsUiState(
    val screenOffDelayMs: Long = 250L,
    val startOnBoot: Boolean = true,
    val compressionQualityHigh: Int = 95,
    val compressionQualityLow: Int = 80,
    val appVersion: String = ""
)
