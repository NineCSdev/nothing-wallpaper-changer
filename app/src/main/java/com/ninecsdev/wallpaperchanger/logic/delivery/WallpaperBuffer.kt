package com.ninecsdev.wallpaperchanger.logic.delivery

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.ninecsdev.wallpaperchanger.data.local.WallpaperRecordStore
import com.ninecsdev.wallpaperchanger.logic.atmosphere.WallpaperModeResolver
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import com.ninecsdev.wallpaperchanger.logic.render.WallpaperRenderer
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperMode
import com.ninecsdev.wallpaperchanger.util.Bitmaps
import com.ninecsdev.wallpaperchanger.util.writeAtomically
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed class BufferPreparationResult {
    object Success : BufferPreparationResult()

    /**
     * [definitive]: the source is provably gone (deleted, permission revoked), so the file is marked
     * unavailable; otherwise the image is just skipped this rotation.
     */
    data class Failure(val definitive: Boolean) : BufferPreparationResult()
}

/**
 * Owns what is prepared for the next rotation. Only one shape exists at a time (a WebP buffer for
 * static, a staged container with seeds for atmosphere), so which file exists records which mode
 * prepared it.
 */
@Singleton
class WallpaperBuffer @Inject constructor(
    @param:ApplicationContext private val appContext: Context,
    private val wallpaperRecordStore: WallpaperRecordStore,
    private val modeResolver: WallpaperModeResolver,
    private val renderer: WallpaperRenderer
) {
    private companion object {
        const val TAG = "WallpaperBuffer"
        const val BUFFER_FILENAME = "buffer_next.webp"
        const val TEMP_FILENAME = "buffer_temp.webp"
        const val COMPRESSION_QUALITY = 95 // Quality left high as only 1 image will exist at any time
    }

    private fun getBufferFile(): File = File(appContext.cacheDir, BUFFER_FILENAME)

    /** Files a cache clear must leave alone, finished and in-flight. */
    fun bufferFiles(): Set<File> = setOf(getBufferFile(), File(appContext.cacheDir, TEMP_FILENAME))

    /** Null when nothing usable is prepared. */
    suspend fun preparedCollectionId(): Long? = wallpaperRecordStore.snapshot().bufferedCollectionId

    /** The artifact may stay on disk but belongs to no rotation. */
    suspend fun forgetPrepared() = wallpaperRecordStore.setBufferedWallpaper(null, null)

    /**
     * Null when nothing is prepared; the caller owns the stream. Falls back to the staged atmosphere
     * delivery (engine framing) if the engine stopped being the system wallpaper; the next refill fixes the shape.
     */
    suspend fun openPrepared(): InputStream? = withContext(Dispatchers.IO) {
        val buffer = getBufferFile()
        if (buffer.exists()) return@withContext buffer.inputStream()

        val staged = AtmosphereSource.readPending(appContext.filesDir)
        if (staged == null) {
            Log.w(TAG, "Nothing prepared to apply. Is the service initialized?")
            return@withContext null
        }

        Log.w(TAG, "No static buffer; applying the staged atmosphere delivery, framed for the engine.")
        ByteArrayInputStream(staged.imageBytes)
    }

    /** Prepares the next wallpaper for whichever mode is currently effective. */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (zoom-fix gating by effective mode)
    suspend fun prepareNextWallpaper(wallpaper: WallpaperImage, cropRule: CropRule): BufferPreparationResult {
        return try {
            when (modeResolver.effectiveMode()) {
                WallpaperMode.ATMOSPHERE -> stageAtmosphereDelivery(wallpaper, cropRule)
                WallpaperMode.STATIC -> writeStaticBuffer(wallpaper, cropRule)
            }
        } catch (e: CancellationException) {
            // Not a preparation failure: the caller is being torn down
            throw e
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "Source unreadable, likely deleted: ${wallpaper.uriString}", e)
            BufferPreparationResult.Failure(definitive = true)
        } catch (e: SecurityException) {
            Log.w(TAG, "Permission revoked for source: ${wallpaper.uriString}", e)
            BufferPreparationResult.Failure(definitive = true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to prepare the next wallpaper", e)
            BufferPreparationResult.Failure(definitive = false)
        }
    }

    private suspend fun stageAtmosphereDelivery(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): BufferPreparationResult {
        val render = renderer.renderAtmosphere(wallpaper, cropRule) ?: return BufferPreparationResult.Failure(definitive = false)
        try {
            val imageBytes = Bitmaps.compressToBytes(render.bitmap, quality = COMPRESSION_QUALITY)
            // Cleared before writing, never after: a crash between must leave nothing prepared
            // (handled) rather than the previous mode's image
            discardStaticBuffer()
            if (!AtmosphereSource.writePending(appContext.filesDir, render.seeds, imageBytes)) {
                return BufferPreparationResult.Failure(definitive = false)
            }
            wallpaperRecordStore.setBufferedWallpaper(wallpaper.id, wallpaper.collectionId)
            Log.d(TAG, "Staged atmosphere delivery: ${imageBytes.size / 1024} KB | Rule: $cropRule")
        } finally {
            render.bitmap.recycle()
        }
        return BufferPreparationResult.Success
    }

    private suspend fun writeStaticBuffer(
        wallpaper: WallpaperImage,
        cropRule: CropRule
    ): BufferPreparationResult {
        val rendered = renderer.renderStatic(wallpaper, cropRule) ?: return BufferPreparationResult.Failure(definitive = false)
        try {
            AtmosphereSource.clearPending(appContext.filesDir)
            writeBuffer(rendered, cropRule)
            wallpaperRecordStore.setBufferedWallpaper(wallpaper.id, wallpaper.collectionId)
        } finally {
            rendered.recycle()
        }
        return BufferPreparationResult.Success
    }

    private fun discardStaticBuffer() {
        val buffer = getBufferFile()
        if (buffer.exists() && !buffer.delete()) {
            Log.w(TAG, "Could not delete the static buffer")
        }
    }

    /** Hops to IO itself: two of the three refill callers reach here from a main-thread scope. */
    private suspend fun writeBuffer(bitmap: Bitmap, cropRule: CropRule) = withContext(Dispatchers.IO) {
        val bufferFile = getBufferFile()
        // Written aside and swapped in: the rotation consumer may open this file at any moment.
        writeAtomically(File(appContext.cacheDir, TEMP_FILENAME), bufferFile) { temp ->
            Bitmaps.compressToFile(bitmap, temp, quality = COMPRESSION_QUALITY)
        }
        Log.d(TAG, "Buffer ready: ${bufferFile.length() / 1024} KB | Rule: $cropRule")
    }
}
