package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In charge of preparing the next wallpaper that will be set.
 * Handles downsampling, aspect-ratio cropping, and WebP compression.
 */
@Singleton
class BufferManager @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {
    private companion object {
        const val TAG = "BufferManager"
        const val BUFFER_FILENAME = "buffer_next.webp"
        const val TEMP_FILENAME = "buffer_temp.webp"
        const val COMPRESSION_QUALITY = 95
    }

    /**
     * Prepares the next wallpaper file on disk.
     * Prefers the user-edited version ([WallpaperImage.editedUri]) when available.
     * If the wallpaper is already in internal storage it uses that file to not overcompress.
     * Does all the processing first on a temp file and then renames to the actual file that will be used
     */
    suspend fun prepareNextWallpaper(wallpaper: WallpaperImage, cropRule: CropRule): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                val (targetW, targetH) = ImageProcessingUtils.getScreenDimensions(appContext)

                // Prefer user-edited image; fall back to original
                val sourceUri = wallpaper.editedUri ?: wallpaper.uri

                // Load the Source Bitmap
                // Already-internal images are decoded at full res to avoid double-compression
                val sourceBitmap: Bitmap? = if (sourceUri.toString().contains("internal_wallpapers")) {
                    appContext.contentResolver.openInputStream(sourceUri)?.use {
                        BitmapFactory.decodeStream(it)
                    }
                } else {
                    ImageProcessingUtils.decodeSampledBitmap(appContext, sourceUri, targetW, targetH)
                }

                if (sourceBitmap == null) return@withContext false

                // Process with crop rule
                val finalBitmap = processBitmap(sourceBitmap, targetW, targetH, cropRule)

                // Atomic Write to Disk
                val bufferFile = getBufferFile()
                val tempFile = File(appContext.cacheDir, TEMP_FILENAME)

                ImageProcessingUtils.compressToFile(finalBitmap, tempFile, quality = COMPRESSION_QUALITY)

                val success = tempFile.renameTo(bufferFile)
                if (success) {
                    Log.d(TAG, "Buffer ready: ${bufferFile.length() / 1024} KB | Rule: $cropRule")
                }

                // Clean up
                ImageProcessingUtils.recycleSafely(sourceBitmap, finalBitmap)

                success
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare buffer: ${e.message}")
                false
            }
        }
    }

    fun getBufferFile(): File {
        return File(appContext.cacheDir, BUFFER_FILENAME)
    }



    private fun processBitmap(source: Bitmap, targetW: Int, targetH: Int, rule: CropRule): Bitmap {
        val sourceW = source.width
        val sourceH = source.height

        // Calculate scale depending on croprule
        val scale = if (rule == CropRule.FIT) {
            // FIT: Entire image visible
            minOf(targetW.toFloat() / sourceW, targetH.toFloat() / sourceH)
        } else {
            // CENTER/LEFT/RIGHT: Fill screen
            maxOf(targetW.toFloat() / sourceW, targetH.toFloat() / sourceH)
        }
        val scaledW = sourceW * scale
        val scaledH = sourceH * scale

        // Calculate Offsets
        val xOffset = when (rule) {
            CropRule.LEFT -> 0f
            CropRule.RIGHT -> targetW - scaledW
            else -> (targetW - scaledW) / 2f
        }
        val yOffset = (targetH - scaledH) / 2f

        // Render
        val result = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK) // Paint the background black for the fit option
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val matrix = android.graphics.Matrix().apply {
            postScale(scale, scale)
            postTranslate(xOffset, yOffset)
        }

        canvas.drawBitmap(source, matrix, paint)
        return result
    }
}
