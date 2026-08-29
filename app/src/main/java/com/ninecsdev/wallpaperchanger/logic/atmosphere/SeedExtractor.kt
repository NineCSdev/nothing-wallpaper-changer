package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Stage 1: a photo becomes six colored seeds.
 *
 * This runs on the app side rather than inside the engine, keeps the cost off the wallpaper thread,
 * where it would show as a black frame at boot and on every surface recreate.
 *
 * The output is always exactly [VertexInfo.SEED_COUNT] entries, sorted by population
 * descending, and **entry 0 is the background color, not a blob** — see [VertexInfo].
 */
@Singleton
class SeedExtractor @Inject constructor() {

    private companion object {
        const val TAG = "SeedExtractor"

        /**
         * No filters and no targets means raw quantizer output: near-black, near-white and skin
         * tones are all eligible and there is no Vibrant/Muted bias. Both are deliberate — the
         * default filter would drop exactly the dark dominant colors this effect leans on.
         */
        const val MAX_COLOR_COUNT = VertexInfo.SEED_COUNT

        /**
         * Linear divisor applied before the scan, so the cost falls by its square.
         *
         * **It is not only a cost knob.** The reduction decides how many pixels the quantizer
         * sees and therefore where its median-cut boxes split, so it moves the *colors* the
         * palette reports -- not merely how extreme the pixel picked for each one is. It also
         * decides whether [Palette] resamples again: above its 112x112 budget it subsamples the
         * scan a second time with the filter off, and below that threshold it leaves it alone.
         *
         * Have tried 2 (more saturated blobs, ~300 ms) and 4 (a bit less saturated, ~60 ms).
         *
         * **16 was measured on device across four photos and rejected.**
         */
        const val SCAN_DOWNSCALE = 4

        /**
         * Half the presentation zoom Nothing OS applies to a wallpaper, the same figure
         * `BufferManager.ZOOM_INSET_FRACTION` pads against, restated here because stage 1 has no
         * business reaching into the buffer's internals to read it.
         */
        const val SCAN_ZOOM_INSET_FRACTION = 0.045f

        /**
         * One step of the analysis crop. [ANALYSIS_STEPS] of these is the framing the palette is
         * quantized at, whatever framing the delivery itself carries.
         */
        const val SCAN_ZOOM_STEP = 1f + 2f * SCAN_ZOOM_INSET_FRACTION

        /**
         * How far in the palette is measured, in [SCAN_ZOOM_STEP]s: the center ~84% of the photo.
         *
         * **Pinned by measurement, not derived.**
         *
         * **No mechanism is claimed.** One step would be the presentation zoom; nothing yet
         * explains the second, and inventing a story for it would be worse than admitting the gap.
         * Treat the number as load-bearing and unexplained: verify against a reference render
         * before changing it, and do not "simplify" it back to one step.
         *
         * Why so little slack: Palette is a six-box median cut and a swatch is its box's
         * population-weighted mean, so on a photo built from a few near-equal masses the split
         * points sit on a knife edge. The `wallpaper1` case has ranks 2 and 3 separated by 13
         * counts out of ~12,660 -- a few percent of framing either way moves a box, and moving a
         * box changes a color, its population rank, and therefore which blob is drawn over which.
         */
        const val ANALYSIS_STEPS = 2

        val SCAN_ZOOM = SCAN_ZOOM_STEP.pow(ANALYSIS_STEPS)

        // D65 white point and the pivot thresholds, matching androidx.core's ColorUtils exactly.
        const val WHITE_X = 95.047
        const val WHITE_Y = 100.0
        const val WHITE_Z = 108.883
        const val XYZ_EPSILON = 0.008856
        const val XYZ_KAPPA = 903.3

        /** sRGB -> linear for all 256 channel values. Exact, because the input is 8-bit. */
        val SRGB_TO_LINEAR = DoubleArray(256) { i ->
            val c = i / 255.0
            if (c < 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
    }

    /**
     * @param bitmap the fitted, edit-applied photo **before** framing. Framing is a presentation
     * choice shouldn't change the palette we create.
     */
    suspend fun extract(bitmap: Bitmap): List<VertexInfo> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()

        val scan = prepareScan(bitmap, SCAN_ZOOM)
        try {
            // Palette quantizes on its own 112x112 downscale regardless, so feeding it the same
            // reduced bitmap keeps the swatches and the pixels they are matched against consistent.
            val swatches = Palette.Builder(scan.bitmap)
                .maximumColorCount(MAX_COLOR_COUNT)
                .clearFilters()
                .clearTargets()
                .generate()
                .swatches
                .sortedByDescending { it.population }

            if (swatches.isEmpty()) {
                Log.w(TAG, "Palette returned no swatches; falling back to a flat seed set")
                return@withContext flatFallback(scan)
            }

            val seeds = nearestPixelPerSwatch(scan, swatches)
            padToSeedCount(seeds, swatches[0])

            Log.d(
                TAG,
                "Extracted ${seeds.size} seeds from ${scan.bitmap.width}x${scan.bitmap.height} " +
                    "in ${System.currentTimeMillis() - started} ms"
            )
            seeds
        } finally {
            scan.release()
        }
    }

    /**
     * The bitmap stage 1 actually reads, plus the mapping back to the photo it came from.
     *
     * [bitmap] is the centre `1 / scanZoom` of the photo, reduced by [SCAN_DOWNSCALE].
     * [referenceWidth] and [referenceHeight] are the *whole* photo at that same reduced scale, and
     * [offsetX] / [offsetY] locate the crop inside it so adding the offset to a pixel's position
     * in [bitmap] gives its position in the photo, which is the frame [VertexInfo] leaves here in.
     *
     * Note that is the *photo's* frame, not the delivered image's..
     */
    private data class Scan(
        val bitmap: Bitmap,
        val offsetX: Int,
        val offsetY: Int,
        val referenceWidth: Int,
        val referenceHeight: Int,
        val owned: Boolean
    ) {
        /** No-ops if crop and reduction both came out as identity, so the caller's bitmap is safe. */
        fun release() {
            if (owned) bitmap.recycle()
        }
    }

    /**
     * Crops to the analysis framing and reduces, in one allocation.
     *
     * **Only the analysis is cropped.** Anchors come back in the full bitmap's coordinates (see
     * [Scan]), because the composite draws the whole image and a blob has to start where its color
     * actually appears in it.
     */
    private fun prepareScan(bitmap: Bitmap, scanZoom: Float): Scan {
        val cropWidth = (bitmap.width / scanZoom).roundToInt().coerceIn(1, bitmap.width)
        val cropHeight = (bitmap.height / scanZoom).roundToInt().coerceIn(1, bitmap.height)
        val cropX = (bitmap.width - cropWidth) / 2
        val cropY = (bitmap.height - cropHeight) / 2

        val scanWidth = (cropWidth / SCAN_DOWNSCALE).coerceAtLeast(1)
        val scanHeight = (cropHeight / SCAN_DOWNSCALE).coerceAtLeast(1)

        val matrix = Matrix().apply {
            setScale(scanWidth.toFloat() / cropWidth, scanHeight.toFloat() / cropHeight)
        }
        // Filtered, because the reduction resamples either way and a nearest-neighbor one would
        // hand the palette whichever pixels the grid happened to land on.
        val scan = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight, matrix, true)

        val referenceWidth =
            (bitmap.width.toFloat() * scanWidth / cropWidth).roundToInt().coerceAtLeast(scanWidth)
        val referenceHeight =
            (bitmap.height.toFloat() * scanHeight / cropHeight).roundToInt().coerceAtLeast(scanHeight)

        return Scan(
            bitmap = scan,
            offsetX = (referenceWidth - scanWidth) / 2,
            offsetY = (referenceHeight - scanHeight) / 2,
            referenceWidth = referenceWidth,
            referenceHeight = referenceHeight,
            owned = scan !== bitmap
        )
    }

    /**
     * For each swatch, the single pixel of the whole bitmap closest to it in CIE-LAB.
     *
     * That pixel's position is what anchors the blob at the start of the morph, so a blob really
     * does begin where its color lives in the photo; its raw color, not the swatch's quantized
     * one, is what gets drawn.
     *
     * O(pixels x swatches), which is why this is a background-thread, once-per-image cost.
     */
    private fun nearestPixelPerSwatch(
        scan: Scan,
        swatches: List<Palette.Swatch>
    ): MutableList<VertexInfo> {
        val bitmap = scan.bitmap
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val swatchLab = Array(swatches.size) { DoubleArray(3) }
        for (i in swatches.indices) colorToLab(swatches[i].rgb, swatchLab[i])

        val bestDistance = DoubleArray(swatches.size) { Double.MAX_VALUE }
        val bestIndex = IntArray(swatches.size) { -1 }
        val pixelLab = DoubleArray(3)

        for (p in pixels.indices) {
            colorToLab(pixels[p], pixelLab)
            for (s in swatches.indices) {
                val lab = swatchLab[s]
                val dl = pixelLab[0] - lab[0]
                val da = pixelLab[1] - lab[1]
                val db = pixelLab[2] - lab[2]
                // Squared, not the true distance: we only ever take an argmin, and squaring is
                // monotonic, so the winner is identical while six square roots per pixel are not.
                val distance = dl * dl + da * da + db * db
                if (distance < bestDistance[s]) {
                    bestDistance[s] = distance
                    bestIndex[s] = p
                }
            }
        }

        return swatches.indices.mapTo(mutableListOf()) { s ->
            val index = bestIndex[s].coerceAtLeast(0)
            VertexInfo(
                x = scan.offsetX + index % width,
                y = scan.offsetY + index / width,
                bitmapWidth = scan.referenceWidth,
                bitmapHeight = scan.referenceHeight,
                pixelColor = pixels[index],
                population = swatches[s].population
            )
        }
    }

    /**
     * CIE-LAB conversion, numerically identical to `ColorUtils.colorToLAB` but affordable to run
     * a few million times.
     *
     * The library version costs **24 `Math.pow` calls per pixel** on this path: three to
     * linearise sRGB, three for the cube-root pivot, and — the surprise — eighteen inside
     * `distanceEuclidean`, which squares its terms with `Math.pow(x, 2)` and is called once per
     * swatch. Over a screen-sized bitmap that is upwards of sixty million `pow` calls, which is
     * where the six and a half seconds went.
     *
     * Two changes, both exact rather than approximations. The sRGB linearisation depends only on
     * an 8-bit channel, so all 256 answers are precomputed once. And the caller compares squared
     * distances (above). Only the pivot's cube root survives, and it is deliberately still
     * `Math.pow(x, 1/3.0)` rather than `cbrt` so the values match the library bit for bit.
     */
    private fun colorToLab(color: Int, outLab: DoubleArray) {
        val sr = SRGB_TO_LINEAR[(color shr 16) and 0xFF]
        val sg = SRGB_TO_LINEAR[(color shr 8) and 0xFF]
        val sb = SRGB_TO_LINEAR[color and 0xFF]

        val x = pivot(100 * (sr * 0.4124 + sg * 0.3576 + sb * 0.1805) / WHITE_X)
        val y = pivot(100 * (sr * 0.2126 + sg * 0.7152 + sb * 0.0722) / WHITE_Y)
        val z = pivot(100 * (sr * 0.0193 + sg * 0.1192 + sb * 0.9505) / WHITE_Z)

        outLab[0] = max(0.0, 116 * y - 16)
        outLab[1] = 500 * (x - y)
        outLab[2] = 200 * (y - z)
    }

    private fun pivot(component: Double): Double =
        if (component > XYZ_EPSILON) {
            component.pow(1 / 3.0)
        } else {
            (XYZ_KAPPA * component + 16) / 116
        }

    /**
     * Tops the list up to [VertexInfo.SEED_COUNT] when the photo quantized to fewer than
     * six colors, which happens on near-monochrome sources.
     *
     * Synthesised colors are the most populous swatch with its saturation walked away in 0.1
     * steps — half of them down, the rest up — wrapping by 0.6 when they run off either end.
     * They are placed at random positions, the only true randomness in the seed list.
     *
     * Every step is measured from the base saturation, not from the previous step: the offsets
     * are k * 0.1 with k counting away from the base in both directions, so the pair either side
     * of it is the nearest and the outermost is the furthest.
     *
     * The copies matter. `Swatch.hsl` hands back the swatch's own array, and writing to it would
     * leave the caller's swatch holding a saturation it never had.
     */
    private fun padToSeedCount(seeds: MutableList<VertexInfo>, base: Palette.Swatch) {
        val missing = VertexInfo.SEED_COUNT - seeds.size
        if (missing <= 0) return

        val half = missing / 2
        val width = seeds[0].bitmapWidth
        val height = seeds[0].bitmapHeight
        val baseHsl = base.hsl.copyOf()

        for (k in half downTo 1) {
            val hsl = baseHsl.copyOf()
            hsl[1] -= k * 0.1f
            if (hsl[1] < 0f) hsl[1] += 0.6f
            appendSynthesised(seeds, ColorUtils.HSLToColor(hsl), width, height)
        }

        for (k in 1..(missing - half)) {
            val hsl = baseHsl.copyOf()
            hsl[1] += k * 0.1f
            if (hsl[1] > 1f) hsl[1] -= 0.6f
            appendSynthesised(seeds, ColorUtils.HSLToColor(hsl), width, height)
        }
    }

    private fun appendSynthesised(
        seeds: MutableList<VertexInfo>,
        rgb: Int,
        width: Int,
        height: Int
    ) {
        seeds += VertexInfo(
            x = (Random.nextFloat() * width).toInt().coerceIn(0, width - 1),
            y = (Random.nextFloat() * height).toInt().coerceIn(0, height - 1),
            bitmapWidth = width,
            bitmapHeight = height,
            pixelColor = rgb,
            population = VertexInfo.SYNTHESIZED_POPULATION
        )
    }

    /**
     * Only reachable if the quantizer finds nothing at all — a zero-area or fully transparent
     * bitmap. Produces a valid six-entry list of mid-gray so the engine renders *something*
     * rather than reading a short array.
     */
    private fun flatFallback(scan: Scan): List<VertexInfo> {
        val grey = 0xFF808080.toInt()
        return List(VertexInfo.SEED_COUNT) {
            VertexInfo(
                x = 0,
                y = 0,
                bitmapWidth = scan.referenceWidth.coerceAtLeast(1),
                bitmapHeight = scan.referenceHeight.coerceAtLeast(1),
                pixelColor = grey,
                population = VertexInfo.SYNTHESIZED_POPULATION
            )
        }
    }
}
