package com.ninecsdev.wallpaperchanger.logic

import kotlin.math.roundToInt

/**
 * Nothing OS enlarges a **static** wallpaper surface for parallax and draws its center, discarding
 * the edges; a **live-wallpaper** surface is left alone.
 *
 * Padding and cropping are inverse operations and do not share a fraction.
 */
object ParallaxZoom {

    // Measured on a 20:9 panel (1080x2400)
    const val ZOOM = 1.10f

    /** Pixels to add to each edge of a static bitmap so the platform's crop consumes them exactly. */
    fun padInset(size: Int): Int = insetPx(size, (ZOOM - 1f) / 2f)

    /** Pixels to remove from each edge of a live-wallpaper bitmap to match the static framing (1/[ZOOM] of its size). Not equal to [padInset]. */
    fun cropInset(size: Int): Int = insetPx(size, (1f - 1f / ZOOM) / 2f)

    /**
     * Maps [value], a coordinate along an axis of [extent] photo pixels, into the bitmap [cropInset]
     * produced. Cropping moves a seed's position, never its color. The clamp is only a guard:
     * callers read anchors from a narrower window than they crop.
     */
    fun photoToDelivered(value: Int, extent: Int): Int {
        if (extent <= 1) return 0
        // Derived from cropInset (whole pixels) so the mapping matches the crop actually performed
        val inset = cropInset(extent)
        val kept = extent - 2 * inset
        if (kept <= 0) return 0
        return ((value - inset).toFloat() * extent / kept).roundToInt().coerceIn(0, extent - 1)
    }

    /** Clamped so an inset can never consume the whole axis, however small the image. */
    private fun insetPx(size: Int, fraction: Float): Int =
        (size * fraction).roundToInt()
            .coerceAtLeast(0)
            .coerceAtMost((size - 1) / 2)
}
