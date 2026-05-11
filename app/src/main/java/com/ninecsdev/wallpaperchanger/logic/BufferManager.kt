package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.CropRule
import com.ninecsdev.wallpaperchanger.model.LockscreenZoomFix
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
    @param:ApplicationContext private val appContext: Context,
    private val appDataStore: AppDataStore
) {
    private companion object {
        const val TAG = "BufferManager"
        const val BUFFER_FILENAME = "buffer_next.webp"
        const val TEMP_FILENAME = "buffer_temp.webp"
        const val COMPRESSION_QUALITY = 95
        const val LOCKSCREEN_ZOOM_INSET_FRACTION = 0.045f
        const val LOCKSCREEN_ZOOM_EXTRA_X_PX = 0
        const val LOCKSCREEN_ZOOM_EXTRA_Y_PX = 0
        const val BLUR_DOWNSCALE_FACTOR = 24
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

                val zoomFix = appDataStore.getLockscreenZoomFix()

                // Process with crop rule
                val finalBitmap = processBitmap(sourceBitmap, targetW, targetH, cropRule, zoomFix)

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


    private fun processBitmap(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        rule: CropRule,
        zoomFix: LockscreenZoomFix
    ): Bitmap {
        val result = renderScreenBitmap(source, targetW, targetH, rule)

        if (zoomFix == LockscreenZoomFix.OFF) {
            return result
        }

        val padded = addLockscreenPadding(result, zoomFix)
        result.recycle()

        return padded
    }

    private fun renderScreenBitmap(source: Bitmap, targetW: Int, targetH: Int, rule: CropRule): Bitmap {
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

        val xOffset = when (rule) {
            CropRule.LEFT -> 0f
            CropRule.RIGHT -> targetW - scaledW
            else -> (targetW - scaledW) / 2f
        }
        val yOffset = (targetH - scaledH) / 2f

        // Render to the target-sized bitmap first
        val result = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        canvas.drawColor(Color.BLACK) // Paint the background black for the fit option
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(xOffset, yOffset)
        }

        canvas.drawBitmap(source, matrix, paint)
        return result
    }

    private fun addLockscreenPadding(screenBitmap: Bitmap, zoomFix: LockscreenZoomFix): Bitmap {
        val targetW = screenBitmap.width
        val targetH = screenBitmap.height

        // Nothing OS sometimes zooms the lockscreen presentation after the wallpaper is set.
        // Keep the wallpaper at full resolution and add tunable padding around it for zoom to consume.
        val insetX = calculateZoomInset(targetW, LOCKSCREEN_ZOOM_EXTRA_X_PX)
        val insetY = calculateZoomInset(targetH, LOCKSCREEN_ZOOM_EXTRA_Y_PX)
        val paddedW = targetW + insetX * 2
        val paddedH = targetH + insetY * 2

        Log.d(TAG, "Adding lockscreen padding: $paddedW x $paddedH with insets $insetX x $insetY")

        val padded = createBitmap(paddedW, paddedH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        val paint = Paint().apply {
            isAntiAlias = true
            isFilterBitmap = true
            isDither = true
        }

        drawPaddingBackground(screenBitmap, canvas, paddedW, paddedH, insetX, insetY, paint, zoomFix)
        canvas.drawBitmap(screenBitmap, insetX.toFloat(), insetY.toFloat(), paint)

        return padded
    }

    private fun calculateZoomInset(size: Int, extraPx: Int): Int {
        return ((size * LOCKSCREEN_ZOOM_INSET_FRACTION).roundToInt() + extraPx)
            .coerceAtLeast(0)
            .coerceAtMost((size - 1) / 2)
    }

    private fun drawPaddingBackground(
        source: Bitmap,
        canvas: Canvas,
        paddedW: Int,
        paddedH: Int,
        insetX: Int,
        insetY: Int,
        paint: Paint,
        zoomFix: LockscreenZoomFix
    ) {
        when (zoomFix) {
            LockscreenZoomFix.BLURRED -> drawBlurredPadding(source, canvas, paddedW, paddedH, paint)
            LockscreenZoomFix.EDGE -> drawEdgePadding(source, canvas, insetX, insetY, paint)
            LockscreenZoomFix.OFF -> Unit
        }
    }

    private fun drawBlurredPadding(
        source: Bitmap,
        canvas: Canvas,
        paddedW: Int,
        paddedH: Int,
        paint: Paint
    ) {
        val blurW = (paddedW / BLUR_DOWNSCALE_FACTOR).coerceAtLeast(1)
        val blurH = (paddedH / BLUR_DOWNSCALE_FACTOR).coerceAtLeast(1)
        val blurred = createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
        val blurCanvas = Canvas(blurred)
        val coverScale = maxOf(blurW.toFloat() / source.width, blurH.toFloat() / source.height)
        val scaledW = source.width * coverScale
        val scaledH = source.height * coverScale
        val left = (blurW - scaledW) / 2f
        val top = (blurH - scaledH) / 2f

        val matrix = Matrix().apply {
            postScale(coverScale, coverScale)
            postTranslate(left, top)
        }

        blurCanvas.drawBitmap(source, matrix, paint)
        canvas.drawBitmap(
            blurred,
            Rect(0, 0, blurW, blurH),
            RectF(0f, 0f, paddedW.toFloat(), paddedH.toFloat()),
            paint
        )
        blurred.recycle()
    }

    private fun drawEdgePadding(
        source: Bitmap,
        canvas: Canvas,
        insetX: Int,
        insetY: Int,
        paint: Paint
    ) {
        val width = source.width
        val height = source.height
        val paddedW = width + insetX * 2
        val paddedH = height + insetY * 2

        if (insetY > 0) {
            canvas.drawBitmap(
                source,
                Rect(0, 0, width, 1),
                RectF(0f, 0f, paddedW.toFloat(), insetY.toFloat()),
                paint
            )
            canvas.drawBitmap(
                source,
                Rect(0, height - 1, width, height),
                RectF(0f, (paddedH - insetY).toFloat(), paddedW.toFloat(), paddedH.toFloat()),
                paint
            )
        }

        if (insetX > 0) {
            canvas.drawBitmap(
                source,
                Rect(0, 0, 1, height),
                RectF(0f, insetY.toFloat(), insetX.toFloat(), (insetY + height).toFloat()),
                paint
            )
            canvas.drawBitmap(
                source,
                Rect(width - 1, 0, width, height),
                RectF((insetX + width).toFloat(), insetY.toFloat(), paddedW.toFloat(), (insetY + height).toFloat()),
                paint
            )
        }
    }

}
