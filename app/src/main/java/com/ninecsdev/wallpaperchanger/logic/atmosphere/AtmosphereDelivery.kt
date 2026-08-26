package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.logic.BufferManager
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereWallpaperService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atmosphere *delivery*, the final hop of the rotation pipeline when atmosphere mode is effective.
 *
 * **No rendering happens here.** The image arrives already screen-fitted and delivery's job is
 * to publish it into the engine's on-disk source and say so.
 *
 * What it does add is **seed extraction**. The palette scan and its per-swatch nearest-pixel search
 * cost ~60ms.
 *
 * Seeds and pixels travel in **one container**, swapped in with a single rename. Two files could not
 * be swapped atomically, and a reader that got fresh pixels with stale seeds would paint one photo's
 * blobs from another photo's palette.
 */
@Singleton
class AtmosphereDelivery @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val bufferManager: BufferManager,
    private val seedExtractor: SeedExtractor,
    private val appDataStore: AppDataStore
) {
    private companion object {
        const val TAG = "AtmosphereDelivery"
    }

    /**
     * A rotation delivery the engine has been told about but has not confirmed showing yet.
     *
     * @param collectionId the collection whose rotation may advance once the image is shown.
     * @param wallpaperId the membership behind that image.
     */
    private class InFlightDelivery(val collectionId: Long?, val wallpaperId: Long?)

    /** The rotation delivery in flight to the engine, or null when none is. */
    private val inFlight = AtomicReference<InFlightDelivery?>(null)

    /** Consumes the delivery the engine has just confirmed showing.
     *
     * Records its image as the live one.
     */
    suspend fun confirmDisplayed(): Long? {
        val delivered = inFlight.getAndSet(null) ?: return null

        appDataStore.setAtmosphereLiveWallpaperId(delivered.wallpaperId)
        return delivered.collectionId
    }

    /**
     * Publishes [bufferFile] to the atmosphere source and broadcasts a rotation reload.
     * Returns false (and logs) if the buffer is missing, unreadable, or fails to decode.
     *
     * The buffer is **read, never moved**: the running engine may cold-start and re-read its source
     * at any time, while the buffer file itself keeps getting overwritten by subsequent refills.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun deliverBuffer(bufferFile: File, collectionId: Long?): Boolean = withContext(Dispatchers.IO) {
        if (!bufferFile.exists()) {
            Log.w(TAG, "Buffer file missing; nothing to deliver to atmosphere engine.")
            return@withContext false
        }

        val bytes = try {
            bufferFile.readBytes()
        } catch (e: Exception) {
            Log.e(TAG, "Could not read the buffer", e)
            return@withContext false
        }

        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        if (bitmap == null) {
            Log.w(TAG, "Buffer did not decode; nothing delivered")
            return@withContext false
        }

        // Tagged as a rotation delivery: its display, and only its display, advances the magazine
        // and names the live image. Set before publishing so the confirmation can never arrive ahead of it.
        val latched = InFlightDelivery(collectionId, appDataStore.getBufferedWallpaperId())
        try {
            inFlight.set(latched)
            publish(bytes, bitmap, fromRotation = true).also { published ->
                if (!published) inFlight.compareAndSet(latched, null)
            }
        } catch (e: Exception) {
            // Seed extraction and the source writer's own contract checks can throw, and an escape
            // here would leave the latch set: the next display confirmation would then credit a
            // rotation whose image never reached the engine.
            Log.e(TAG, "Failed to publish the buffer to the atmosphere source", e)
            inFlight.compareAndSet(latched, null)
            false
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Publishes an already-rendered [bitmap] and broadcasts a non-rotation reload.
     *
     * The entry point for images that never go through the rotation buffer: the default wallpaper,
     * the revert-to-default path that runs when a collection empties, and the pre-render that
     * happens before the system picker is launched. None of those is a rotation, so none of them
     * may advance the magazine.
     *
     * The caller keeps ownership of [bitmap] and is responsible for recycling it.
     *
     * [wallpaperId] is the membership [bitmap] was rendered from. Null for images that belong to
     * no collection (the default wallpaper).
     */
    suspend fun deliverBitmap(bitmap: Bitmap, wallpaperId: Long? = null): Boolean = withContext(Dispatchers.IO) {
        try {
            // Not a rotation, so it must not leave a delivery to be credited later.
            inFlight.set(null)
            publish(ImageProcessingUtils.compressToBytes(bitmap), bitmap, fromRotation = false)
                .also { published ->
                    if (published) appDataStore.setAtmosphereLiveWallpaperId(wallpaperId)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver a rendered bitmap", e)
            false
        }
    }

    /**
     * Deletes the on-disk source once the engine is confirmed gone (the user left atmosphere mode
     * and a static wallpaper actually replaced the live one). It is a screen-sized image that would
     * otherwise sit in `filesDir` indefinitely; re-entering atmosphere re-renders it.
     *
     * Only call after confirming the engine is no longer the system wallpaper — a live engine
     * cold-starts by re-reading this file, so deleting it out from under one shows black.
     */
    suspend fun clearSource() {
        val source = AtmosphereSource.file(appContext.filesDir)
        if (source.exists() && !source.delete()) {
            Log.w(TAG, "Could not delete the atmosphere source file.")
        }
        // Nothing is published anymore, so nothing is live and nothing is in flight
        inFlight.set(null)
        appDataStore.setAtmosphereLiveWallpaperId(null)
    }

    /**
     * Signals the live engine to re-decode its on-disk source.
     *
     * The file is the source of truth, because the system starts the engine at boot
     * and recreates it on surface changes, both of which can happen with this process gone.
     *
     * [fromRotation] marks whether the reload is a rotation delivery that should advance the magazine
     */
    private fun sendReload(fromRotation: Boolean) {
        appContext.sendBroadcast(
            Intent(AtmosphereWallpaperService.ACTION_RELOAD)
                .setPackage(appContext.packageName)
                .putExtra(AtmosphereWallpaperService.EXTRA_FROM_ROTATION, fromRotation)
        )
    }

    private suspend fun publish(
        imageBytes: ByteArray,
        bitmap: Bitmap,
        fromRotation: Boolean
    ): Boolean {
        // Measured off the bitmap rather than read from settings
        val seeds = seedExtractor.extract(bitmap, bufferManager.hasZoomFixPadding(bitmap))

        if (!AtmosphereSource.write(appContext.filesDir, seeds, imageBytes)) return false

        sendReload(fromRotation)
        Log.i(TAG, "Delivered atmosphere source (fromRotation=$fromRotation).")
        return true
    }
}
