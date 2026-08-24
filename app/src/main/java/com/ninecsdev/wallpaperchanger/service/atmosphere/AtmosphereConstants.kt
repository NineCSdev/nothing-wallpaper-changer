package com.ninecsdev.wallpaperchanger.service.atmosphere

import android.view.animation.Interpolator
import android.view.animation.PathInterpolator

/**
 * Every tuning value the effect runs on.
 *
 * `animRatio` is 1.0 in the only shipped configuration, so each milestone below is its literal
 * multiplier. They are kept as the *products* rather than as `ratio * k` because there is exactly
 * one call site and no preset table (the effect has a single configuration).
 *
 * All absolute distances are in **1080-wide panel space**; see [renderRate].
 */
internal object AtmosphereConstants {

    // Frame milestones

    /** `uAlpha` reaches 1.0 here — barely a third into the morph. */
    const val RANDOMIZE_SHAPES_TRANSITION = 65

    /** Last frame on which a blob's outline grows. */
    const val POLYGON_CHANGE_END_FRAME = 100

    /** End of the morph. The settled state is a seek to [SETTLED_FRAME]. */
    const val TOTAL_ANIM_FRAMES = 185

    /** `uMixBlend` reaches 1.0 here — the photo is fully replaced by the background color. */
    const val BG_TEXTURE_TRANSITION = 135

    /** Blur ramp end. Also, via `* 0.1f`, the frame the render target collapses to 72 px. */
    const val BG_BLUR_GROWTH = 100

    /** Grain ramp end. The only window with a linear curve. */
    const val NOISE_GROWTH = 80

    /**
     * One past [TOTAL_ANIM_FRAMES]. Seeking here is how the engine draws its settled state
     * whenever it is not animating.
     */
    const val SETTLED_FRAME = TOTAL_ANIM_FRAMES + 1

    /**
     * Nothing blob-related is drawn below this frame, the render target is still half-resolution,
     * and the growth loop's one-off 30x kick lands exactly on it. Three separate behaviors share
     * this boundary.
     */
    const val FIRST_BLOB_FRAME = 10

    // Magnitudes

    /** Gaussian radius at full ramp. Clamped to [BLUR_RADIUS_LIMIT] upstream; 30 is well under. */
    const val MAX_BLUR_RADIUS = 30

    /** Per-tap step of the separable blur, in render-target pixels, at full ramp. */
    const val MAX_BLUR_OFFSET = 14f

    /** Grain mix at full ramp. */
    const val MAX_NOISE = 0.14f

    /** The blur shader's `uKernel` is declared at this size and always uploaded full. */
    const val BLUR_RADIUS_LIMIT = 124

    // Shape scale (ANIM_LEVEL 0; level 1 would be 0.8 / 0.05)

    /** Ceiling for the Bezier handle tension. */
    const val CURVE_SCALE_MAX = 0.7f

    /** Per-frame step toward [CURVE_SCALE_MAX] — ~16 frames to saturate. */
    const val CURVE_SCALE_STEP = 0.044f

    // Geometry

    /** Five blobs, five anchors each. Both are structural, not tunable. */
    const val BLOB_COUNT = 5
    const val ANCHOR_COUNT = 5

    /** Entry 0 of the seed list is the background color, so the array is one longer than [BLOB_COUNT]. */
    const val SEED_COUNT = BLOB_COUNT + 1

    /**
     * The base outline every blob starts from: a jittered pentagon roughly 20 units across,
     * as five (x, y) pairs.
     */
    val DEFAULT_VERTICES = floatArrayOf(
        8.5873f, 3.8233f,
        -0.7394f, 10.5742f,
        -7.5481f, 3.2040f,
        -6.1544f, -9.4770f,
        4.2499f, -6.3007f
    )

    /** Shoelace centroid of [DEFAULT_VERTICES], derived rather than stored. */
    private val DEFAULT_CENTROID: FloatArray = centroidOf(DEFAULT_VERTICES)

    val DEFAULT_CENTROID_X: Float = DEFAULT_CENTROID[0]
    val DEFAULT_CENTROID_Y: Float = DEFAULT_CENTROID[1]

    /**
     * Shoelace centroid of a closed polygon given as flat (x, y) pairs.
     *
     * Falls back to the arithmetic mean of the vertices when the polygon encloses no area, which a
     * shoelace centroid cannot describe (it divides by that area).
     */
    fun centroidOf(points: FloatArray): FloatArray {
        val n = points.size / 2
        var area = 0f
        var cx = 0f
        var cy = 0f
        for (i in 0 until n) {
            val x0 = points[i * 2]
            val y0 = points[i * 2 + 1]
            val x1 = points[((i + 1) % n) * 2]
            val y1 = points[((i + 1) % n) * 2 + 1]
            val cross = x0 * y1 - x1 * y0
            area += cross
            cx += (x0 + x1) * cross
            cy += (y0 + y1) * cross
        }
        area *= 0.5f
        if (area == 0f) {
            var mx = 0f
            var my = 0f
            for (i in 0 until n) {
                mx += points[i * 2]
                my += points[i * 2 + 1]
            }
            return floatArrayOf(mx / n, my / n)
        }
        return floatArrayOf(cx / (6f * area), cy / (6f * area))
    }

    /** Minimum spacing between blob targets, in **surface pixels**. */
    const val MIN_TARGET_SEPARATION = 500f

    /** Rejection-sampling budget before a candidate is accepted regardless. */
    const val PLACEMENT_MAX_ATTEMPTS = 100

    /** The one-off multiplier applied to every anchor's growth step on [FIRST_BLOB_FRAME]. */
    const val GROWTH_KICK = 30f

    /** Ceiling on tessellation segments per Bezier span; the floor is 3. */
    const val MAX_SEGMENTS_PER_SPAN = 16
    const val MIN_SEGMENTS_PER_SPAN = 3

    // Render targets

    /**
     * Width of the buffer the entire effect is composited, rasterized and blurred in from
     * [FIRST_BLOB_FRAME] onward. The height follows the surface aspect.
     *
     * This is load-bearing, not a performance shortcut: sigma = radius/3 = 10 texels here is a
     * blur ~14% of the frame width, and reproducing that at half resolution would need a radius
     * beyond [BLUR_RADIUS_LIMIT]. The bilinear magnify back to the panel is what turns five
     * flat-filled polygons into smooth gradients.
     */
    const val EFFECT_RASTER_WIDTH = 72

    /** Divisor for the render target used *below* [FIRST_BLOB_FRAME]. */
    const val PREROLL_DOWNSCALE = 2

    // Clock

    /**
     * The renderer self-clocks to this floor and re-arms itself with `requestRender()`, so the
     * morph lasts ~2.96 s on any panel
     */
    const val FRAME_INTERVAL_MS = 16L

    /** Reference panel width. Above this, [renderRate] is identically 1.0. */
    const val REFERENCE_WIDTH = 1080f

    // Easing

    /**
     * The animation curve, shared by every eased window (alpha, mix, travel, blur).
     * A strong ease-out: most of the change lands early.
     */
    val ANIM_EASE: Interpolator = PathInterpolator(0.15f, 0f, 0f, 1f)

    /** Used only by [renderRate] at `type == 1`. Not an animation curve. */
    private val RENDER_RATE_EASE: Interpolator = PathInterpolator(0f, 0f, 0.5f, 1f)

    /**
     * Resolution adaptation, not animation as [REFERENCE_WIDTH] is the panel the constants above
     * were authored against. Returns 1.0 on any panel at least that wide, so on the reference
     * device this is a no-op and every absolute distance is already in native units.
     *
     * Scales linearly below the reference width; [easedRenderRate] is the same adaptation on a
     * curve, and the two are not interchangeable.
     */
    fun renderRate(width: Int): Float {
        if (width > REFERENCE_WIDTH - 1f) return 1f
        return width / REFERENCE_WIDTH
    }

    /** [renderRate] through [RENDER_RATE_EASE]. Used by the grain ramp alone. */
    fun easedRenderRate(width: Int): Float {
        if (width > REFERENCE_WIDTH - 1f) return 1f
        return RENDER_RATE_EASE.getInterpolation(width / REFERENCE_WIDTH)
    }

    // Frame ramps

    /** Linear 0..1 progress of [frame] across the window `[from, to]`, clamped at both ends. */
    fun ramp(frame: Int, from: Int = 0, to: Int): Float =
        ((frame - from).toFloat() / (to - from)).coerceIn(0f, 1f)

    /** [ramp] through [ANIM_EASE], which is what every window but the grain uses. */
    fun easedRamp(frame: Int, from: Int = 0, to: Int): Float =
        ANIM_EASE.getInterpolation(ramp(frame, from, to))
}
