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
 * Turns a photo into exactly [VertexInfo.SEED_COUNT] seeds sorted by population descending (entry 0
 * is the background, see [VertexInfo]). Runs app-side so the cost stays off the engine's thread,
 * where it would show as a black frame at boot and on every surface recreate.
 */
@Singleton
class SeedExtractor @Inject constructor() {

    private companion object {
        const val TAG = "SeedExtractor"

        // Used with no filters or targets (raw quantizer output): the default filter would
        // drop exactly the dark dominant colors this effect leans on
        const val MAX_COLOR_COUNT = VertexInfo.SEED_COUNT

        /**
         * Linear divisor applied before the scan. Not only a cost knob: it shifts the quantizer's
         * median-cut splits and so the colors reported. 2 gave more saturated blobs (~300 ms),
         * 4 less (~60 ms); 16 was measured on device and rejected.
         */
        const val SCAN_DOWNSCALE = 4

        // Empirical, not the platform parallax zoom (measured 1.10): do not derive one from the other
        const val SCAN_ZOOM_STEP = 1.09f

        /**
         * Steps of [SCAN_ZOOM_STEP] the palette is measured at (the center ~84% of the photo).
         * Empirical and load-bearing: near-equal color masses sit on a median-cut knife edge, so a
         * few percent of framing can change a color's rank. Verify against a reference render before changing.
         */
        const val ANALYSIS_STEPS = 2

        val SCAN_ZOOM = SCAN_ZOOM_STEP.pow(ANALYSIS_STEPS)

        // D65 white point and the pivot thresholds, matching androidx.core's ColorUtils exactly.
        const val WHITE_X = 95.047
        const val WHITE_Y = 100.0
        const val WHITE_Z = 108.883
        const val XYZ_EPSILON = 0.008856
        const val XYZ_KAPPA = 903.3

        val SRGB_TO_LINEAR = DoubleArray(256) { i ->
            val c = i / 255.0
            if (c < 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        }
    }

    /** [bitmap] is the fitted, edit-applied photo before framing: framing must not change the palette. */
    suspend fun extract(bitmap: Bitmap): List<VertexInfo> = withContext(Dispatchers.Default) {
        val started = System.currentTimeMillis()

        val scan = prepareScan(bitmap, SCAN_ZOOM)
        try {
            // Palette downscales to 112x112 regardless; feeding it the reduced bitmap keeps
            // swatches and the pixels they are matched against consistent
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
     * [bitmap] is the center `1 / scanZoom` of the photo, reduced by [SCAN_DOWNSCALE]. [referenceWidth] and
     * [referenceHeight] are the whole photo at that scale; adding [offsetX]/[offsetY] to a position in
     * [bitmap] gives its position in the photo (not in the delivered image).
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

    /** Only the analysis is cropped: anchors stay in the full bitmap's coordinates because the composite draws the whole image. */
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
     * For each swatch, the pixel closest to it in CIE-LAB. Its position anchors the blob and its raw
     * color (not the swatch's quantized one) is what is drawn. O(pixels x swatches): background only.
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
     * Numerically identical to `ColorUtils.colorToLAB` but avoids its ~24 `Math.pow` calls per pixel
     * (sRGB via lookup table, squared distances in the caller). The pivot keeps `pow(x, 1/3.0)`, not
     * `cbrt`, to match it bit for bit.
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
     * Tops up near-monochrome sources: the base swatch's saturation walked in 0.1 steps from the base
     * (half down, the rest up, wrapping by 0.6), at random positions. `Swatch.hsl` is the swatch's
     * own array, so it is copied.
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

    /** Only reachable on a zero-area or fully transparent bitmap; mid-gray keeps the list at six entries. */
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
