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
 * Prefers the wallpaper already on screen, falls back to the active collection's
 * first available image, then to the user's default wallpaper.
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
        val live = wallpaperById(liveId) ?: run {
            Log.i(TAG, "Live image $liveId is gone or unavailable; picking a source instead.")
            return provision()
        }
        return deliver(live.first, live.second)
    }

    private suspend fun deliver(wallpaper: WallpaperImage, cropRule: CropRule): Boolean {
        val render = bufferManager.renderForAtmosphere(wallpaper, cropRule) ?: return false
        return try {
            // deliverRender writes the container and broadcasts the reload itself. The id lets
            // a later exit give this exact photo back.
            atmosphereDelivery.deliverRender(render, wallpaper.id)
        } finally {
            render.bitmap.recycle()
        }
    }

    /**
     * Picks the image + crop rule to feed the atmosphere renderer, in the order of how close each
     * candidate is to what the user is looking at.
     *
     * 1. **The wallpaper on screen** ([AppDataStore.getAppliedWallpaperId]). Not restricted to the
     *    active collection: if the user switched collections the image on screen still belongs to the
     *    old one. Rendered with *its own* membership's crop rule and edits (how it was framed).
     * 2. **The active collection's first available image**, on fresh install, a service that has never run,
     *    or a revert to the default wallpaper.
     * 3. **The user's default wallpaper.**
     *
     * Reuses [WallpaperRepository.activeCollectionImagesFlow] for case 2.
     */
    private suspend fun resolveSource(): Pair<WallpaperImage, CropRule>? {
        onScreenWallpaper()?.let { return it }

        val activeSnapshot = repository.activeCollectionImagesFlow().first()
        val firstAvailable = activeSnapshot?.second?.firstOrNull()
        if (activeSnapshot != null && firstAvailable != null) {
            return firstAvailable to activeSnapshot.first.defaultCropRule
        }
        val default = repository.getDefaultWallpaper() ?: return null
        if (!default.isAvailable) return null

        return default to cropRuleOf(default)
    }

    /** The membership currently applied as the static wallpaper, if it is still usable. */
    private suspend fun onScreenWallpaper(): Pair<WallpaperImage, CropRule>? {
        val appliedId = appDataStore.getAppliedWallpaperId() ?: return null
        return wallpaperById(appliedId)
    }

    /** Looks up a membership and the crop rule of the collection it belongs to. */
    private suspend fun wallpaperById(wallpaperId: Long): Pair<WallpaperImage, CropRule>? {
        val wallpaper = repository.getWallpaperById(wallpaperId) ?: return null
        if (!wallpaper.isAvailable) return null
        return wallpaper to cropRuleOf(wallpaper)
    }

    private suspend fun cropRuleOf(wallpaper: WallpaperImage): CropRule =
        repository.getCollectionById(wallpaper.collectionId)?.defaultCropRule ?: WallpaperCollection.DEFAULT_CROP_RULE
}
