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
 * The stored [WallpaperMode] is only the user's *desired* mode; atmosphere is *effective* only while
 * our engine is the system wallpaper. Delivery routing and zoom-fix gating must use [effectiveMode],
 * not the raw preference. Read-only: the desired mode is never flipped to reconcile.
 */
@Singleton
class WallpaperModeResolver @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore
) {
    /** On [Dispatchers.IO] because [WallpaperManager.getWallpaperInfo] is a synchronous call. */
    suspend fun isAtmosphereEngineActive(): Boolean = withContext(Dispatchers.IO) {
        val info = WallpaperManager.getInstance(appContext).wallpaperInfo ?: return@withContext false
        // Class reference rather than a string, so a rename cannot desync it
        info.packageName == appContext.packageName && info.serviceName == AtmosphereWallpaperService::class.java.name
    }

    suspend fun effectiveMode(): WallpaperMode {
        val desired = appDataStore.getWallpaperMode()
        return if (desired == WallpaperMode.ATMOSPHERE && isAtmosphereEngineActive()) {
            WallpaperMode.ATMOSPHERE
        } else {
            WallpaperMode.STATIC
        }
    }
}
