package com.ninecsdev.wallpaperchanger.ui.settingsscreen

import com.ninecsdev.wallpaperchanger.data.backup.BackupExportState
import com.ninecsdev.wallpaperchanger.logic.DeviceStorage
import com.ninecsdev.wallpaperchanger.logic.StorageUsage
import com.ninecsdev.wallpaperchanger.model.RotationPolicy
import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix

/**
 * [tag] is a BCP-47 locale tag, or "" for the system default; [nativeName] is the language in itself,
 * [displayName] in the device locale (null for the default entry).
 */
data class LanguageOption(
    val tag: String,
    val nativeName: String,
    val displayName: String?
)

/** [device] is null when the platform will not report it; the readout then shows only the app's share. */
data class StorageBreakdown(
    val localCopies: StorageUsage,
    val cacheBytes: Long,
    val device: DeviceStorage?
) {
    val appBytes: Long
        get() = localCopies.totalBytes + cacheBytes

    val othersBytes: Long?
        get() = device?.let { (it.totalBytes - it.freeBytes - appBytes).coerceAtLeast(0L) }
}

data class SettingsUiState(
    val screenOffDelayMs: Long = 250L,
    val startOnBoot: Boolean = true,
    /** The app-wide cadence; collections without an override follow it. */
    val rotationPolicy: RotationPolicy = RotationPolicy.PerLock,
    val batterySaverPolicy: BatterySaverPolicy = BatterySaverPolicy.PAUSE,
    val skipOnDnd: Boolean = false,
    val wallpaperDestination: WallpaperDestination = WallpaperDestination.LOCK,
    val wallpaperMode: WallpaperMode = WallpaperMode.STATIC,
    // Whether our live wallpaper is actually the system wallpaper; null until the first check
    val atmosphereEngineActive: Boolean? = null,
    // Whether a source image exists to feed the atmosphere renderer; gates the set-button up-front.
    val hasAtmosphereSource: Boolean = false,
    val wallpaperZoomFix: WallpaperZoomFix = WallpaperZoomFix.OFF,
    val compressionQualityHigh: Int = 95,
    val compressionQualityLow: Int = 80,
    val keepLocalCopies: Boolean = false,
    val hasMediaAccess: Boolean = true,
    val hasPartialMediaAccess: Boolean = false,
    val availableLanguages: List<LanguageOption> = emptyList(),
    val selectedLanguageTag: String = "",
    val appVersion: String = "",
    val storage: StorageBreakdown? = null,
    val isClearingCache: Boolean = false,
    val backupExport: BackupExportState = BackupExportState.Idle
) {
    /**
     * Without `READ_MEDIA_IMAGES` (a partial grant counts as without) every pick is internalized, so the
     * toggle renders ON and disabled. The stored [keepLocalCopies] is never overwritten, so granting
     * the permission restores the user's choice.
     */
    val effectiveKeepLocalCopies: Boolean
        get() = keepLocalCopies || !hasMediaAccess

    /** The destination only governs static delivery, so it is inert from the moment the user opts into atmosphere (desired mode, not effective). */
    val isDestinationEnabled: Boolean
        get() = wallpaperMode != WallpaperMode.ATMOSPHERE
}
