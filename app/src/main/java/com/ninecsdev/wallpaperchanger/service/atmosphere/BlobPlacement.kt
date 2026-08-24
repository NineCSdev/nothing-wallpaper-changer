package com.ninecsdev.wallpaperchanger.service.atmosphere

import java.util.Random

/**
 * Picks where the blobs drift *to*.
 *
 * Poisson-disc-ish rejection sampling over the surface: candidates land uniformly, and any
 * candidate closer than [AtmosphereConstants.MIN_TARGET_SEPARATION] to an already-placed point is
 * thrown away. After [AtmosphereConstants.PLACEMENT_MAX_ATTEMPTS] rejections the candidate is
 * taken anyway.
 */
internal object BlobPlacement {

    /**
     * Fills [out] with `count` (x, y) pairs in surface pixels.
     *
     * @param random re-seeded per call by the caller, so the layout is new on every lock.
     */
    fun generate(width: Int, height: Int, count: Int, random: Random, out: FloatArray) {
        var placed = 0
        while (placed < count) {
            // I know they are redundant but prefer this algorithm shape
            var x = 0f
            var y = 0f
            var attempts = 0
            while (true) {
                x = random.nextInt(width.coerceAtLeast(1)).toFloat()
                y = random.nextInt(height.coerceAtLeast(1)).toFloat()
                if (attempts >= AtmosphereConstants.PLACEMENT_MAX_ATTEMPTS) break
                if (isFarEnough(out, placed, x, y)) break
                attempts++
            }
            out[placed * 2] = x
            out[placed * 2 + 1] = y
            placed++
        }
    }

    private fun isFarEnough(out: FloatArray, placed: Int, x: Float, y: Float): Boolean {
        val minimum = AtmosphereConstants.MIN_TARGET_SEPARATION * AtmosphereConstants.MIN_TARGET_SEPARATION
        for (i in 0 until placed) {
            val dx = out[i * 2] - x
            val dy = out[i * 2 + 1] - y
            if (dx * dx + dy * dy < minimum) return false
        }
        return true
    }
}
