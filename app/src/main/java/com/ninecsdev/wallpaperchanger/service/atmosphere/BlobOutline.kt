package com.ninecsdev.wallpaperchanger.service.atmosphere

import kotlin.math.sqrt

/**
 * One blob's outline: five anchors that push outward every frame, smoothed into a closed cubic
 * Bezier and emitted as a triangle strip.
 *
 * The anchors live in the base pentagon's own space, centred on its shoelace centroid, so growth
 * here is independent of where the blob sits on screen — placement is entirely the caller's MVP.
 *
 * Two copies of the anchors are kept. The working set is what grows; the pristine set is what a
 * seek replays from. That is what makes `draw(n)` a real seek rather than an approximation, and
 * it is why the debug frame scrub can be trusted.
 */
internal class BlobOutline {

    /** Working anchors, five (x, y) pairs, mutated every frame. */
    private val anchors = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** Untouched copy of the base pentagon; a seek restores from this. */
    private val pristine = FloatArray(AtmosphereConstants.ANCHOR_COUNT * 2)

    /** Per-anchor radial growth rate, quantized to {2, 4, 6} and scaled by the render rate. */
    private val steps = FloatArray(AtmosphereConstants.ANCHOR_COUNT)

    /** Handle tension, ramping to [AtmosphereConstants.CURVE_SCALE_MAX] over ~16 frames. */
    private var curveScale = 0f

    /** Shoelace centroid of the base pentagon — the point growth radiates from. */
    private var centroidX = 0f
    private var centroidY = 0f

    /** Flat fill color, `pixelColor` of this blob's seed, unpacked to 0..1. */
    val color = FloatArray(3)

    /**
     * Scratch for the tessellated loop before it is walked into a strip.
     *
     * Held rather than allocated per call: this runs five times a frame for the length of a
     * 185-frame animation, and the one thing that must not vary is the frame pacing.
     */
    private val loop = FloatArray(MAX_STRIP_VERTICES * 2)

    fun reset(baseAnchors: FloatArray, growthSteps: FloatArray, blobColor: Int) {
        baseAnchors.copyInto(pristine)
        baseAnchors.copyInto(anchors)
        growthSteps.copyInto(steps)
        curveScale = 0f

        color[0] = ((blobColor shr 16) and 0xFF) / 255f
        color[1] = ((blobColor shr 8) and 0xFF) / 255f
        color[2] = (blobColor and 0xFF) / 255f

        computeCentroid()
    }

    /**
     * Brings the outline to [frame].
     *
     * @param isInit true to seek — restore the pristine anchors and replay every growth step from
     * [AtmosphereConstants.FIRST_BLOB_FRAME]. False to step a single frame forward.
     */
    fun advanceTo(frame: Int, isInit: Boolean) {
        if (isInit) {
            pristine.copyInto(anchors)
            val end = minOf(AtmosphereConstants.POLYGON_CHANGE_END_FRAME, frame)
            for (k in AtmosphereConstants.FIRST_BLOB_FRAME until end) push(k)
            val elapsed = (frame - AtmosphereConstants.FIRST_BLOB_FRAME + 1).coerceAtLeast(0)
            curveScale = (elapsed * AtmosphereConstants.CURVE_SCALE_STEP)
                .coerceAtMost(AtmosphereConstants.CURVE_SCALE_MAX)
        } else {
            if (frame >= AtmosphereConstants.FIRST_BLOB_FRAME &&
                frame < AtmosphereConstants.POLYGON_CHANGE_END_FRAME
            ) {
                push(frame)
            }
            curveScale = (curveScale + AtmosphereConstants.CURVE_SCALE_STEP)
                .coerceAtMost(AtmosphereConstants.CURVE_SCALE_MAX)
        }
    }

    /**
     * One frame of radial growth. The step on the very first frame is multiplied by
     * [AtmosphereConstants.GROWTH_KICK] — that single push is what establishes the blob's size,
     * and the ~90 ordinary pushes that follow only refine it.
     */
    private fun push(k: Int) {
        for (j in 0 until AtmosphereConstants.ANCHOR_COUNT) {
            var d = steps[j]
            if (k == AtmosphereConstants.FIRST_BLOB_FRAME) d *= AtmosphereConstants.GROWTH_KICK
            translateOutward(j, d)
        }
    }

    private fun translateOutward(index: Int, distance: Float) {
        val dx = anchors[index * 2] - centroidX
        val dy = anchors[index * 2 + 1] - centroidY
        val length = sqrt(dx * dx + dy * dy)
        if (length <= 0f) return
        anchors[index * 2] += dx / length * distance
        anchors[index * 2 + 1] += dy / length * distance
    }

    /** Shoelace centroid over the *base* anchors, computed once per reset. */
    private fun computeCentroid() {
        val n = AtmosphereConstants.ANCHOR_COUNT
        var area = 0f
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            val x0 = pristine[i * 2]
            val y0 = pristine[i * 2 + 1]
            val x1 = pristine[((i + 1) % n) * 2]
            val y1 = pristine[((i + 1) % n) * 2 + 1]
            val cross = x0 * y1 - x1 * y0
            area += cross
            cx += (x0 + x1) * cross
            cy += (y0 + y1) * cross
        }
        area *= 0.5f
        if (area == 0f) {
            centroidX = AtmosphereConstants.DEFAULT_CENTROID_X
            centroidY = AtmosphereConstants.DEFAULT_CENTROID_Y
            return
        }
        centroidX = cx / (6f * area)
        centroidY = cy / (6f * area)
    }

    /**
     * Tessellates the closed outline and writes it into [out] as a triangle strip, returning the
     * vertex count.
     *
     * The strip order is a parity walk over the outline — front, back, front, back — which fills a
     * closed loop without an index buffer or a centre vertex.
     *
     * @param coarseness 0 at the start of the morph, 1 at the end. The outline deliberately
     * *coarsens* as the animation runs.
     */
    fun tessellate(coarseness: Float, out: FloatArray): Int {
        val n = AtmosphereConstants.ANCHOR_COUNT
        val segments = (
            ((1f - coarseness) * AtmosphereConstants.MAX_SEGMENTS_PER_SPAN).toInt()
            ).coerceAtLeast(AtmosphereConstants.MIN_SEGMENTS_PER_SPAN)

        var w = 0
        for (i in 0 until n) {
            val p0x = anchors[i * 2]
            val p0y = anchors[i * 2 + 1]
            val p1x = anchors[((i + 1) % n) * 2]
            val p1y = anchors[((i + 1) % n) * 2 + 1]

            val prevX = anchors[((i - 1 + n) % n) * 2]
            val prevY = anchors[((i - 1 + n) % n) * 2 + 1]
            val nextX = anchors[((i + 2) % n) * 2]
            val nextY = anchors[((i + 2) % n) * 2 + 1]

            val c0x = p0x + (p1x - prevX) * curveScale
            val c0y = p0y + (p1y - prevY) * curveScale
            val c1x = p1x - (nextX - p0x) * curveScale
            val c1y = p1y - (nextY - p0y) * curveScale

            for (s in 0 until segments) {
                val u = s.toFloat() / segments
                val v = 1f - u
                val b0 = v * v * v
                val b1 = 3f * v * v * u
                val b2 = 3f * v * u * u
                val b3 = u * u * u
                loop[w++] = b0 * p0x + b1 * c0x + b2 * c1x + b3 * p1x
                loop[w++] = b0 * p0y + b1 * c0y + b2 * c1y + b3 * p1y
            }
        }

        return emitStrip(w / 2, out)
    }

    /**
     * Walks the closed loop alternately from both ends so consecutive triples form triangles that
     * tile the interior: `0, 1, last, 2, last-1, ...`
     */
    private fun emitStrip(pointCount: Int, out: FloatArray): Int {
        var head = 0
        var tail = pointCount - 1
        var w = 0
        var fromHead = true
        while (head <= tail) {
            val index = if (fromHead) head++ else tail--
            out[w++] = loop[index * 2]
            out[w++] = loop[index * 2 + 1]
            fromHead = !fromHead
        }
        return w / 2
    }

    /** Upper bound on [tessellate]'s vertex count, for one-time buffer sizing. */
    companion object {
        const val MAX_STRIP_VERTICES =
            AtmosphereConstants.ANCHOR_COUNT * AtmosphereConstants.MAX_SEGMENTS_PER_SPAN
    }
}
