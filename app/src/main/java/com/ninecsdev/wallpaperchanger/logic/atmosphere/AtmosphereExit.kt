package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecord
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [AtmosphereExit.leave], in the order the chain produces them:
 *
 * - [REPLACED]: a wallpaper of the user's own took the engine's place.
 * - [CLEARED]: nothing of the user's was available, so the device's built-in wallpaper did.
 * - [FAILED]: even clearing threw. The mode switch did not happen.
 */
enum class AtmosphereExitOutcome { REPLACED, CLEARED, FAILED }

/**
 * Leaves the atmosphere live wallpaper.
 *
 * Android has no "unset live wallpaper" so leaving is a fallback chain, ordered by how close each
 * step is to what the user is looking at:
 *
 * 1. **The image the engine is showing**, re-rendered with static framing. Which one that is comes
 *    from [WallpaperRecord.liveAtmosphereWallpaperId]
 * 2. **The user's default wallpaper** Applied regardless of the revert-to-default preference.
 * 3. **[WallpaperManager.clear]**, the device's built-in as final fallback.
 *
 * **Every step writes both screens, ignoring the wallpaper-destination preference.** The engine
 * took both on entry so we hand back both on exit.
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

        /** Both screens at once. The engine owns both, so the replacement has to take both. */
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

    /**
     * Re-renders and applies the wallpaper the engine is currently showing.
     *
     * Returns false whenever that image cannot be named or produced.
     */
    private suspend fun applyLiveImage(): Boolean {
        val wallpaperId = wallpaperRecordStore.snapshot().liveAtmosphereWallpaperId ?: return false
        val wallpaper = repository.getWallpaperById(wallpaperId)

        if (wallpaper == null || !wallpaper.isAvailable) {
            Log.i(TAG, "Live wallpaper $wallpaperId is gone or unavailable; falling back.")
            return false
        }
        return applyRendered(wallpaper, cropRuleOf(wallpaper), "the live atmosphere image")
    }

    private suspend fun applyDefault(): Boolean {
        val wallpaper = repository.getDefaultWallpaper() ?: return false
        if (!wallpaper.isAvailable) {
            Log.i(TAG, "The default wallpaper is unavailable; falling back.")
            return false
        }
        return applyRendered(wallpaper, cropRuleOf(wallpaper), "the default wallpaper")
    }

    private suspend fun cropRuleOf(wallpaper: WallpaperImage): CropRule =
        repository.getCollectionById(wallpaper.collectionId)?.defaultCropRule ?: WallpaperCollection.DEFAULT_CROP_RULE

    private suspend fun applyRendered(
        wallpaper: WallpaperImage,
        cropRule: CropRule,
        description: String
    ): Boolean {
        val rendered = bufferManager.renderForStatic(wallpaper, cropRule) ?: return false
        return try {
            setToBothScreens(rendered).also { applied ->
                if (applied) Log.i(TAG, "Left atmosphere by applying $description.")
            }
        } finally {
            rendered.recycle()
        }
    }

    private fun setToBothScreens(bitmap: Bitmap): Boolean = runCatching {
        WallpaperManager.getInstance(appContext).setBitmap(bitmap, null, true, BOTH_SCREENS)
    }.onFailure { Log.e(TAG, "Could not apply the replacement wallpaper", it) }.isSuccess


    private fun clearToBuiltIn(): Boolean = runCatching {
        WallpaperManager.getInstance(appContext).clear()
    }.onFailure { Log.e(TAG, "Could not clear to the built-in wallpaper", it) }.isSuccess
}
