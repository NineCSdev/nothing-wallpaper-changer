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
 * Publishes an already-rendered image and its seeds into the engine's on-disk source and tells the
 * engine to reload. Seeds and pixels travel in one container, so a reader never pairs fresh pixels
 * with another photo's palette.
 */
@Singleton
class AtmosphereDelivery @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val wallpaperRecordStore: WallpaperRecordStore
) {
    private companion object {
        const val TAG = "AtmosphereDelivery"
    }

    /** A rotation delivery the engine has been told about but has not confirmed showing yet. */
    private class InFlightDelivery(val collectionId: Long?, val wallpaperId: Long?)

    private val inFlight = AtomicReference<InFlightDelivery?>(null)

    /** Consumes the in-flight delivery the engine has just confirmed showing and records its image as live. */
    suspend fun confirmDisplayed(): Long? {
        val delivered = inFlight.getAndSet(null) ?: return null

        wallpaperRecordStore.setLiveAtmosphereWallpaperId(delivered.wallpaperId)
        return delivered.collectionId
    }

    /**
     * Publishes the delivery staged by the last refill and broadcasts a rotation reload.
     * Returns false when nothing is staged (a static-mode refill leaves a buffer with static framing
     * and no palette); the rotation then waits for the next refill.
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md
    suspend fun deliverPrepared(collectionId: Long?): Boolean = withContext(Dispatchers.IO) {
        // Set before publishing so the display confirmation can never arrive ahead of it
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
            // A latch left set would credit the next display confirmation to an image the engine never got
            Log.e(TAG, "Failed to publish the staged delivery to the atmosphere source", e)
            inFlight.compareAndSet(latched, null)
            false
        }
    }

    /**
     * Publishes the staged delivery for a collection switch. The engine adopts it on the next frame
     * instead of holding it for a dark panel, which makes it live once published.
     */
    // TODO tests: see vault note tests/Collection Switch Rotation Gate Tests.md
    suspend fun deliverPreparedNow(): Boolean = withContext(Dispatchers.IO) {
        try {
            // A rotation the engine was still holding is replaced by this one, so its confirmation will never come
            inFlight.set(null)
            val bufferedId = wallpaperRecordStore.snapshot().bufferedWallpaperId
            if (!AtmosphereSource.promotePending(appContext.filesDir)) return@withContext false

            sendReload(fromRotation = false)
            wallpaperRecordStore.setLiveAtmosphereWallpaperId(bufferedId)
            Log.i(TAG, "Delivered the staged atmosphere source for a collection switch.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to publish the staged delivery for a collection switch", e)
            false
        }
    }

    /**
     * For images outside the rotation buffer (default wallpaper, revert on an emptied collection,
     * pre-render before the picker). Never a rotation, so never advances the magazine.
     * The caller keeps ownership of `render.bitmap`; [wallpaperId] is the membership it came from.
     */
    suspend fun deliverRender(render: AtmosphereRender, wallpaperId: Long): Boolean = withContext(Dispatchers.IO) {
        try {
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
     * Only call after confirming the engine is no longer the system wallpaper: a live engine
     * cold-starts by re-reading the source, so deleting it from under one shows black.
     */
    suspend fun clearSource() {
        val source = AtmosphereSource.file(appContext.filesDir)
        if (source.exists() && !source.delete()) {
            Log.w(TAG, "Could not delete the atmosphere source file.")
        }
        AtmosphereSource.clearPending(appContext.filesDir)
        inFlight.set(null)
        wallpaperRecordStore.setLiveAtmosphereWallpaperId(null)
    }

    /**
     * The on-disk source is the source of truth: the system starts the engine at boot and recreates
     * it on surface changes, possibly with this process gone. [fromRotation] reloads advance the magazine.
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
