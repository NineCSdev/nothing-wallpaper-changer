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

object ImageProcessingUtils {

    private const val TAG = "ImageProcessingUtils"

    /** The pixel size a prepared wallpaper has to be. Assumes a display whose natural orientation is portrait. */
    fun getWallpaperCanvasSize(context: Context): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        if (bounds.width() > bounds.height()) {
            Log.d(TAG, "Display reports ${bounds.width()}x${bounds.height()}; using the portrait canvas.")
        }
        return minOf(bounds.width(), bounds.height()) to maxOf(bounds.width(), bounds.height())
    }

    /** Largest power-of-two sample size that keeps the image at or just above [reqW] x [reqH]. */
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
     * Decodes [uri] subsampled toward [targetW] x [targetH], EXIF orientation applied. Software-backed,
     * since callers draw it onto a software [Canvas]. Null if undecodable; failures to open the source propagate.
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

    fun compressToBytes(
        bitmap: Bitmap,
        format: Bitmap.CompressFormat = Bitmap.CompressFormat.WEBP_LOSSY,
        quality: Int = 95
    ): ByteArray = ByteArrayOutputStream().also { bitmap.compress(format, quality, it) }.toByteArray()

    /** Safe even if [source] and [processed] are the same instance. */
    fun recycleSafely(source: Bitmap, processed: Bitmap) {
        if (processed != source) source.recycle()
        processed.recycle()
    }

    fun createRenderPaint(): Paint = Paint().apply {
        isAntiAlias = true
        isFilterBitmap = true
        isDither = true
    }

    /** The canvas is filled with [Color.BLACK] first so unfilled areas (e.g. FIT mode) have a clean background. */
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
