package com.ninecsdev.wallpaperchanger.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Pins the framing math shared by the editor, the thumbnails and the render path.
 *
 * The property these cases exist for is that **the preview and the wallpaper agree**: the same
 * framing has to come out whether the caller describes its content as an aspect ratio or as pixel
 * dimensions, and whatever resolution it decoded at.
 */
class EditTransformTest {

    /** Generous enough for float accumulation, tight enough that a real drift of a pixel fails. */
    private val tolerance = 0.01f

    private fun assertClose(message: String, expected: Float, actual: Float, eps: Float = tolerance) {
        assertTrue(
            "$message: expected $expected, was $actual",
            abs(expected - actual) <= eps
        )
    }

    /** The panel the app renders for, plus containers that are not round numbers. */
    private val aspects = listOf(0.5f, 9f / 19.5f, 1f, 4f / 3f, 1.777f, 2.35f)
    private val zooms = listOf(1f, 1.25f, 2f, 3.5f, 5f)

    // ---- Fit geometry ----

    @Test
    fun `wide content in a tall container is letterboxed`() {
        val t = computeEditTransform(200f, 100f, 100f, 200f, zoom = 1f, offsetX = 0f, offsetY = 0f)

        // Width binds: 100/200 = 0.5, so the content lands 100x50 and centres at y = 75.
        assertClose("scale", 0.5f, t.scale)
        assertClose("drawX", 0f, t.drawX)
        assertClose("drawY", 75f, t.drawY)
    }

    @Test
    fun `tall content in a wide container is pillarboxed`() {
        val t = computeEditTransform(100f, 200f, 200f, 100f, zoom = 1f, offsetX = 0f, offsetY = 0f)

        assertClose("scale", 0.5f, t.scale)
        assertClose("drawX", 75f, t.drawX)
        assertClose("drawY", 0f, t.drawY)
    }

    @Test
    fun `content matching the container fills it exactly`() {
        val t = computeEditTransform(1080f, 2400f, 540f, 1200f, zoom = 1f, offsetX = 0f, offsetY = 0f)

        assertClose("drawX", 0f, t.drawX)
        assertClose("drawY", 0f, t.drawY)
        assertFalse("a fitted image has no pan to give", t.canPanX)
        assertFalse(t.canPanY)
    }

    // ---- Zoom and pan-range semantics ----

    @Test
    fun `at zoom one there is no pan whatever the offsets say`() {
        // The fitted content never overflows, so both ranges clamp to zero and a stored offset from
        // some other zoom cannot leak into the framing.
        for (offset in listOf(-1f, -0.5f, 0f, 0.37f, 1f)) {
            val t = computeEditTransform(200f, 100f, 100f, 200f, 1f, offset, offset)

            assertClose("panX at offset $offset", 0f, t.panX)
            assertClose("panY at offset $offset", 0f, t.panY)
        }
    }

    @Test
    fun `a full offset lands the content edge exactly on the container edge`() {
        val containerW = 1080f
        val containerH = 2400f
        val t = computeEditTransform(1080f, 2400f, containerW, containerH, zoom = 2f, offsetX = 1f, offsetY = 1f)

        // Panning to the limit brings the content's top-left to the container's, never past it.
        assertClose("drawX", 0f, t.drawX)
        assertClose("drawY", 0f, t.drawY)

        val mirrored = computeEditTransform(1080f, 2400f, containerW, containerH, 2f, -1f, -1f)
        assertClose("drawX at -1", containerW - 1080f * mirrored.scale, mirrored.drawX)
        assertClose("drawY at -1", containerH - 2400f * mirrored.scale, mirrored.drawY)
    }

    @Test
    fun `each axis clamps independently`() {
        // A panorama in a tall container: zooming past the fit overflows width long before height.
        val t = computeEditTransform(2000f, 1000f, 1080f, 2400f, zoom = 1.5f, offsetX = 1f, offsetY = 1f)

        assertTrue("the wide axis should have pan", t.canPanX)
        assertFalse("the tall axis should not", t.canPanY)
        assertClose("panY", 0f, t.panY)
        assertTrue("panX", t.panX > 0f)
    }

    @Test
    fun `a zero offset keeps the content centred at any zoom`() {
        for (zoom in zooms) {
            val t = computeEditTransform(1200f, 800f, 1080f, 2400f, zoom, 0f, 0f)

            assertClose("drawX at zoom $zoom", (1080f - 1200f * t.scale) / 2f, t.drawX)
            assertClose("drawY at zoom $zoom", (2400f - 800f * t.scale) / 2f, t.drawY)
        }
    }

    // ---- Caller-parameterization equivalence: the drift guard ----

    @Test
    fun `describing the content by aspect or by pixels gives the same framing`() {
        // The editor passes (aspect, 1f); the render path passes source pixel dimensions. If these
        // two ever disagreed, the wallpaper would not match what the user framed.
        for (aspect in aspects) {
            for (zoom in zooms) {
                val srcHeight = 1731f
                val byPixels = computeEditTransform(aspect * srcHeight, srcHeight, 1080f, 2400f, zoom, 0.4f, -0.8f)
                val byAspect = computeEditTransform(aspect, 1f, 1080f, 2400f, zoom, 0.4f, -0.8f)

                val label = "aspect $aspect zoom $zoom"
                assertClose("$label panX", byPixels.panX, byAspect.panX)
                assertClose("$label panY", byPixels.panY, byAspect.panY)
                assertClose("$label drawX", byPixels.drawX, byAspect.drawX)
                assertClose("$label drawY", byPixels.drawY, byAspect.drawY)
            }
        }
    }

    @Test
    fun `the framing does not depend on the resolution the content decoded at`() {
        // BufferManager decodes oversampled. Doubling the pixel dimensions must halve the scale and
        // leave everything the user can see untouched.
        for (zoom in zooms) {
            val once = computeEditTransform(900f, 1600f, 1080f, 2400f, zoom, -0.6f, 0.25f)
            val twice = computeEditTransform(1800f, 3200f, 1080f, 2400f, zoom, -0.6f, 0.25f)

            assertClose("scale at zoom $zoom", once.scale / 2f, twice.scale)
            assertClose("panX at zoom $zoom", once.panX, twice.panX)
            assertClose("panY at zoom $zoom", once.panY, twice.panY)
            assertClose("drawX at zoom $zoom", once.drawX, twice.drawX)
            assertClose("drawY at zoom $zoom", once.drawY, twice.drawY)
        }
    }

    // ---- The pan inversion ----

    @Test
    fun `a pixel pan inverts back to the offset it came from`() {
        // The gesture code depends on this round-trip and used to hand-roll it.
        for (offset in listOf(-1f, -0.75f, -0.2f, 0f, 0.33f, 1f)) {
            val t = computeEditTransform(1200f, 1200f, 1080f, 2400f, zoom = 3f, offsetX = offset, offsetY = offset)

            assertClose("offsetX from $offset", offset, t.offsetXFor(t.panX, -99f))
            assertClose("offsetY from $offset", offset, t.offsetYFor(t.panY, -99f))
        }
    }

    @Test
    fun `the inversion clamps instead of overshooting`() {
        val t = computeEditTransform(1200f, 1200f, 1080f, 2400f, zoom = 3f, offsetX = 0f, offsetY = 0f)

        assertEquals(1f, t.offsetXFor(t.panX + 10_000f, -99f), 0f)
        assertEquals(-1f, t.offsetXFor(t.panX - 10_000f, -99f), 0f)
        assertEquals(1f, t.offsetYFor(t.panY + 10_000f, -99f), 0f)
        assertEquals(-1f, t.offsetYFor(t.panY - 10_000f, -99f), 0f)
    }

    @Test
    fun `an axis with no room returns the fallback untouched`() {
        // A pinch passing through a non-overflowing zoom must not destroy the framing stored for
        // the zooms that do overflow, and must not divide by zero reaching that conclusion.
        val t = computeEditTransform(200f, 100f, 100f, 200f, zoom = 1f, offsetX = 0f, offsetY = 0f)

        assertEquals(0.42f, t.offsetXFor(500f, 0.42f), 0f)
        assertEquals(-0.31f, t.offsetYFor(-500f, -0.31f), 0f)
    }

    @Test
    fun `canPan agrees with the inversion on every axis`() {
        val sentinel = 0.1234f
        for (aspect in aspects) {
            for (zoom in zooms) {
                val t = computeEditTransform(aspect, 1f, 1080f, 2400f, zoom, 0.5f, 0.5f)

                val label = "aspect $aspect zoom $zoom"
                assertEquals("$label X", t.canPanX, t.offsetXFor(0f, sentinel) != sentinel)
                assertEquals("$label Y", t.canPanY, t.offsetYFor(0f, sentinel) != sentinel)
            }
        }
    }

    // ---- computeLayerTransform: the surfaces that fit before they transform ----

    @Test
    fun `a container shaped like the canvas gets the zoom as its scale`() {
        // This is what makes the editor's hand-substituted "scale = zoom" a consequence of the
        // shared math rather than a second rule that can drift from it.
        val canvasW = 1080f
        val canvasH = 2400f
        for (aspect in aspects) {
            for (zoom in zooms) {
                for (multiple in listOf(1f, 0.25f, 3f)) {
                    val layer = computeLayerTransform(
                        contentAspect = aspect,
                        containerWidth = canvasW * multiple,
                        containerHeight = canvasH * multiple,
                        canvasWidth = canvasW,
                        canvasHeight = canvasH,
                        zoom = zoom,
                        offsetX = 0.3f,
                        offsetY = -0.7f,
                    )
                    val direct = computeEditTransform(aspect, 1f, canvasW * multiple, canvasH * multiple, zoom, 0.3f, -0.7f)

                    val label = "aspect $aspect zoom $zoom x$multiple"
                    assertClose("$label scale", zoom, layer.scale)
                    assertClose("$label translationX", direct.panX, layer.translationX)
                    assertClose("$label translationY", direct.panY, layer.translationY)
                }
            }
        }
    }

    @Test
    fun `a square cell onto a tall canvas crops top and bottom`() {
        // Hand-derived: canvas 1080x2400, cell 360x360, content aspect 0.5 at zoom 1.
        // The cell shows a 360x800 window, in which the content is drawn 360x720.
        val layer = computeLayerTransform(
            contentAspect = 0.5f,
            containerWidth = 360f,
            containerHeight = 360f,
            canvasWidth = 1080f,
            canvasHeight = 2400f,
            zoom = 1f,
            offsetX = 0f,
            offsetY = 0f,
        )

        // The container had already fitted the content to 180x360; the layer scale takes it to
        // 360x720, filling the cell's width and overflowing its height symmetrically.
        val fittedWidth = min(360f / 0.5f, 360f) * 0.5f
        val fittedHeight = min(360f / 0.5f, 360f)
        assertClose("scale", 2f, layer.scale)
        assertClose("drawn width", 360f, fittedWidth * layer.scale)
        assertClose("drawn height", 720f, fittedHeight * layer.scale)
        assertClose("translationX", 0f, layer.translationX)
        assertClose("translationY", 0f, layer.translationY)
    }

    @Test
    fun `what the layer puts on screen does not depend on the container's shape`() {
        // Every surface shows a centred window onto the same canvas framing, so the drawn size and
        // the pan are the canvas-space values scaled by the container's cover factor and nothing
        // else. Containers of wildly different aspects must agree once that factor is divided out.
        val canvasW = 1080f
        val canvasH = 2400f
        val containers = listOf(
            360f to 360f,
            1080f to 2400f,
            2400f to 1080f,
            300f to 900f,
            777f to 501f,
        )

        for (aspect in aspects) {
            for (zoom in zooms) {
                var reference: Triple<Float, Float, Float>? = null
                for ((w, h) in containers) {
                    val layer = computeLayerTransform(aspect, w, h, canvasW, canvasH, zoom, 0.45f, -0.6f)

                    val cover = max(w / canvasW, h / canvasH)
                    val containerFit = min(w / aspect, h)
                    val inCanvasUnits = Triple(
                        layer.scale * containerFit / cover,
                        layer.translationX / cover,
                        layer.translationY / cover,
                    )

                    val label = "aspect $aspect zoom $zoom container ${w}x$h"
                    if (reference == null) {
                        reference = inCanvasUnits
                    } else {
                        assertClose("$label drawn size", reference.first, inCanvasUnits.first, 0.05f)
                        assertClose("$label panX", reference.second, inCanvasUnits.second, 0.05f)
                        assertClose("$label panY", reference.third, inCanvasUnits.third, 0.05f)
                    }
                }
            }
        }
    }
}
