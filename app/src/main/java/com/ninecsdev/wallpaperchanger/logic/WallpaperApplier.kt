package com.ninecsdev.wallpaperchanger.logic

import android.app.WallpaperManager
import android.content.Context
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.AtmosphereDelivery
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperDestination
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
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
 *   reporting back via [AtmosphereWallpaperService.ACTION_DISPLAYED][com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService].
 * - [FAILED]: nothing was applied or delivered.
 *
 * Named for *when the image lands*, not for which mode produced it, so a caller deciding whether
 * to advance the rotation never has to know the modes exist.
 */
enum class WallpaperApplyOutcome { SHOWN, DEFERRED, FAILED }

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

    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun applyDefaultWallpaper(): Boolean = withContext(Dispatchers.IO) {
        val uri = appDataStore.getDefaultWallpaperUri() ?: return@withContext false

        if (wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE) {
            // Revert-to-default flows through the atmosphere path: render the default and hand it
            // to the engine instead of setBitmap, which would evict the engine and end the mode.
            val rendered = bufferManager.renderForAtmosphere(
                WallpaperImage.forDefaultWallpaper(uri),
                CropRule.FIT
            ) ?: return@withContext false

            return@withContext try {
                atmosphereDelivery.deliverBitmap(rendered).also { delivered ->
                    if (delivered) Log.i(TAG, "Applied default wallpaper through atmosphere source.")
                }
            } finally {
                rendered.recycle()
            }
        }

        warnIfDesiringAtmosphere("applyDefaultWallpaper")
        val destination = appDataStore.getWallpaperDestination()

        try {
            val (screenW, screenH) = ImageProcessingUtils.getScreenDimensions(appContext)
            val bitmap = ImageProcessingUtils.decodeSampledBitmap(appContext, uri, screenW * 2, screenH * 2)
                ?: return@withContext false
            var processed = bitmap

            try {
                processed = bufferManager.applyZoomFixIfNeeded(bitmap)
                WallpaperManager.getInstance(appContext).setBitmap(
                    processed,
                    null,
                    true,
                    destination.toFlags()
                )

                Log.i(TAG, "Successfully applied default wallpaper to $destination.")
                true
            } finally {
                if (processed !== bitmap) processed.recycle()
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to apply default wallpaper to $destination", e)
            false
        }
    }

    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun applyBufferWallpaper(): WallpaperApplyOutcome = withContext(Dispatchers.IO) {
        if (wallpaperModeResolver.effectiveMode() == WallpaperMode.ATMOSPHERE) {
            // Delivery is a cheap copy of the already-rendered buffer into the engine's source file
            // plus a reload broadcast — no setStream, which would replace the live wallpaper. The
            // image is shown later (or held), so the caller defers the rotation advance until the
            // engine confirms display; hence DEFERRED rather than SHOWN.
            return@withContext if (atmosphereDelivery.deliverBuffer(bufferManager.getBufferFile())) {
                WallpaperApplyOutcome.DEFERRED
            } else {
                WallpaperApplyOutcome.FAILED
            }
        }

        warnIfDesiringAtmosphere("applyBufferWallpaper")
        val destination = appDataStore.getWallpaperDestination()
        try {
            val bufferFile = bufferManager.getBufferFile()

            if (!bufferFile.exists()) {
                Log.w(TAG, "Buffer file missing. Is the service initialized?")
                return@withContext WallpaperApplyOutcome.FAILED
            }

            bufferFile.inputStream().use { stream ->
                WallpaperManager.getInstance(appContext).setStream(
                    stream,
                    null,
                    true,
                    destination.toFlags()
                )
            }

            Log.i(TAG, "Wallpaper applied successfully from disk buffer to $destination.")
            WallpaperApplyOutcome.SHOWN
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stream buffer to $destination", e)
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
