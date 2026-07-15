package com.ninecsdev.wallpaperchanger.logic

// TODO: Add tests for this (see "EditTransform Tests" in the notes)

/**
 * Result of the edit transform calculation. All values are in container units.
 *
 * Consumers read different fields depending on their rendering model:
 * - The editor preview and [EditableWallpaperImage][com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.EditableWallpaperImage]
 *   render the image pre-fitted (`ContentScale.Fit`, centered) and apply a scale + [panX]/[panY]
 *   via `graphicsLayer` (the editor scales by `zoom` directly; EditableWallpaperImage derives its
 *   layer scale from [scale] because its fit base differs from its transform container), ignoring
 *   [drawX]/[drawY].
 * - [BufferManager] draws the raw source bitmap through a `Matrix` and uses [scale] plus
 *   [drawX]/[drawY].
 */
data class EditTransform(
    /** Absolute content→container scale: fit scale × zoom. */
    val scale: Float,
    /** Pixel pan from the centered position, X axis. */
    val panX: Float,
    /** Pixel pan from the centered position, Y axis. */
    val panY: Float,
    /** Top-left X of the scaled content: centering + pan. Ready for `Matrix.postTranslate`. */
    val drawX: Float,
    /** Top-left Y of the scaled content: centering + pan. Ready for `Matrix.postTranslate`. */
    val drawY: Float,
    /** Maximum pixel pan from center on the X axis (offset ±1 maps to ±this; `panX = offsetX * maxPanX`). 0 when the axis doesn't overflow. */
    val maxPanX: Float,
    /** Maximum pixel pan from center on the Y axis (offset ±1 maps to ±this; `panY = offsetY * maxPanY`). 0 when the axis doesn't overflow. */
    val maxPanY: Float,
)

/**
 * The single source of truth for the edit transform math (fit + zoom + normalized pan),
 * shared by the editor preview, [EditableWallpaperImage][com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.EditableWallpaperImage],
 * and [BufferManager]'s render path — so the wallpaper that lands on screen matches exactly
 * what the user saw in the editor.
 *
 * Semantics:
 * - zoom = 1.0 → the content fits the container exactly (letterboxed / pillarboxed as needed).
 * - zoom > 1.0 → the content is scaled up (can fill and overflow the container).
 * - [offsetX]/[offsetY] are normalized to -1..1 over the full available pan range; when the
 *   scaled content doesn't overflow an axis, the pan range on that axis is 0.
 *
 * Content dimensions only need to be in units consistent with each other.
 * Container dimensions are in output pixels, and both must be > 0.
 */
fun computeEditTransform(
    contentWidth: Float,
    contentHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): EditTransform {
    val fitScale = minOf(containerWidth / contentWidth, containerHeight / contentHeight)
    val scale = fitScale * zoom

    val scaledWidth = contentWidth * scale
    val scaledHeight = contentHeight * scale

    val maxPanX = ((scaledWidth - containerWidth) / 2f).coerceAtLeast(0f)
    val maxPanY = ((scaledHeight - containerHeight) / 2f).coerceAtLeast(0f)
    val panX = offsetX * maxPanX
    val panY = offsetY * maxPanY

    val centerX = (containerWidth - scaledWidth) / 2f
    val centerY = (containerHeight - scaledHeight) / 2f

    return EditTransform(
        scale = scale,
        panX = panX,
        panY = panY,
        drawX = centerX + panX,
        drawY = centerY + panY,
        maxPanX = maxPanX,
        maxPanY = maxPanY,
    )
}
