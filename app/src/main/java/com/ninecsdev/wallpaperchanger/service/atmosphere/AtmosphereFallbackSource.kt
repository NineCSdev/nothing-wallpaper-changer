package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereProtocol
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.AtmosphereSource
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import kotlin.math.roundToInt

/**
 * The image the engine shows when [AtmosphereSource] holds nothing (the system may start the
 * engine at any time); otherwise the panel would stay black with nothing scheduled to fix it.
 * The app is asked for a real source meanwhile (see [AtmosphereProtocol.ACTION_SOURCE_REQUESTED]).
 * Seeds are sampled from the image, not analyzed.
 */
internal object AtmosphereFallbackSource {

    private const val TAG = "AtmosphereFallback"

    private const val SAMPLE_WIDTH = 32

    /** Shown only if the device will not even hand over its built-in wallpaper. */
    private const val NEUTRAL_COLOR = 0xFF404040.toInt()

    /** Null if [width]/[height] are not yet known. Call off the GL thread; it decodes and scales. */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (no-source recovery)
    fun load(context: Context, width: Int, height: Int): AtmosphereSource.Decoded? {
        if (width <= 0 || height <= 0) return null

        val bitmap = builtInWallpaper(context, width, height) ?: flatBitmap(width, height)

        return AtmosphereSource.Decoded(sampleSeeds(bitmap), bitmap)
    }

    private fun builtInWallpaper(context: Context, width: Int, height: Int): Bitmap? = try {
        val drawable = WallpaperManager.getInstance(context)
            .getBuiltInDrawable(width, height, true, 0.5f, 0.5f)
        if (drawable == null) {
            Log.w(TAG, "No built-in wallpaper on this device")
            null
        } else {
            createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                drawable.setBounds(0, 0, width, height)
                drawable.draw(Canvas(bitmap))
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "Could not read the built-in wallpaper", e)
        null
    }

    private fun flatBitmap(width: Int, height: Int): Bitmap =
        createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            eraseColor(NEUTRAL_COLOR)
        }

    /**
     * The mean color for the background plus five spread samples for the blobs. Spread matters:
     * a blob starts at its seed's position (see [ShapeCreator]), so clustered samples would launch
     * every blob from the same place.
     */
    private fun sampleSeeds(bitmap: Bitmap): List<VertexInfo> {
        val width = SAMPLE_WIDTH.coerceIn(1, bitmap.width)
        val height = (width.toFloat() * bitmap.height / bitmap.width)
            .roundToInt().coerceIn(1, bitmap.height)

        val thumbnail = bitmap.scale(width, height)
        val pixels = IntArray(width * height)
        thumbnail.getPixels(pixels, 0, width, 0, 0, width, height)
        if (thumbnail !== bitmap) thumbnail.recycle()

        val seeds = ArrayList<VertexInfo>(VertexInfo.SEED_COUNT)
        seeds += VertexInfo(0, 0, width, height, meanColor(pixels), VertexInfo.SYNTHESIZED_POPULATION)

        for (i in 0 until AtmosphereConstants.BLOB_COUNT) {
            val across = (i + 0.5f) / AtmosphereConstants.BLOB_COUNT
            // Inset from the edges, alternating high and low: a zigzag, not a line
            val x = ((0.15f + 0.7f * across) * width).toInt().coerceIn(0, width - 1)
            val y = ((if (i % 2 == 0) 0.25f else 0.75f) * height).toInt().coerceIn(0, height - 1)
            seeds += VertexInfo(
                x, y, width, height, pixels[y * width + x], VertexInfo.SYNTHESIZED_POPULATION
            )
        }
        return seeds
    }

    private fun meanColor(pixels: IntArray): Int {
        if (pixels.isEmpty()) return NEUTRAL_COLOR
        var red = 0L
        var green = 0L
        var blue = 0L
        for (pixel in pixels) {
            red += (pixel shr 16) and 0xFF
            green += (pixel shr 8) and 0xFF
            blue += pixel and 0xFF
        }
        val count = pixels.size
        return Color.rgb((red / count).toInt(), (green / count).toInt(), (blue / count).toInt())
    }
}
