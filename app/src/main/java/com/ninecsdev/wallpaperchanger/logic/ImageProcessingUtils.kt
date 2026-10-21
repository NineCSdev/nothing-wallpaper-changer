package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import android.util.Log
import android.view.WindowManager
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Shared image processing utilities used by [BufferManager] and [ImageInternalizer].
 * Centralizes bitmap decoding, compression, rendering, and the
 * wallpaper-canvas lookup so the logic is not duplicated.
 */
object ImageProcessingUtils {

    private const val TAG = "ImageProcessingUtils"

    /**
     * The wallpaper canvas as a `(width, height)` pair: the pixel size a prepared wallpaper has to be.
     * Assumes a display whose natural orientation is portrait.
     */
    fun getWallpaperCanvasSize(context: Context): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (bounds.width() > bounds.height()) {
            Log.d(TAG, "Display reports ${bounds.width()}x${bounds.height()}; using the portrait canvas.")
        }
        return minOf(bounds.width(), bounds.height()) to maxOf(bounds.width(), bounds.height())
    }

    /**
     * The largest power-of-two sample size that keeps a [width] × [height] image at or just above
     * [reqW] × [reqH], so decode never loads more pixels than the render needs.
     */
    private fun calculateSampleSize(width: Int, height: Int, reqW: Int, reqH: Int): Int {
        var sampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while (halfH / sampleSize >= reqH && halfW / sampleSize >= reqW) {
                sampleSize *= 2
            }
        }
        return sampleSize
    }

    /**
     * Decodes [uri] subsampled toward [targetW] × [targetH], with EXIF orientation applied.
     * Software-backed, since callers draw it onto a software [Canvas].
     *
     * @return null if the data cannot be decoded; failures to open the source propagate.
     */
    fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        return try {
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                // info.size is already orientation-corrected
                decoder.setTargetSampleSize(
                    calculateSampleSize(info.size.width, info.size.height, targetW, targetH)
                )
            }
        } catch (e: ImageDecoder.DecodeException) {
            Log.w(TAG, "Could not decode $uri", e)
            null
        }
    }

    /**
     * Compresses [bitmap] into [file] using the given [format] and [quality].
     */
    fun compressToFile(
        bitmap: Bitmap,
        file: File,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSY,
        quality: Int = 95
    ) {
        FileOutputStream(file).use { out ->
            bitmap.compress(format, quality, out)
        }
    }

    /**
     * [compressToFile]'s in-memory sibling, for the atmosphere source container, which carries the
     * encoded bytes inline rather than as a file of their own.
     */
    fun compressToBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSY,
        quality: Int = 95
    ): ByteArray = ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    /**
     * Recycles both the [source] and [processed] bitmaps.
     * If they are the same instance only one recycle call is made.
     */
    fun recycleSafely(source: Bitmap, processed: Bitmap) {
        if (processed != source) source.recycle()
        processed.recycle()
    }

    /**
     * Returns a [Paint] configured for high-quality bitmap rendering.
     * Shared by all render paths to avoid constructing identical [Paint]
     * objects in [BufferManager].
     */
    fun createRenderPaint(): Paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    /**
     * Scales [source] and draws it onto a new [targetW] × [targetH] bitmap using
     * the given [scale] and pixel translation ([translateX], [translateY]).
     *
     * The canvas is first filled with [Color.BLACK] so transparent or unfilled
     * areas (e.g. from FIT crop mode) have a clean background.
     */
    fun renderScaledBitmap(
        source: Bitmap,
        targetW: Int,
        targetH: Int,
        scale: Float,
        translateX: Float,
        translateY: Float
    ): Bitmap {
        val output = createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        canvas.drawColor(Color.BLACK)
        val matrix = Matrix().apply {
            postScale(scale, scale)
            postTranslate(translateX, translateY)
        }
        canvas.drawBitmap(source, matrix, createRenderPaint())
        return output
    }
}
