package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.app.WallpaperManager
import android.content.Context
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
 * mode. Atmosphere is *effective* solely when our live wallpaper engine is the system wallpaper
 * (the user may have picked ATMOSPHERE but never confirmed it on the system picker, or replaced it
 * later with another launcher/wallpaper).
 * Both delivery routing and zoom-fix gating must consult [effectiveMode] rather than the raw preference.
 *
 * The desired setting is **never silently flipped** to reconcile a mismatch; the UI surfaces the
 * mismatch instead (a "not set yet" prompt in Settings) so the user's intent is preserved.
 *
 * Read-only by design.
 */
@Singleton
class WallpaperModeResolver @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore
) {
    /**
     * True when [AtmosphereWallpaperService] is the currently-set system live wallpaper.
     * A live wallpaper reports its component via [WallpaperManager.getWallpaperInfo]; a static
     * wallpaper (or another app's live wallpaper) yields null / a different component.
     *
     * On [Dispatchers.IO] because [WallpaperManager.getWallpaperInfo] is a synchronous call.
     */
    suspend fun isAtmosphereEngineActive(): Boolean = withContext(Dispatchers.IO) {
        val info = WallpaperManager.getInstance(appContext).wallpaperInfo ?: return@withContext false
        // The direct class reference is deliberate. This is not the app talking to the engine, it is the
        // app asking the platform which wallpaper is live, so not part of AtmosphereProtocol.kt
        info.packageName == appContext.packageName &&
            info.serviceName == AtmosphereWallpaperService::class.java.name
    }

    /**
     * ATMOSPHERE only when the user desires it **and** the engine is actually live; STATIC otherwise
     * (including the "desired atmosphere but not confirmed on the system" mismatch case).
     */
    suspend fun effectiveMode(): WallpaperMode {
        val desired = appDataStore.getWallpaperMode()
        return if (desired == WallpaperMode.ATMOSPHERE && isAtmosphereEngineActive()) {
            WallpaperMode.ATMOSPHERE
        } else {
            WallpaperMode.STATIC
        }
    }
}
