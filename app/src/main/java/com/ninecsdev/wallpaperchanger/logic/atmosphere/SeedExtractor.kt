package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.core.graphics.ColorUtils
import androidx.palette.graphics.Palette
import com.ninecsdev.wallpaperchanger.service.atmosphere.AtmosphereConstants
import com.ninecsdev.wallpaperchanger.service.atmosphere.VertexInfo
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
 * The output is always exactly [AtmosphereConstants.SEED_COUNT] entries, sorted by population
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
        const val MAX_COLOR_COUNT = AtmosphereConstants.SEED_COUNT

        /**
         * Linear divisor applied before the scan, so the cost falls by its square.
         *
         * It trades color: downscaling pre-averages pixels, so the pixel chosen
         * as nearest to a swatch is slightly less extreme than the true one.
         *
         * Have tried 2 (gives more saturated blobs but takes ~300ms) and
         * 4 (gives a bit less saturated blobs but takes ~60ms)
         */
        const val SCAN_DOWNSCALE = 4

        /**
         * Half the presentation zoom Nothing OS applies to a wallpaper, the same figure
         * `BufferManager.ZOOM_INSET_FRACTION` pads against, restated here because stage 1 has no
         * business reaching into the buffer's internals to read it.
         */
        const val SCAN_ZOOM_INSET_FRACTION = 0.045f

        /**
         * Image is delivered un-zoomed, so without this crop the two analyses run on different framing of
         * the same photo.
         *
         * **One step per layer of framing between the delivered bitmap and the panel**, which is
         * why [scanZoom] takes an exponent rather than reading this constant directly. An unpadded
         * delivery is one step from the panel: the platform's own zoom. A zoom-fix-padded delivery
         * is two, because its padding exists precisely to be eaten by that zoom, so undoing it only
         * gets back to the un-zoomed framing and the platform's step still has to be applied on
         * top. Both cases land on the same pixels of the same photo, which is the point -- the
         * palette must not shift because the user changed a presentation setting.
         *
         * Nine percent sounds too small to matter and is not. Palette is a six-box median cut and a
         * swatch is its box's population-weighted mean, so on a photo built from a few near-equal
         * masses the split points sit on a knife edge: the 2026-08-23 `wallpaper1` case has its top
         * three swatches within 1% of each other in population. Un-zoomed, the black splatter shares
         * a box with the red and averages to a brown that then wins rank 0 and becomes the
         * background; at the OS's framing it shares a box with the navy instead, which yields a dark
         * purple background *and* frees a box for the magenta the OS visibly renders.
         *
         * Only the analysis is cropped. Anchors are still reported in the full bitmap's coordinates
         * (see [Scan]), because the composite draws the whole delivered bitmap and a blob has to
         * start where its color actually appears on the panel.
         */
        const val SCAN_ZOOM_STEP = 1f + 2f * SCAN_ZOOM_INSET_FRACTION

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
     * @param bitmap the fitted, edit-applied image that will be delivered to the engine, exactly
     * as the engine will receive it — padding included, if the user's zoom fix adds any.
     * @param padded whether [bitmap] carries zoom-fix padding, from
     * [BufferManager.hasZoomFixPadding][com.ninecsdev.wallpaperchanger.logic.BufferManager.hasZoomFixPadding].
     * It only sets how far the scan crops in (see [SCAN_ZOOM_STEP]); the padding itself is never
     * quantized either way, so the palette is identical whichever the user picked.
     */
    suspend fun extract(bitmap: Bitmap, padded: Boolean): List<VertexInfo> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()

        val scan = prepareScan(bitmap, scanZoom(padded))
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
                .toMutableList()

            if (swatches.isEmpty()) {
                Log.w(TAG, "Palette returned no swatches; falling back to a flat seed set")
                return@withContext flatFallback(scan)
            }

            val seeds = nearestPixelPerSwatch(scan, swatches)
            padToSeedCount(seeds, swatches)

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
     * The bitmap stage 1 actually reads, plus the mapping back to the one that gets drawn.
     *
     * [bitmap] is the centre `1 / scanZoom` of the delivered image, reduced by [SCAN_DOWNSCALE].
     * [referenceWidth] and [referenceHeight] are the *whole* delivered image at that same reduced
     * scale, and [offsetX] / [offsetY] locate the crop inside it -- so adding the offset to a
     * pixel's position in [bitmap] gives its position in the image the composite draws, which is
     * what [VertexInfo] has to carry.
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
     * Total inset from the delivered bitmap to the framing the panel shows: one
     * [SCAN_ZOOM_STEP] for the platform's zoom, plus another to undo zoom-fix padding when it is
     * present.
     */
    private fun scanZoom(padded: Boolean): Float =
        if (padded) SCAN_ZOOM_STEP * SCAN_ZOOM_STEP else SCAN_ZOOM_STEP

    /** Crops to the OS's framing and reduces, in one allocation. */
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
        // Filtered, because the reduction resamples either way and a nearest-neighbour one would
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
     * Tops the list up to [AtmosphereConstants.SEED_COUNT] when the photo quantised to fewer than
     * six colours, which happens on near-monochrome sources.
     *
     * Synthesised colours are the most populous swatch with its saturation walked away in 0.1
     * steps — half of them down, the rest up — wrapping by 0.6 when they run off either end.
     * They are placed at random positions, the only true randomness in the seed list.
     *
     * `swatches[0].hsl` hands back Palette's **own** array, and this re-fetches and mutates it in
     * place on every iteration so the steps compound instead of each starting from the base
     * saturation. That is deliberate and load-bearing: hoisting the fetch out of the loop, or
     * copying the array, changes the colors this produces.
     */
    private fun padToSeedCount(seeds: MutableList<VertexInfo>, swatches: MutableList<Palette.Swatch>) {
        val missing = AtmosphereConstants.SEED_COUNT - seeds.size
        if (missing <= 0) return

        val half = missing / 2
        val width = seeds[0].bitmapWidth
        val height = seeds[0].bitmapHeight

        for (k in half downTo 1) {
            val hsl = swatches[0].hsl
            hsl[1] -= k * 0.1f
            if (hsl[1] < 0f) hsl[1] += 0.6f
            appendSynthesised(seeds, swatches, ColorUtils.HSLToColor(hsl), width, height)
        }

        for (k in 1..(missing - half)) {
            val hsl = swatches[0].hsl
            hsl[1] += k * 0.1f
            if (hsl[1] > 1f) hsl[1] -= 0.6f
            appendSynthesised(seeds, swatches, ColorUtils.HSLToColor(hsl), width, height)
        }
    }

    private fun appendSynthesised(
        seeds: MutableList<VertexInfo>,
        swatches: MutableList<Palette.Swatch>,
        rgb: Int,
        width: Int,
        height: Int
    ) {
        swatches += Palette.Swatch(rgb, -1)
        seeds += VertexInfo(
            x = (Random.nextFloat() * width).toInt().coerceIn(0, width - 1),
            y = (Random.nextFloat() * height).toInt().coerceIn(0, height - 1),
            bitmapWidth = width,
            bitmapHeight = height,
            pixelColor = rgb,
            population = -1
        )
    }

    /**
     * Only reachable if the quantizer finds nothing at all — a zero-area or fully transparent
     * bitmap. Produces a valid six-entry list of mid-gray so the engine renders *something*
     * rather than reading a short array.
     */
    private fun flatFallback(scan: Scan): List<VertexInfo> {
        val grey = 0xFF808080.toInt()
        return List(AtmosphereConstants.SEED_COUNT) {
            VertexInfo(
                x = 0,
                y = 0,
                bitmapWidth = scan.referenceWidth.coerceAtLeast(1),
                bitmapHeight = scan.referenceHeight.coerceAtLeast(1),
                pixelColor = grey,
                population = -1
            )
        }
    }
}
