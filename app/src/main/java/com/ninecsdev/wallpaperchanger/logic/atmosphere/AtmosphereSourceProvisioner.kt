package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
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

        val (wallpaper, cropRule) = resolved
        val rendered = bufferManager.renderFramed(wallpaper, cropRule, WallpaperMode.ATMOSPHERE)
            ?: return false
        return try {
            // deliverBitmap writes the container and broadcasts the reload itself. The id lets
            // a later exit give this exact photo back; the default wallpaper carries none (id 0).
            atmosphereDelivery.deliverBitmap(rendered, wallpaper.id.takeIf { it != 0L })
        } finally {
            rendered.recycle()
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

        return WallpaperImage.forDefaultWallpaper(defaultUri) to CropRule.FIT
    }
}
