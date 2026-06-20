package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
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
import com.ninecsdev.wallpaperchanger.model.enums.LockscreenZoomFix
import com.ninecsdev.wallpaperchanger.model.WallpaperImage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import javax.inject.Inject
import javax.inject.Singleton

enum class BufferSource {
    EDITED,
    ORIGINAL
}

sealed class BufferPreparationResult {
    data class Success(val source: BufferSource) : BufferPreparationResult()
    object Failure : BufferPreparationResult()
}

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
        const val INTERNAL_WALLPAPERS_DIRECTORY = "internal_wallpapers"
        const val COMPRESSION_QUALITY = 95 // Quality left high as only 1 image will exist at any time
        const val LOCKSCREEN_ZOOM_INSET_FRACTION = 0.045f // Zoom that I observed in a 20:9 screen
        const val BLUR_DOWNSCALE_FACTOR = 24
    }

    private data class TargetSize(
        val width: Int, val height: Int
    )

    private data class DecodedWallpaper(
        val bitmap: Bitmap,
        val source: BufferSource
    )

    private data class BitmapPlacement(
        val scale: Float,
        val xOffset: Float,
        val yOffset: Float
    )

    fun getBufferFile(): File = File(appContext.cacheDir, BUFFER_FILENAME)

    /**
     * Applies the lockscreen zoom-fix padding to the given [bitmap] if the
     * user has the setting enabled. Returns the original bitmap unchanged
     * when the zoom-fix is [LockscreenZoomFix.OFF].
     */
    suspend fun applyZoomFixIfNeeded(bitmap: Bitmap): Bitmap {
        // Used in WallpaperApplier for default wallpaper
        val zoomFix = appDataStore.getLockscreenZoomFix()
        if (zoomFix == LockscreenZoomFix.OFF) return bitmap

        val padded = addLockscreenPadding(bitmap, zoomFix)
        // Don't recycle the input as caller owns it
        return padded
    }

    /**
     * Prepares the next wallpaper file on disk.
     * Prefers the user-edited version ([WallpaperImage.editedUri]) when available.
     * Internal wallpapers are decoded directly because they have already been resized by the app.
     * Processing writes to a temp file first, then replaces the active buffer file.
     */
    suspend fun prepareNextWallpaper(wallpaper: WallpaperImage, cropRule: CropRule): BufferPreparationResult {
        return withContext(Dispatchers.IO) {
            try {
                val targetSize = getTargetSize()
                val decoded = decodeWallpaperSource(wallpaper, targetSize) ?: return@withContext BufferPreparationResult.Failure
                val zoomFix = appDataStore.getLockscreenZoomFix()
                var finalBitmap: Bitmap? = null

                try {
                    finalBitmap = processBitmap(decoded.bitmap, targetSize, cropRule, zoomFix)
                    writeBuffer(finalBitmap, cropRule)
                    BufferPreparationResult.Success(decoded.source)
                } finally {
                    recyclePreparedBitmaps(decoded.bitmap, finalBitmap)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to prepare buffer", e)
                BufferPreparationResult.Failure
            }
        }
    }

    private fun getTargetSize(): TargetSize {
        val (width, height) = ImageProcessingUtils.getScreenDimensions(appContext)
        return TargetSize(width, height)
    }

    private fun decodeWallpaperSource(wallpaper: WallpaperImage, targetSize: TargetSize): DecodedWallpaper? {
        wallpaper.editedUri?.let { uri ->
            decodeSourceBitmap(uri, targetSize)?.let { bitmap ->
                return DecodedWallpaper(bitmap, BufferSource.EDITED)
            }
        }

        return decodeSourceBitmap(wallpaper.uri, targetSize)?.let { bitmap ->
            DecodedWallpaper(bitmap, BufferSource.ORIGINAL)
        }
    }

    private fun decodeSourceBitmap(sourceUri: Uri, targetSize: TargetSize): Bitmap? {
        return if (sourceUri.isInternalWallpaperUri()) {
            appContext.contentResolver.openInputStream(sourceUri)?.use {
                BitmapFactory.decodeStream(it)
            }
        } else {
            ImageProcessingUtils.decodeSampledBitmap(
                appContext,
                sourceUri,
                targetSize.width,
                targetSize.height
            )
        }
    }

    private fun Uri.isInternalWallpaperUri(): Boolean {
        return pathSegments.contains(INTERNAL_WALLPAPERS_DIRECTORY)
    }

    private fun processBitmap(
        source: Bitmap,
        targetSize: TargetSize,
        rule: CropRule,
        zoomFix: LockscreenZoomFix
    ): Bitmap {
        val screenBitmap = renderScreenBitmap(source, targetSize, rule)

        if (zoomFix == LockscreenZoomFix.OFF) {
            return screenBitmap
        }

        var paddedBitmap: Bitmap? = null
        try {
            val padded = addLockscreenPadding(screenBitmap, zoomFix)
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
        } catch (e: AtomicMoveNotSupportedException) {
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

    private fun addLockscreenPadding(screenBitmap: Bitmap, zoomFix: LockscreenZoomFix): Bitmap {
        val targetW = screenBitmap.width
        val targetH = screenBitmap.height

        // Nothing OS sometimes zooms the lockscreen presentation after the wallpaper is set.
        // Keep the wallpaper at full resolution and add tunable padding around it for zoom to consume.
        val insetX = calculateZoomInset(targetW)
        val insetY = calculateZoomInset(targetH)
        val paddedW = targetW + insetX * 2
        val paddedH = targetH + insetY * 2

        Log.d(TAG, "Adding lockscreen padding: $paddedW x $paddedH with insets $insetX x $insetY")

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
