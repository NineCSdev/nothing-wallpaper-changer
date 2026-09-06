package com.ninecsdev.wallpaperchanger.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Pins the pad/crop split in [ParallaxZoom].
 *
 * Growing an axis by a factor and shrinking it by the same factor are different amounts of pixels.
 * Serving both from one figure is the mistake these cases exist to catch.
 *
 * Everything is asserted against [ParallaxZoom.ZOOM] rather than its literal value.
 */
class ParallaxZoomTest {

    /** The panel axes the app renders for, plus sizes that are not round numbers. */
    private val axes = listOf(1080, 2400, 720, 1440, 1600, 2160, 3120, 999, 1237)

    // --- The invariant that matters ---

    @Test
    fun `padding round-trips through the zoom`() {
        for (size in axes) {
            val padded = size + 2 * ParallaxZoom.padInset(size)
            val drawn = padded / ParallaxZoom.ZOOM
            assertTrue(
                "padInset($size) grew the axis to $padded, drawn back as $drawn",
                abs(drawn - size) <= 1f
            )
        }
    }

    @Test
    fun `cropping matches the fraction the platform leaves visible`() {
        for (size in axes) {
            val cropped = size - 2 * ParallaxZoom.cropInset(size)
            val keptFraction = cropped.toFloat() / size
            val platformFraction = 1f / ParallaxZoom.ZOOM
            assertTrue(
                "cropInset($size) kept $cropped px, a fraction of $keptFraction",
                abs(keptFraction - platformFraction) * size <= 1f
            )
        }
    }

    @Test
    fun `the two insets are not the same number`() {
        // The tall axis, where the gap is widest.
        assertEquals(120, ParallaxZoom.padInset(2400))
        assertEquals(109, ParallaxZoom.cropInset(2400))
        // And the short one, so a width-only reading of this file still shows the split.
        assertEquals(54, ParallaxZoom.padInset(1080))
        assertEquals(49, ParallaxZoom.cropInset(1080))
    }

    // ---- Anchor mapping ----

    @Test
    fun `the crop's own corners map to the delivered corners`() {
        for (extent in axes) {
            val inset = ParallaxZoom.cropInset(extent)
            assertEquals(
                "leading corner of a $extent px axis",
                0,
                ParallaxZoom.photoToDelivered(inset, extent)
            )
            assertEquals(
                "trailing corner of a $extent px axis",
                extent - 1,
                ParallaxZoom.photoToDelivered(extent - inset, extent)
            )
        }
    }

    @Test
    fun `the centre of the axis does not move`() {
        for (extent in axes) {
            assertEquals(
                "centre of a $extent px axis",
                extent / 2,
                ParallaxZoom.photoToDelivered(extent / 2, extent)
            )
        }
    }

    @Test
    fun `the mapping is monotonic across the axis`() {
        for (extent in listOf(1080, 2400, 999)) {
            var previous = ParallaxZoom.photoToDelivered(0, extent)
            for (value in 1 until extent) {
                val mapped = ParallaxZoom.photoToDelivered(value, extent)
                assertTrue(
                    "photoToDelivered folded back at $value on a $extent px axis: $previous -> $mapped",
                    mapped >= previous
                )
                previous = mapped
            }
        }
    }

    // ---- Degenerate sizes ----

    @Test
    fun `an axis of one pixel or less maps to zero rather than dividing by it`() {
        assertEquals(0, ParallaxZoom.photoToDelivered(0, 1))
        assertEquals(0, ParallaxZoom.photoToDelivered(0, 0))
        assertEquals(0, ParallaxZoom.photoToDelivered(5, -3))
    }

    @Test
    fun `an inset never consumes the whole axis`() {
        for (size in 1..8) {
            assertTrue(
                "padInset($size) = ${ParallaxZoom.padInset(size)}",
                2 * ParallaxZoom.padInset(size) < size
            )
            assertTrue(
                "cropInset($size) = ${ParallaxZoom.cropInset(size)}",
                2 * ParallaxZoom.cropInset(size) < size
            )
        }
    }

    @Test
    fun `zero and negative sizes do not throw`() {
        ParallaxZoom.padInset(0)
        ParallaxZoom.cropInset(0)
        ParallaxZoom.padInset(-10)
        ParallaxZoom.cropInset(-10)
    }
}
