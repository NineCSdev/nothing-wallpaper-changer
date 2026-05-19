package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Renders the edited wallpaper bitmap and saves it to internal storage.
 *
 * Always renders from the **original** image ([com.ninecsdev.wallpaperchanger.model.WallpaperImage.uri]) to avoid
 * quality degradation across repeated edits.
 *
 * Zoom and offset semantics:
 * - zoom = 1.0f means the image covers the screen (fill/cover).
 * - offsetX/offsetY are normalized to -1..1 representing full available pan range.
 */
@Singleton
class WallpaperEditRenderer @Inject constructor(
    @param:ApplicationContext private val appContext: Context
) {
    private companion object {
        const val TAG = "WallpaperEditRenderer"
        const val EDITED_FOLDER = "edited_wallpapers"
        const val COMPRESSION_QUALITY = 95
    }

    /**
     * Renders the wallpaper with the given zoom and normalized offsets,
     * then saves it as a WebP file in internal storage.
     *
     * @param sourceUri The original image URI.
     * @param zoom Zoom factor where 1.0 = cover/fill the screen.
     * @param normalizedOffsetX Normalized X offset in -1..1.
     * @param normalizedOffsetY Normalized Y offset in -1..1.
     * @return The [Uri] of the saved file, or null on failure.
     */
    suspend fun renderAndSave(
        sourceUri: Uri,
        zoom: Float,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float
    ): Uri? = withContext(Dispatchers.IO) {
        try {
            val (targetW, targetH) = ImageProcessingUtils.getScreenDimensions(appContext)

            // Decode at ~2x screen res for quality headroom during zoom
            val source = ImageProcessingUtils.decodeSampledBitmap(appContext, sourceUri, targetW * 2, targetH * 2) ?: return@withContext null

            val output = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(output)
            canvas.drawColor(Color.BLACK)

            // Base scale: fit the screen
            val baseScale = minOf(
                targetW.toFloat() / source.width,
                targetH.toFloat() / source.height
            )
            val scale = baseScale * zoom

            val scaledW = source.width * scale
            val scaledH = source.height * scale

            // Center the scaled image
            val centerX = (targetW - scaledW) / 2f
            val centerY = (targetH - scaledH) / 2f

            // Convert normalized offset to pixel offset
            // Available pan range is how much the image overflows the screen
            val maxPanX = (scaledW - targetW).coerceAtLeast(0f) / 2f
            val maxPanY = (scaledH - targetH).coerceAtLeast(0f) / 2f
            val pixelOffsetX = normalizedOffsetX * maxPanX
            val pixelOffsetY = normalizedOffsetY * maxPanY

            val matrix = Matrix().apply {
                postScale(scale, scale)
                postTranslate(centerX + pixelOffsetX, centerY + pixelOffsetY)
            }
            canvas.drawBitmap(source, matrix, ImageProcessingUtils.createRenderPaint())

            // Save to file
            val dir = File(appContext.filesDir, EDITED_FOLDER)
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "edit_${UUID.randomUUID()}.webp")
            ImageProcessingUtils.compressToFile(output, file, quality = COMPRESSION_QUALITY)

            // Cleanup
            ImageProcessingUtils.recycleSafely(source, output)

            Log.d(TAG, "Edit saved: ${file.length() / 1024} KB")
            Uri.fromFile(file)
        } catch (e: Exception) {
            Log.e(TAG, "Render failed", e)
            null
        }
    }
}
