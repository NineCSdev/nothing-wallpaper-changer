package com.ninecsdev.wallpaperchanger.logic

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereDelivery
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Named for when the image lands, so callers never need to know the modes. [SHOWN]: on screen now,
 * advance the rotation. [DEFERRED]: shown later or held, do NOT advance until the delivery channel
 * confirms. [ALREADY_LIVE]: that image is already on screen; advancing would burn the image being prepared.
 */
enum class WallpaperApplyOutcome { SHOWN, DEFERRED, ALREADY_LIVE, FAILED }

/**
 * Mode-aware router. In effective atmosphere mode it must never call setBitmap/setStream: that would
 * replace the live wallpaper and end the mode. Effective mode is resolved on every call, so a stale
 * ATMOSPHERE desire with the engine unset still takes the static path.
 */
@Singleton
class WallpaperApplier @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val repository: WallpaperRepository,
    private val bufferManager: BufferManager,
    private val wallpaperModeResolver: WallpaperModeResolver,
    private val atmosphereDelivery: AtmosphereDelivery
) {
    private companion object {
        const val TAG = "WallpaperApplier"
    }

    /**
     * The desired-but-not-effective mismatch is legitimate, but it is also what a stale or failed
     * liveness read looks like, in which case the static write silently evicts our live wallpaper.
     * The two are indistinguishable from inside, so this logs.
     */
    private suspend fun warnIfDesiringAtmosphere(path: String) {
        if (appDataStore.getWallpaperMode() != WallpaperMode.ATMOSPHERE) return

        Log.w(
            TAG,
            "$path: atmosphere is the desired mode but the engine does not read as the system " +
                "wallpaper, so this is taking the static path. If the engine was in fact live, " +
                "this write has just replaced it."
        )
    }

    /** [useCollectionOverride] lets the active collection's default win over the global (service stop/pause). */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun applyDefaultWallpaper(useCollectionOverride: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val defaultWallpaper = (if (useCollectionOverride) repository.getCollectionDefaultOrGlobal() else repository.getDefaultWallpaper()) ?: return@withContext false
        if (!defaultWallpaper.isAvailable) return@withContext false

        val cropRule = repository.cropRuleFor(defaultWallpaper)

        if (wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE) {
            // Goes through the engine: setBitmap would evict it and end the mode
            val render = bufferManager.renderForAtmosphere(defaultWallpaper, cropRule) ?: return@withContext false

            return@withContext try {
                atmosphereDelivery.deliverRender(render, defaultWallpaper.id).also { delivered ->
                    if (delivered) Log.i(TAG, "Applied default wallpaper through atmosphere source.")
                }
            } finally {
                render.bitmap.recycle()
            }
        }

        warnIfDesiringAtmosphere("applyDefaultWallpaper")
        val destination = appDataStore.getWallpaperDestination()

        val rendered = bufferManager.renderForStatic(defaultWallpaper, cropRule) ?: return@withContext false

        return@withContext try {
            WallpaperManager.getInstance(appContext).setBitmap(
                rendered,
                null,
                true,
                destination.toFlags()
            )

            wallpaperRecordStore.setAppliedWallpaperId(defaultWallpaper.id)

            Log.i(TAG, "Successfully applied default wallpaper to $destination.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply default wallpaper to $destination", e)
            false
        } finally {
            rendered.recycle()
        }
    }

    /** [rotatingCollectionId] is the collection the buffered image was drawn from, when this apply is a rotation. */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun applyBufferWallpaper(
        rotatingCollectionId: Long? = null
    ): WallpaperApplyOutcome = withContext(Dispatchers.IO) {
        if (wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE) {
            // Keyed on ids rather than on timing: an unknown buffer id (null, written while a refill
            // is in flight) never matches, so this can never decide to sit out forever.
            val record = wallpaperRecordStore.snapshot()
            val bufferedId = record.bufferedWallpaperId
            if (bufferedId != null && bufferedId == record.liveAtmosphereWallpaperId) {
                Log.i(TAG, "Buffered image $bufferedId is already live; not delivering or advancing.")
                return@withContext WallpaperApplyOutcome.ALREADY_LIVE
            }

            // A copy plus a reload broadcast, never setStream (that would replace the live wallpaper).
            // Shown later or held, hence DEFERRED
            val delivered = atmosphereDelivery.deliverPrepared(rotatingCollectionId)
            return@withContext if (delivered) {
                WallpaperApplyOutcome.DEFERRED
            } else {
                WallpaperApplyOutcome.FAILED
            }
        }

        warnIfDesiringAtmosphere("applyBufferWallpaper")
        val destination = appDataStore.getWallpaperDestination()
        try {
            val bufferedId = wallpaperRecordStore.snapshot().bufferedWallpaperId
            val prepared = bufferManager.openPrepared() ?: return@withContext WallpaperApplyOutcome.FAILED

            // The platform write and our record of it are one step
            withContext(NonCancellable) {
                prepared.use { stream ->
                    WallpaperManager.getInstance(appContext).setStream(
                        stream,
                        null,
                        true,
                        destination.toFlags()
                    )
                }

                wallpaperRecordStore.setAppliedWallpaperId(bufferedId)
            }

            Log.i(TAG, "Wallpaper applied successfully from the prepared image to $destination.")
            WallpaperApplyOutcome.SHOWN
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream the prepared image to $destination", e)
            WallpaperApplyOutcome.FAILED
        }
    }

    private fun WallpaperDestination.toFlags(): Int = when (this) {
        WallpaperDestination.LOCK -> WallpaperManager.FLAG_LOCK
        WallpaperDestination.HOME -> WallpaperManager.FLAG_SYSTEM
        // As WallpaperManager checks the bits we do an OR to activate both (01 or 10 = 11)
        WallpaperDestination.BOTH -> WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM
    }
}
