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
 * mode. Atmosphere is *effective* when our live wallpaper engine is the system wallpaper
 * (user may have never confirmed on the system picker or replaced it with another launcher/wallpaper).
 * Both delivery routing and zoom-fix gating must consult [effectiveMode] rather than the raw preference.
 *
 * Desired is **never flipped** to reconcile, UI surfaces it instead so the user's intent is preserved.
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
     * On [Dispatchers.IO] because [WallpaperManager.getWallpaperInfo] is a synchronous call.
     */
    suspend fun isAtmosphereEngineActive(): Boolean = withContext(Dispatchers.IO) {
        val info = WallpaperManager.getInstance(appContext).wallpaperInfo ?: return@withContext false
        // The direct class reference is deliberate
        info.packageName == appContext.packageName && info.serviceName == AtmosphereWallpaperService::class.java.name
    }

    /** [WallpaperMode.ATMOSPHERE] only when the user desires it **and** the engine is actually live. [WallpaperMode.STATIC] otherwise */
    suspend fun effectiveMode(): WallpaperMode {
        val desired = appDataStore.getWallpaperMode()
        return if (desired == WallpaperMode.ATMOSPHERE && isAtmosphereEngineActive()) {
            WallpaperMode.ATMOSPHERE
        } else {
            WallpaperMode.STATIC
        }
    }
}
