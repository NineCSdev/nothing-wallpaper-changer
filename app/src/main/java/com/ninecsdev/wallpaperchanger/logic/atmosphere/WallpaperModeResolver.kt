package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single authority for "which mode is *actually* in effect right now".
 *
 * The stored [WallpaperMode] (see [AppDataStore.wallpaperModeFlow]) is only the user's *desired*
 * mode. Atmosphere is *effective* solely when NWC's live wallpaper engine is the system wallpaper
 * (the user may have picked ATMOSPHERE but never confirmed it on the system picker, or replaced it
 * later with another launcher/wallpaper).
 * Both delivery routing and zoom-fix gating must consult [effectiveMode] rather than the raw preference.
 *
 * The desired setting is **never silently flipped** to reconcile a mismatch; the UI surfaces the
 * mismatch instead (a "not set yet" prompt in Settings) so the user's intent is preserved.
 */
@Singleton
class WallpaperModeResolver @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore
) {
    private companion object {
        const val TAG = "WallpaperModeResolver"
    }

    /**
     * True when NWC's [AtmosphereWallpaperService] is the currently-set system live wallpaper.
     * A live wallpaper reports its component via [WallpaperManager.getWallpaperInfo]; a static
     * wallpaper (or another app's live wallpaper) yields null / a different component.
     *
     * Blocking: [WallpaperManager.getWallpaperInfo] is a synchronous binder call into system_server.
     * Deliberately left non-suspend because the Settings resume hook needs a snapshot it can take
     * inline; call it off the main thread wherever a suspend context is available ([effectiveMode]
     * already does).
     */
    fun isAtmosphereEngineActive(): Boolean {
        val info = WallpaperManager.getInstance(appContext).wallpaperInfo ?: return false
        return info.packageName == appContext.packageName &&
            info.serviceName == AtmosphereWallpaperService::class.java.name
    }

    /**
     * ATMOSPHERE only when the user desires it **and** the engine is actually live; STATIC otherwise
     * (including the "desired atmosphere but not confirmed on the system" mismatch case).
     *
     * Runs on [Dispatchers.IO] because [isAtmosphereEngineActive] blocks on binder: callers reach
     * this from whatever dispatcher they happen to be on, and two of the three
     * ([RotationEngine.refillDiskBuffer] via `SettingsViewModel`'s `viewModelScope` and via the
     * foreground service's main-dispatcher scope) are on the main thread.
     */
    suspend fun effectiveMode(): WallpaperMode = withContext(Dispatchers.IO) {
        val desired = appDataStore.getWallpaperMode()
        if (desired == WallpaperMode.ATMOSPHERE && isAtmosphereEngineActive()) {
            WallpaperMode.ATMOSPHERE
        } else {
            WallpaperMode.STATIC
        }
    }

    /**
     * Removes any separate lock-screen wallpaper so the engine receives both screens.
     * Safe to call repeatedly; it does nothing when no lock wallpaper is set.
     */
    fun ensureEngineOwnsLockScreen() {
        runCatching {
            val manager = WallpaperManager.getInstance(appContext)
            if (manager.getWallpaperId(WallpaperManager.FLAG_LOCK) > 0) {
                manager.clear(WallpaperManager.FLAG_LOCK)
                Log.i(TAG, "Cleared the separate lock wallpaper; the engine now owns both screens")
            }
        }.onFailure { Log.w(TAG, "Could not clear the lock wallpaper", it) }
    }
}
