package com.ninecsdev.wallpaperchanger.logic

// TODO: Add tests for this (see "EditTransform Tests" in the notes)

/**
 * Result of the edit transform calculation. All values are in container units.
 *
 * Callers that draw the raw source through a `Matrix` read [scale] with [drawX]/[drawY]. Callers
 * whose framework already fitted the content go through [computeLayerTransform] instead.
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
    // Only ever divided by, to convert a pixel pan back into a normalized offset
    private val maxPanX: Float,
    private val maxPanY: Float,
) {
    /** Whether the scaled content overflows the X axis, and so has any pan to give. */
    val canPanX: Boolean get() = maxPanX > 0f

    /** Whether the scaled content overflows the Y axis, and so has any pan to give. */
    val canPanY: Boolean get() = maxPanY > 0f

    /**
     * Converts a pixel pan back into the persisted -1..1 offset, clamped to that range.
     *
     * Returns [fallback] unchanged on an axis with no room to pan.
     */
    fun offsetXFor(pan: Float, fallback: Float): Float = if (maxPanX > 0f) (pan / maxPanX).coerceIn(-1f, 1f) else fallback

    /** The Y-axis twin of [offsetXFor]. */
    fun offsetYFor(pan: Float, fallback: Float): Float = if (maxPanY > 0f) (pan / maxPanY).coerceIn(-1f, 1f) else fallback
}

/** `graphicsLayer` values for content a framework has already fitted to its container. */
data class LayerTransform(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * The single source of truth for the edit transform math (fit + zoom + normalized pan),
 * shared by the editor preview, the thumbnails and [BufferManager]'s render path, so the
 * wallpaper that lands on screen matches exactly what the user saw in the editor.
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

/**
 * The same framing as [computeEditTransform], for a container that has **already fitted the
 * content itself** and can only transform it afterward.
 *
 * The content is framed against the wallpaper canvas, and the container then shows a centered
 * window onto that framing. [scale][LayerTransform.scale] divides out the fit the container already applied.
 *
 * All dimensions in container pixels, all > 0; [contentAspect] is width / height.
 */
fun computeLayerTransform(
    contentAspect: Float,
    containerWidth: Float,
    containerHeight: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): LayerTransform {
    // The canvas, cover-fitted into the container: one axis matches, the other overflows.
    val cover = maxOf(containerWidth / canvasWidth, containerHeight / canvasHeight)

    val transform = computeEditTransform(
        contentWidth = contentAspect,
        contentHeight = 1f,
        containerWidth = canvasWidth * cover,
        containerHeight = canvasHeight * cover,
        zoom = zoom,
        offsetX = offsetX,
        offsetY = offsetY,
    )

    val containerFitScale = minOf(containerWidth / contentAspect, containerHeight)

    return LayerTransform(
        scale = transform.scale / containerFitScale,
        translationX = transform.panX,
        translationY = transform.panY,
    )
}
