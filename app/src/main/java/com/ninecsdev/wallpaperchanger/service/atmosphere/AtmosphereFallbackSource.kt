package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import kotlin.math.roundToInt

/**
 * The fallback image the engine shows when it has no source of its own.
 *
 * The system starts this engine whenever it likes. In the case [AtmosphereSource]
 * holds nothing the renderer's guard would leave the panel cleared to black
 * with nothing scheduled to ever fix it. So the engine falls back to the device's built-in
 * wallpaper while it asks the app for a real source (see [AtmosphereWallpaperService.ACTION_SOURCE_REQUESTED]).
 *
 * Seeds here are **sampled, not analyzed**.
 */
internal object AtmosphereFallbackSource {

    private const val TAG = "AtmosphereFallback"

    /** Width of the thumbnail the seed colors are read from. Height follows the aspect. */
    private const val SAMPLE_WIDTH = 32

    /** Shown only if the device will not even hand over its built-in wallpaper. */
    private const val NEUTRAL_COLOR = 0xFF404040.toInt()

    /**
     * An *AtmosphereSource* or null if [width]/[height] are not yet known.
     *
     * Call it off the GL thread; it decodes and scales. The caller owns the returned bitmap on
     * the same terms as one read from [AtmosphereSource].
     */
    // TODO tests: see vault note tests/Atmosphere Delivery Tests.md (no-source recovery)
    fun load(context: Context, width: Int, height: Int): AtmosphereSource.Decoded? {
        if (width <= 0 || height <= 0) return null

        val bitmap = builtInWallpaper(context, width, height) ?: flatBitmap(width, height)

        return AtmosphereSource.Decoded(sampleSeeds(bitmap), bitmap)
    }

    /** The device's own wallpaper, drawn at surface size. */
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
     * Six seeds off a thumbnail: an overall cast for the background, and five spread samples for
     * the blobs.
     *
     * Spread matters and clustering does not work as a blob starts at its seed's position (see
     * [ShapeCreator]), so five samples from one corner would launch every blob from the same place.
     * Every seed carries [VertexInfo.SYNTHESIZED_POPULATION].
     */
    private fun sampleSeeds(bitmap: Bitmap): List<VertexInfo> {
        val width = SAMPLE_WIDTH.coerceIn(1, bitmap.width)
        val height = (width.toFloat() * bitmap.height / bitmap.width)
            .roundToInt().coerceIn(1, bitmap.height)

        val thumbnail = bitmap.scale(width, height)
        val pixels = IntArray(width * height)
        thumbnail.getPixels(pixels, 0, width, 0, 0, width, height)
        if (thumbnail !== bitmap) thumbnail.recycle()

        val seeds = ArrayList<VertexInfo>(AtmosphereConstants.SEED_COUNT)
        // Entry 0 is the background so it wants the image's mean color. Its position doesn't matter
        seeds += VertexInfo(
            0, 0, width, height, meanColor(pixels), VertexInfo.SYNTHESIZED_POPULATION
        )

        for (i in 0 until AtmosphereConstants.BLOB_COUNT) {
            val across = (i + 0.5f) / AtmosphereConstants.BLOB_COUNT
            // Inset from the edges, alternating high and low, so the five land on a zigzag rather
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
