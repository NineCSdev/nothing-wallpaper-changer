package com.ninecsdev.wallpaperchanger.logic

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
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
     * Calculates an appropriate "inSampleSize" for [BitmapFactory.Options]
     * so the decoded bitmap is roughly at (or just above) the requested
     * [reqW] × [reqH] resolution without loading the full image into memory.
     */
    fun calculateInSampleSize(options: BitmapFactory.Options, reqW: Int, reqH: Int): Int {
        val (height: Int, width: Int) = options.outHeight to options.outWidth
        var inSampleSize = 1
        if (height > reqH || width > reqW) {
            val halfH = height / 2
            val halfW = width / 2
            while (halfH / inSampleSize >= reqH && halfW / inSampleSize >= reqW) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    /**
     * Two-pass decode: reads the image bounds first, computes an "inSampleSize"
     * targeting [targetW] × [targetH], then decodes at reduced resolution.
     *
     * @return the subsampled [Bitmap], or null if decoding fails.
     */
    fun decodeSampledBitmap(
        context: Context,
        uri: Uri,
        targetW: Int,
        targetH: Int
    ): Bitmap? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }

        if (options.outWidth <= 0 || options.outHeight <= 0) return null

        options.inSampleSize = calculateInSampleSize(options, targetW, targetH)
        options.inJustDecodeBounds = false

        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
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
