package com.ninecsdev.wallpaperchanger.logic

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.WallpaperRepository
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereDelivery
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.WallpaperCollection
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Outcome of [WallpaperApplier.applyBufferWallpaper]. The distinction the caller acts on is
 * *when the image becomes visible*:
 *
 * - [SHOWN]: on screen as of this call (the static path's setStream returned) — the caller
 *   advances the rotation now.
 * - [DEFERRED]: accepted but shown later, or held and never shown — the caller must NOT advance
 *   now and waits for the delivery channel's own confirmation. As of writing that is the atmosphere engine
 *   reporting back via [AtmosphereProtocol.ACTION_DISPLAYED][com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereProtocol].
 * - [ALREADY_LIVE]: the buffered image is the one already on screen, so nothing was applied and
 *   the caller must NOT advance — advancing would re-show it and burn the image being prepared.
 * - [FAILED]: nothing was applied or delivered.
 *
 * Named for *when the image lands*, not for which mode produced it, so a caller deciding whether
 * to advance the rotation never has to know the modes exist.
 */
enum class WallpaperApplyOutcome { SHOWN, DEFERRED, ALREADY_LIVE, FAILED }

/**
 * Applies prepared images to the Android screen wallpaper.
 *
 * Mode-aware router: in static mode it writes the bitmap/stream straight to [WallpaperManager]; in
 * effective atmosphere mode it instead publishes into the live engine's on-disk source
 * ([AtmosphereDelivery]) and **never** calls setBitmap/setStream — doing so would replace the live
 * wallpaper and kick the user out of atmosphere mode. Effective mode (not just the desired setting)
 * is resolved via [WallpaperModeResolver] at every call, so a stale ATMOSPHERE desire with the
 * engine no longer set still routes through the normal static path.
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
     * Warns when a static write is about to happen while the user still wants atmosphere.
     *
     * That combination is legitimate, it is the documented mismatch state, where the mode was
     * chosen but never confirmed on the system picker — but it is also what a *stale or failed*
     * engine-liveness read looks like, and in that case the write below replaces our own live
     * wallpaper and silently drops the user out of the mode. The two are indistinguishable from
     * inside, so the least this path can do is say so.
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

    /**
     * @param useCollectionOverride lets the active collection's default wallpaper win over the
     * global. For the service stop/pause.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun applyDefaultWallpaper(useCollectionOverride: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        val defaultWallpaper = (if (useCollectionOverride) repository.getCollectionDefaultOrGlobal() else repository.getDefaultWallpaper()) ?: return@withContext false
        if (!defaultWallpaper.isAvailable) return@withContext false

        val cropRule = repository.getCollectionById(defaultWallpaper.collectionId)?.defaultCropRule ?: WallpaperCollection.DEFAULT_CROP_RULE

        if (wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE) {
            // Revert-to-default flows through the atmosphere path: render the default and hand it
            // to the engine instead of setBitmap, which would evict the engine and end the mode.
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

    /**
     * @param rotatingCollectionId the collection the buffered image was drawn from, when this apply
     * is a rotation.
     */
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

            // Delivery is a cheap copy of the already-rendered buffer into the engine's source file
            // plus a reload broadcast — no setStream, which would replace the live wallpaper. The
            // image is shown later (or held), so the caller defers the rotation advance until the
            // engine confirms display; hence DEFERRED rather than SHOWN.
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

                // Records what the user is now looking at
                wallpaperRecordStore.setAppliedWallpaperId(bufferedId)
            }

            Log.i(TAG, "Wallpaper applied successfully from the prepared image to $destination.")
            WallpaperApplyOutcome.SHOWN
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream the prepared image to $destination", e)
            WallpaperApplyOutcome.FAILED
        }
    }

    /**
     * Extension helper to translate [WallpaperDestination] to [WallpaperManager.FLAG_LOCK] or
     * [WallpaperManager.FLAG_SYSTEM]
     */
    private fun WallpaperDestination.toFlags(): Int = when (this) {
        WallpaperDestination.LOCK -> WallpaperManager.FLAG_LOCK
        WallpaperDestination.HOME -> WallpaperManager.FLAG_SYSTEM
        // As WallpaperManager checks the bits we do an or to activate both (01 or 10 = 11)
        WallpaperDestination.BOTH -> WallpaperManager.FLAG_LOCK or WallpaperManager.FLAG_SYSTEM
    }
}
