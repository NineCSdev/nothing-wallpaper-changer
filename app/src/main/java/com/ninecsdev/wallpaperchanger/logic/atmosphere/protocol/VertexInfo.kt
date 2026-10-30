package com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol

/**
 * One seed: a color the photo is mostly made of, plus where that color lives in it.
 *
 * **Entry 0 is not a blob.** The list is sorted by population descending; the most populous color
 * is the background and entries 1..5 are the blobs. Reversing this paints the dominant mass over
 * everything instead of under it.
 *
 * @param x X of the pixel in [bitmapWidth]-space closest to this swatch in CIE-LAB.
 * @param y Y of the same pixel. Note the renderer flips this into GL's origin.
 * @param pixelColor That pixel's raw color (not the swatch's own color) is what is drawn.
 * @param population How much of the photo quantized to this swatch; orders the list, so it decides
 * the background seed. Synthesized seeds carry [SYNTHESIZED_POPULATION].
 */
data class VertexInfo(
    val x: Int,
    val y: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val pixelColor: Int,
    val population: Int
) {
    companion object {
        /** [population] for a seed whose color was invented rather than measured. */
        const val SYNTHESIZED_POPULATION = -1

        const val SEED_COUNT = 6
    }
}
