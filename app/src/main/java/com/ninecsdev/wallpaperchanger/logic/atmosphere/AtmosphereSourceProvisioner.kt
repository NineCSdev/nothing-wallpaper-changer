package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders the image the atmosphere engine should be showing, and delivers it.
 *
 * Picks the active collection's first available image, falling back to the user's default
 * wallpaper.
 */
@Singleton
class AtmosphereSourceProvisioner @Inject constructor(
    private val repository: WallpaperRepository,
    private val appDataStore: AppDataStore,
    private val bufferManager: BufferManager,
    private val atmosphereDelivery: AtmosphereDelivery
) {
    private companion object {
        const val TAG = "AtmosphereProvisioner"
    }

    /**
     * Renders the atmosphere source image and delivers it, returning true once it is on disk and
     * the engine has been told about it.
     *
     * **Never gate this on the desired [WallpaperMode][com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode].**
     * The stored mode says what the user wants the *rotation* to do; not which wallpaper is on screen right now.
     *
     * Returns 'false' if there is nothing to deliver.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (no-source recovery)
    suspend fun provision(): Boolean {
        val resolved = resolveSource()
        if (resolved == null) {
            Log.i(TAG, "No collection image and no default wallpaper; nothing to provision")
            return false
        }
        return deliver(resolved.first, resolved.second)
    }

    /**
     * Re-renders the image the engine is **already showing**, rather than picking one.
     *
     * Falls back to [provision] when nothing is live, or when the live image has gone away.
     */
    suspend fun reprovisionLive(): Boolean {
        val liveId = appDataStore.getAtmosphereLiveWallpaperId() ?: return provision()
        val wallpaper = repository.getWallpaperById(liveId)
        if (wallpaper == null || !wallpaper.isAvailable) {
            Log.i(TAG, "Live image $liveId is gone or unavailable; picking a source instead.")
            return provision()
        }
        val cropRule = repository.getCollectionById(wallpaper.collectionId)?.defaultCropRule
            ?: WallpaperCollection.DEFAULT_CROP_RULE
        return deliver(wallpaper, cropRule)
    }

    private suspend fun deliver(wallpaper: WallpaperImage, cropRule: CropRule): Boolean {
        val render = bufferManager.renderForAtmosphere(wallpaper, cropRule) ?: return false
        return try {
            // deliverRender writes the container and broadcasts the reload itself. The id lets
            // a later exit give this exact photo back; the default wallpaper carries none (id 0).
            atmosphereDelivery.deliverRender(render, wallpaper.id.takeIf { it != 0L })
        } finally {
            render.bitmap.recycle()
        }
    }

    /**
     * Picks the image + crop rule to feed the atmosphere renderer. Reuses
     * [WallpaperRepository.activeCollectionImagesFlow] for the active-collection case.
     */
    private suspend fun resolveSource(): Pair<WallpaperImage, CropRule>? {
        val activeSnapshot = repository.activeCollectionImagesFlow().first()
        val firstAvailable = activeSnapshot?.second?.firstOrNull()
        if (activeSnapshot != null && firstAvailable != null) {
            return firstAvailable to activeSnapshot.first.defaultCropRule
        }
        val defaultUri = appDataStore.getDefaultWallpaperUri() ?: return null

        return WallpaperImage.forDefaultWallpaper(defaultUri) to WallpaperImage.DEFAULT_WALLPAPER_CROP_RULE
    }
}
