package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecord
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/** Renders the image the atmosphere engine should be showing, and delivers it. */
@Singleton
class AtmosphereSourceProvisioner @Inject constructor(
    private val repository: WallpaperRepository,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val bufferManager: BufferManager,
    private val atmosphereDelivery: AtmosphereDelivery
) {
    private companion object {
        const val TAG = "AtmosphereProvisioner"
    }

    /**
     * Never gate this on the desired wallpaper mode: it says what the user wants the *rotation* to
     * do, not which wallpaper is on screen. Returns false if there is nothing to deliver.
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

    /** Re-renders the image the engine is already showing; falls back to [provision] if there is none. */
    suspend fun reprovisionLive(): Boolean {
        val liveId = wallpaperRecordStore.snapshot().liveAtmosphereWallpaperId ?: return provision()
        val live = wallpaperById(liveId) ?: run {
            Log.i(TAG, "Live image $liveId is gone or unavailable; picking a source instead.")
            return provision()
        }
        return deliver(live.first, live.second)
    }

    private suspend fun deliver(wallpaper: WallpaperImage, cropRule: CropRule): Boolean {
        val render = bufferManager.renderForAtmosphere(wallpaper, cropRule) ?: return false
        return try {
            // The id lets a later exit give this exact photo back
            atmosphereDelivery.deliverRender(render, wallpaper.id)
        } finally {
            render.bitmap.recycle()
        }
    }

    /**
     * Order: the wallpaper on screen ([WallpaperRecord.appliedWallpaperId], which may belong to a
     * previously active collection and keeps its own crop rule and edits), the active collection's
     * first available image, then the default wallpaper.
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

        return default to repository.cropRuleFor(default)
    }

    private suspend fun onScreenWallpaper(): Pair<WallpaperImage, CropRule>? {
        val appliedId = wallpaperRecordStore.snapshot().appliedWallpaperId ?: return null
        return wallpaperById(appliedId)
    }

    private suspend fun wallpaperById(wallpaperId: Long): Pair<WallpaperImage, CropRule>? {
        val wallpaper = repository.getWallpaperById(wallpaperId) ?: return null
        if (!wallpaper.isAvailable) return null
        return wallpaper to repository.cropRuleFor(wallpaper)
    }
}
