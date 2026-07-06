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
import android.net.Uri
import android.util.Log
import androidx.core.graphics.createBitmap
import kotlin.math.roundToInt
import com.ninecsdev.wallpaperchanger.data.local.AppDataStore
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import com.ninecsdev.wallpaperchanger.model.enums.WallpaperZoomFix
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton

sealed class BufferPreparationResult {
    object Success : BufferPreparationResult()

    /**
     * @param definitive true when the failure proves the source is gone (file deleted, permission
     * revoked) — [RotationEngine][com.ninecsdev.wallpaperchanger.logic.RotationEngine] marks the file
     * unavailable rather than retrying it. false for any other failure (decode error, transient IO) —
     * the image is simply skipped for this rotation and retried later, no side effects.
     */
    data class Failure(val definitive: Boolean) : BufferPreparationResult()
}

/**
 * In charge of preparing the next wallpaper that will be set.
 * Handles downsampling, aspect-ratio cropping, and WebP compression.
 *
 * When a wallpaper has edit params (zoom/offsetX/offsetY), the collection's [CropRule] is
 * bypassed entirely.
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
        const val COMPRESSION_QUALITY = 95 // Quality left high as only 1 image will exist at any time
        const val ZOOM_INSET_FRACTION = 0.045f // Zoom that I observed in a 20:9 screen
        const val BLUR_DOWNSCALE_FACTOR = 24
        const val EDIT_DECODE_SCALE = 2
    }

    private data class TargetSize(
        val width: Int, val height: Int
    )

    private data class BitmapPlacement(
        val scale: Float,
        val xOffset: Float,
        val yOffset: Float
    )

    fun getBufferFile(): File = File(appContext.cacheDir, BUFFER_FILENAME)

    /**
     * Applies the wallpaper zoom-fix padding to the given [bitmap] if the
     * user has the setting enabled. Returns the original bitmap unchanged
     * when the zoom-fix is [WallpaperZoomFix.OFF].
     */
    suspend fun applyZoomFixIfNeeded(bitmap: Bitmap): Bitmap {
        // Used in WallpaperApplier for default wallpaper
        val zoomFix = appDataStore.getWallpaperZoomFix()
        if (zoomFix == WallpaperZoomFix.OFF) return bitmap

        val padded = addZoomFixPadding(bitmap, zoomFix)
        // Don't recycle the input as caller owns it
        return padded
    }

    /**
     * Prepares the next wallpaper file on disk.
     *
     * If the wallpaper has edit params, the edit transform (fit + zoom + offset) is applied
     * and the [cropRule] is **bypassed**.
     * Otherwise, the standard [cropRule] pipeline is used.
     */
    suspend fun prepareNextWallpaper(wallpaper: WallpaperImage, cropRule: CropRule): BufferPreparationResult {
        return withContext(Dispatchers.IO) {
            try {
                val targetSize = getTargetSize()
                val zoomFix = appDataStore.getWallpaperZoomFix()
                val hasEdit = wallpaper.editParams != null

                val sourceBitmap = decodeSourceBitmap(
                    wallpaper.uri,
                    targetSize,
                    oversample = if (hasEdit) EDIT_DECODE_SCALE else 1
                ) ?: return@withContext BufferPreparationResult.Failure(definitive = false)

                var finalBitmap: Bitmap? = null
                try {
                    finalBitmap = prepareFinalBitmap(wallpaper, sourceBitmap, targetSize, cropRule, zoomFix)
                    writeBuffer(finalBitmap, cropRule)
                    BufferPreparationResult.Success
                } finally {
                    recyclePreparedBitmaps(sourceBitmap, finalBitmap)
                }
            } catch (e: FileNotFoundException) {
                Log.w(TAG, "Source unreadable, likely deleted: ${wallpaper.uri}", e)
                BufferPreparationResult.Failure(definitive = true)
            } catch (e: SecurityException) {
                Log.w(TAG, "Permission revoked for source: ${wallpaper.uri}", e)
                BufferPreparationResult.Failure(definitive = true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare buffer", e)
                BufferPreparationResult.Failure(definitive = false)
            }
        }
    }

    private fun getTargetSize(): TargetSize {
        val (width, height) = ImageProcessingUtils.getScreenDimensions(appContext)
        return TargetSize(width, height)
    }

    private fun decodeSourceBitmap(sourceUri: Uri, targetSize: TargetSize, oversample: Int = 1): Bitmap? {
        val reqW = targetSize.width * oversample
        val reqH = targetSize.height * oversample
        return if (ImageInternalizer.isInternalUri(sourceUri)) {
            appContext.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } else {
            ImageProcessingUtils.decodeSampledBitmap(
                appContext,
                sourceUri,
                reqW,
                reqH
            )
        }
    }

    private fun prepareFinalBitmap(
        wallpaper: WallpaperImage,
        sourceBitmap: Bitmap,
        targetSize: TargetSize,
        cropRule: CropRule,
        zoomFix: WallpaperZoomFix
    ): Bitmap {
        val editParams = wallpaper.editParams
        return if (editParams != null) {
            val edited = applyEditTransform(
                source = sourceBitmap,
                targetSize = targetSize,
                zoom = editParams.zoom,
                normalizedOffsetX = editParams.offsetX,
                normalizedOffsetY = editParams.offsetY
            )
            if (zoomFix == WallpaperZoomFix.OFF) {
                edited
            } else {
                val padded = addZoomFixPadding(edited, zoomFix)
                if (padded !== edited) edited.recycle()
                padded
            }
        } else {
            processBitmap(sourceBitmap, targetSize, cropRule, zoomFix)
        }
    }

    /**
     * Applies the user's edit params to produce a screen-sized bitmap.
     *
     * Uses identical math to the editor preview (fit-based scaling + user zoom + normalized offsets),
     * so the wallpaper on the lockscreen matches exactly what the user saw in the editor.
     *
     * @param source Decoded source bitmap (ideally at 2× screen resolution for quality headroom).
     * @param zoom User zoom factor where 1.0 = cover/fill the screen.
     * @param normalizedOffsetX Normalized X offset in -1..1.
     * @param normalizedOffsetY Normalized Y offset in -1..1.
     */
    private fun applyEditTransform(
        source: Bitmap,
        targetSize: TargetSize,
        zoom: Float,
        normalizedOffsetX: Float,
        normalizedOffsetY: Float
    ): Bitmap {
        val targetW = targetSize.width
        val targetH = targetSize.height

        // Base scale: fit the image inside the screen (same as the editor preview)
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

        // Convert normalized offsets to pixel offsets
        val maxPanX = (scaledW - targetW).coerceAtLeast(0f) / 2f
        val maxPanY = (scaledH - targetH).coerceAtLeast(0f) / 2f
        val pixelOffsetX = normalizedOffsetX * maxPanX
        val pixelOffsetY = normalizedOffsetY * maxPanY

        val output = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)

        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(centerX + pixelOffsetX, centerY + pixelOffsetY)
        }
        canvas.drawBitmap(source, matrix, ImageProcessingUtils.createRenderPaint())

        return output
    }

    private fun processBitmap(
        source: Bitmap,
        targetSize: TargetSize,
        rule: CropRule,
        zoomFix: WallpaperZoomFix
    ): Bitmap {
        val screenBitmap = renderScreenBitmap(source, targetSize, rule)

        if (zoomFix == WallpaperZoomFix.OFF) {
            return screenBitmap
        }

        var paddedBitmap: Bitmap? = null
        try {
            val padded = addZoomFixPadding(screenBitmap, zoomFix)
            paddedBitmap = padded
            return padded
        } finally {
            if (paddedBitmap !== screenBitmap) screenBitmap.recycle()
        }
    }

    private fun renderScreenBitmap(source: Bitmap, targetSize: TargetSize, rule: CropRule): Bitmap {
        val placement = calculateBitmapPlacement(source.width, source.height, targetSize, rule)

        return ImageProcessingUtils.renderScaledBitmap(
            source,
            targetSize.width,
            targetSize.height,
            placement.scale,
            placement.xOffset,
            placement.yOffset
        )
    }

    private fun calculateBitmapPlacement(
        sourceWidth: Int,
        sourceHeight: Int,
        targetSize: TargetSize,
        rule: CropRule
    ): BitmapPlacement {
        val scale = when (rule) {
            CropRule.FIT -> minOf(
                targetSize.width.toFloat() / sourceWidth,
                targetSize.height.toFloat() / sourceHeight
            )
            CropRule.CENTER,
            CropRule.LEFT,
            CropRule.RIGHT -> maxOf(
                targetSize.width.toFloat() / sourceWidth,
                targetSize.height.toFloat() / sourceHeight
            )
        }
        val scaledWidth = sourceWidth * scale
        val scaledHeight = sourceHeight * scale
        val xOffset = when (rule) {
            CropRule.LEFT -> 0f
            CropRule.RIGHT -> targetSize.width - scaledWidth
            CropRule.CENTER,
            CropRule.FIT -> (targetSize.width - scaledWidth) / 2f
        }
        val yOffset = (targetSize.height - scaledHeight) / 2f

        return BitmapPlacement(scale, xOffset, yOffset)
    }

    private fun writeBuffer(bitmap: Bitmap, cropRule: CropRule) {
        val tempFile = File(appContext.cacheDir, TEMP_FILENAME)
        val bufferFile = getBufferFile()

        try {
            ImageProcessingUtils.compressToFile(bitmap, tempFile, quality = COMPRESSION_QUALITY)
            replaceBuffer(tempFile, bufferFile)
            Log.d(TAG, "Buffer ready: ${bufferFile.length() / 1024} KB | Rule: $cropRule")
        } finally {
            if (tempFile.exists()) tempFile.delete()
        }
    }

    private fun replaceBuffer(tempFile: File, bufferFile: File) {
        try {
            Files.move(tempFile.toPath(), bufferFile.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tempFile.toPath(), bufferFile.toPath(), REPLACE_EXISTING)
        }
    }

    private fun recyclePreparedBitmaps(sourceBitmap: Bitmap, finalBitmap: Bitmap?) {
        if (finalBitmap == null) {
            sourceBitmap.recycle()
        } else {
            ImageProcessingUtils.recycleSafely(sourceBitmap, finalBitmap)
        }
    }

    private fun addZoomFixPadding(screenBitmap: Bitmap, zoomFix: WallpaperZoomFix): Bitmap {
        val targetW = screenBitmap.width
        val targetH = screenBitmap.height

        // Nothing OS sometimes zooms the wallpaper presentation after it is set.
        // Keep the wallpaper at full resolution and add tunable padding around it for zoom to consume.
        val insetX = calculateZoomInset(targetW)
        val insetY = calculateZoomInset(targetH)
        val paddedW = targetW + insetX * 2
        val paddedH = targetH + insetY * 2

        Log.d(TAG, "Adding zoom-fix padding: $paddedW x $paddedH with insets $insetX x $insetY")

        val padded = createBitmap(paddedW, paddedH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(padded)
        val paint = ImageProcessingUtils.createRenderPaint()
        var success = false

        try {
            drawPaddingBackground(screenBitmap, canvas, paddedW, paddedH, insetX, insetY, paint, zoomFix)
            canvas.drawBitmap(screenBitmap, insetX.toFloat(), insetY.toFloat(), paint)
            success = true
            return padded
        } finally {
            if (!success) padded.recycle()
        }
    }

    private fun calculateZoomInset(size: Int, extraPx: Int = 0): Int {
        return ((size * ZOOM_INSET_FRACTION).roundToInt() + extraPx)
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
        zoomFix: WallpaperZoomFix
    ) {
        when (zoomFix) {
            WallpaperZoomFix.BLURRED -> drawBlurredPadding(source, canvas, paddedW, paddedH, paint)
            WallpaperZoomFix.EDGE -> drawEdgePadding(source, canvas, insetX, insetY, paint)
            WallpaperZoomFix.OFF -> Unit
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
        val blurSize = TargetSize(blurW, blurH)
        val blurred = createBitmap(blurW, blurH, Bitmap.Config.ARGB_8888)
        val blurCanvas = Canvas(blurred)
        val placement = calculateBitmapPlacement(source.width, source.height, blurSize, CropRule.CENTER)

        try {
            val matrix = Matrix().apply {
                postScale(placement.scale, placement.scale)
                postTranslate(placement.xOffset, placement.yOffset)
            }

            blurCanvas.drawBitmap(source, matrix, paint)
            canvas.drawBitmap(
                blurred,
                Rect(0, 0, blurW, blurH),
                RectF(0f, 0f, paddedW.toFloat(), paddedH.toFloat()),
                paint
            )
        } finally {
            blurred.recycle()
        }
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
