package com.ninecsdev.wallpaperchanger.logic

import kotlin.math.roundToInt

/**
 * The presentation zoom Nothing OS applies to a wallpaper, and every quantity derived from it.
 *
 * The platform enlarges a **static** wallpaper surface for its parallax effect and draws the
 * center of it, discarding the edges. It leaves a **live-wallpaper** surface alone.
 *
 * - [padInset]: how much to grow a static bitmap by so the platform's crop lands on the padding.
 * - [cropInset]: how much to crop a live-wallpaper bitmap by so it shows the same framing as static.
 * - [photoToDelivered]: where a coordinate in the photo ends up once [cropInset] has been applied.
 *
 * **Padding and cropping are inverse operations and do not share a fraction.** Reaching for one number
 * to do both is the mistake this module exists to make impossible.
 */
// TODO Tests specced in the vault: `tests/ParallaxZoom Tests.md`.
object ParallaxZoom {

    /** The factor the platform enlarges a static wallpaper surface by before drawing its center. */
    // Pinned on device on a 20:9 panel (1080x2400)
    const val ZOOM = 1.10f

    /**
     * Pixels to add to each edge of a static bitmap so the platform's crop consumes them exactly.
     *
     * Grows the image to [ZOOM] times its size, so that dividing by [ZOOM] returns the original.
     */
    fun padInset(size: Int): Int = insetPx(size, (ZOOM - 1f) / 2f)

    /**
     * Pixels to remove from each edge of a live-wallpaper bitmap to imitate the platform's crop.
     *
     * Shrinks the image to 1/[ZOOM] of its size, which is what the platform leaves visible. **This
     * is not [padInset], and the difference is not rounding**: growing by a factor and shrinking by
     * the same factor are different amounts of pixels.
     */
    fun cropInset(size: Int): Int = insetPx(size, (1f - 1f / ZOOM) / 2f)

    /**
     * Re-expresses [value], a coordinate along an axis of [extent] pixels in the photo, into the
     * bitmap that [cropInset] produced from it.
     *
     * A seed is two things with different owners: a **color**, which belongs to the photo and must
     * not move when a presentation setting changes, and a **position**, which is a coordinate into
     * the image that is actually drawn. Cropping changes *position* not *color*.
     *
     * The clamp is a guard rather than a working part: callers crop a wider window than they read
     * anchors from, so a mapped anchor already lands inside.
     */
    fun photoToDelivered(value: Int, extent: Int): Int {
        if (extent <= 1) return 0
        // Derived from cropInset so the mapping follows the crop that was actually performed.
        // The two disagreed below a pixel while one worked in whole pixels and the other in fractions.
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
