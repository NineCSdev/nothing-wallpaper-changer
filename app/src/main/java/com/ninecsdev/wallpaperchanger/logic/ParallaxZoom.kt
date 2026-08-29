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
// TODO Tests specced in the vault: `tests/ParallaxZoom.md`.
object ParallaxZoom {

    /**
     * Half of what the platform adds, expressed as a fraction of the image's own size.
     *
     * Observed on a 20:9 panel. Currently used for both the pad and the crop, which cannot both
     * be right — see the class note.
     */
    private const val INSET_FRACTION = 0.045f

    /** Pixels to add to each edge of a static bitmap so the platform's crop consumes them. */
    fun padInset(size: Int): Int = insetPx(size)

    /** Pixels to remove from each edge of a live-wallpaper bitmap to imitate the platform's crop. */
    fun cropInset(size: Int): Int = insetPx(size)

    /**
     * Re-expresses [value], a coordinate along an axis of [extent] pixels in the photo, into the
     * bitmap that [cropInset] produced from it.
     *
     * A seed is two things with different owners: a **color**, which belongs to the photo and must
     * not move when a presentation setting changes, and a **position**, which is a coordinate into
     * the image that is actually drawn. Cropping moves every point of the photo, so positions
     * follow it even though colors do not.
     *
     * The clamp is a guard rather than a working part: callers crop a wider window than they read
     * anchors from, so a mapped anchor already lands inside.
     */
    fun photoToDelivered(value: Int, extent: Int): Int {
        if (extent <= 1) return 0
        val kept = 1f - 2f * INSET_FRACTION
        val withinDelivered = (value.toFloat() / extent - INSET_FRACTION) / kept
        return (withinDelivered * extent).roundToInt().coerceIn(0, extent - 1)
    }

    /** Clamped so an inset can never consume the whole axis, however small the image. */
    private fun insetPx(size: Int): Int =
        (size * INSET_FRACTION).roundToInt()
            .coerceAtLeast(0)
            .coerceAtMost((size - 1) / 2)
}
