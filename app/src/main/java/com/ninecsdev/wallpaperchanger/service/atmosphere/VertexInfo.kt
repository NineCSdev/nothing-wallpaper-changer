package com.ninecsdev.wallpaperchanger.service.atmosphere

/**
 * One seed: a color the photo is mostly made of, plus where that color actually lives in it.
 *
 * Six of these describe a wallpaper completely as far as the renderer is concerned. They are
 * produced by [SeedExtractor][com.ninecsdev.wallpaperchanger.logic.atmosphere.SeedExtractor] and
 * are the only thing the renderer knows about the source image besides its pixels.
 *
 * **Entry 0 is not a blob.** The list is sorted by population descending, and the most populous
 * color is spent on the background the whole composite is pulled toward; entries 1..5 are the
 * five blobs. Getting this backwards paints the photo's dominant mass *over* everything instead
 * of *under* it.
 *
 * [swatchColor] and [population] are carried for layout parity with the original's 28-byte stride
 * and are never read by the renderer.
 *
 * @param x X of the pixel in [bitmapWidth]-space closest to this swatch in CIE-LAB.
 * @param y Y of the same pixel. Note the renderer flips this into GL's origin.
 * @param pixelColor That pixel's raw color (this, not the swatch's own color) is what is drawn.
 */
data class VertexInfo(
    val x: Int,
    val y: Int,
    val bitmapWidth: Int,
    val bitmapHeight: Int,
    val pixelColor: Int,
    val swatchColor: Int,
    val population: Int
) {
    companion object {
        /** Field count, and the stride used by the on-disk container. */
        const val INT_COUNT = 7
    }
}
