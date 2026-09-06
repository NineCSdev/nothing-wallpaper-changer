package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.content.Context
import android.content.Intent
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.ImageProcessingUtils
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereProtocol
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Atmosphere *delivery*, the final hop of the rotation pipeline when atmosphere mode is effective.
 *
 * **Nothing is rendered or measured here.** The image arrives already screen-fitted and already
 * carrying its palette, and delivery's job is to publish it into the engine's on-disk source and say
 * so. A rotation delivery is a copy and a broadcast.
 *
 * Seeds and pixels travel in **one container**, swapped in with a single rename. Two files could not
 * be swapped atomically, and a reader that got fresh pixels with stale seeds would paint one photo's
 * blobs from another photo's palette.
 */
@Singleton
class AtmosphereDelivery @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val wallpaperRecordStore: WallpaperRecordStore
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

        wallpaperRecordStore.setLiveAtmosphereWallpaperId(delivered.wallpaperId)
        return delivered.collectionId
    }

    /**
     * Publishes the delivery staged by the last refill and broadcasts a rotation reload.
     *
     * Returns false (and logs) when nothing is staged (what a refill ran on static mode leaves).
     * Failing is the point: the buffer it left carries static framing and no palette. The rotation
     * waits for the next refill.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun deliverPrepared(collectionId: Long?): Boolean = withContext(Dispatchers.IO) {
        // Tagged as a rotation delivery: its display, and only its display, advances the magazine
        // and names the live image. Set before publishing so the confirmation can never arrive ahead of it.
        val latched = InFlightDelivery(collectionId, wallpaperRecordStore.snapshot().bufferedWallpaperId)
        try {
            inFlight.set(latched)
            if (!AtmosphereSource.promotePending(appContext.filesDir)) {
                inFlight.compareAndSet(latched, null)
                return@withContext false
            }
            sendReload(fromRotation = true)
            Log.i(TAG, "Delivered the staged atmosphere source (fromRotation=true).")
            true
        } catch (e: Exception) {
            // An escape here would leave the latch set: the next display confirmation would then
            // credit a rotation whose image never reached the engine.
            Log.e(TAG, "Failed to publish the staged delivery to the atmosphere source", e)
            inFlight.compareAndSet(latched, null)
            false
        }
    }

    /**
     * Publishes an already-rendered image and broadcasts a non-rotation reload.
     *
     * The entry point for images that never go through the rotation buffer: the default wallpaper,
     * the revert-to-default path that runs when a collection empties, and the pre-render that
     * happens before the system picker is launched. None of those is a rotation, so none of them
     * may advance the magazine.
     *
     * The caller keeps ownership of `render.bitmap` and is responsible for recycling it.
     *
     * [wallpaperId] is the membership the image was rendered from.
     */
    suspend fun deliverRender(render: AtmosphereRender, wallpaperId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
            // Not a rotation, so it must not leave a delivery to be credited later.
            inFlight.set(null)
            publish(render.seeds, ImageProcessingUtils.compressToBytes(render.bitmap), fromRotation = false)
                .also { published ->
                    if (published) wallpaperRecordStore.setLiveAtmosphereWallpaperId(wallpaperId)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deliver a rendered image", e)
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
        // The staged delivery is the same screen-sized image one step earlier in its life.
        AtmosphereSource.clearPending(appContext.filesDir)
        // Nothing is published anymore, so nothing is live and nothing is in flight
        inFlight.set(null)
        wallpaperRecordStore.setLiveAtmosphereWallpaperId(null)
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
            Intent(AtmosphereProtocol.ACTION_RELOAD)
                .setPackage(appContext.packageName)
                .putExtra(AtmosphereProtocol.EXTRA_FROM_ROTATION, fromRotation)
        )
    }

    private fun publish(
        seeds: List<VertexInfo>,
        imageBytes: ByteArray,
        fromRotation: Boolean
    ): Boolean {
        if (!AtmosphereSource.write(appContext.filesDir, seeds, imageBytes)) return false

        sendReload(fromRotation)
        Log.i(TAG, "Delivered atmosphere source (fromRotation=$fromRotation).")
        return true
    }
}
