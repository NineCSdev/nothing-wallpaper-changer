package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecord
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** [FAILED] means even clearing threw: the mode switch did not happen. */
enum class AtmosphereExitOutcome { REPLACED, CLEARED, FAILED }

/**
 * Android has no "unset live wallpaper", so leaving is a fallback chain: the image the engine is
 * showing ([WallpaperRecord.liveAtmosphereWallpaperId]) re-rendered with static framing, then the
 * default wallpaper (regardless of the revert-to-default preference), then [WallpaperManager.clear].
 * Every step writes both screens, ignoring the destination preference, since the engine took both.
 */
// TODO tests: see vault note tests/Atmosphere Delivery Tests.md (exit chain)
@Singleton
class AtmosphereExit @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val repository: WallpaperRepository,
    private val bufferManager: BufferManager
) {
    private companion object {
        const val TAG = "AtmosphereExit"

        const val BOTH_SCREENS = WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
    }

    suspend fun leave(): AtmosphereExitOutcome = withContext(Dispatchers.IO) {
        if (applyLiveImage()) return@withContext AtmosphereExitOutcome.REPLACED
        if (applyDefault()) return@withContext AtmosphereExitOutcome.REPLACED

        if (clearToBuiltIn()) {
            Log.i(TAG, "Left atmosphere by clearing to the built-in wallpaper.")
            AtmosphereExitOutcome.CLEARED
        } else {
            AtmosphereExitOutcome.FAILED
        }
    }

    private suspend fun applyLiveImage(): Boolean {
        val wallpaperId = wallpaperRecordStore.snapshot().liveAtmosphereWallpaperId ?: return false
        val wallpaper = repository.getWallpaperById(wallpaperId)

        if (wallpaper == null || !wallpaper.isAvailable) {
            Log.i(TAG, "Live wallpaper $wallpaperId is gone or unavailable; falling back.")
            return false
        }
        return applyRendered(wallpaper, repository.cropRuleFor(wallpaper), "the live atmosphere image")
    }

    private suspend fun applyDefault(): Boolean {
        val wallpaper = repository.getDefaultWallpaper() ?: return false
        if (!wallpaper.isAvailable) {
            Log.i(TAG, "The default wallpaper is unavailable; falling back.")
            return false
        }
        return applyRendered(wallpaper, repository.cropRuleFor(wallpaper), "the default wallpaper")
    }

    private suspend fun applyRendered(
        wallpaper: WallpaperImage,
        cropRule: CropRule,
        description: String
    ): Boolean {
        val rendered = bufferManager.renderForStatic(wallpaper, cropRule) ?: return false
        return try {
            setToBothScreens(rendered).also { applied ->
                if (applied) {
                    wallpaperRecordStore.setAppliedWallpaperId(wallpaper.id)
                    Log.i(TAG, "Left atmosphere by applying $description.")
                }
            }
        } finally {
            rendered.recycle()
        }
    }

    private fun setToBothScreens(bitmap: Bitmap): Boolean = runCatching {
        WallpaperManager.getInstance(appContext).setBitmap(bitmap, null, true, BOTH_SCREENS)
    }.onFailure { Log.e(TAG, "Could not apply the replacement wallpaper", it) }.isSuccess


    private suspend fun clearToBuiltIn(): Boolean = runCatching {
        WallpaperManager.getInstance(appContext).clear()
    }.onFailure { Log.e(TAG, "Could not clear to the built-in wallpaper", it) }
        .isSuccess
        .also { cleared -> if (cleared) wallpaperRecordStore.setAppliedWallpaperId(null) }
}
