package com.ninecsdev.wallpaperchanger.logic


/** Values in container units. Callers whose framework already fitted the content use [computeLayerTransform] instead. */
data class EditTransform(
    /** Absolute content→container scale: fit scale × zoom. */
    val scale: Float,
    /** Pixel pan from the centered position. */
    val panX: Float,
    val panY: Float,
    /** Top-left of the scaled content (centering + pan), ready for `Matrix.postTranslate`. */
    val drawX: Float,
    val drawY: Float,
    // Only ever divided by, to convert a pixel pan back into a normalized offset
    private val maxPanX: Float,
    private val maxPanY: Float,
) {
    val canPanX: Boolean get() = maxPanX > 0f

    val canPanY: Boolean get() = maxPanY > 0f

    /** Converts a pixel pan back into the persisted -1..1 offset; returns [fallback] on an axis with no room to pan. */
    fun offsetXFor(pan: Float, fallback: Float): Float = if (maxPanX > 0f) (pan / maxPanX).coerceIn(-1f, 1f) else fallback

    fun offsetYFor(pan: Float, fallback: Float): Float = if (maxPanY > 0f) (pan / maxPanY).coerceIn(-1f, 1f) else fallback
}

/** `graphicsLayer` values for content a framework has already fitted to its container. */
data class LayerTransform(
    val scale: Float,
    val translationX: Float,
    val translationY: Float,
)

/**
 * Single source of truth for fit + zoom + normalized pan, shared by the editor preview, thumbnails and
 * [BufferManager], so the wallpaper matches what the editor showed. zoom 1.0 fits the container;
 * [offsetX]/[offsetY] are -1..1 over the full pan range (zero when the axis doesn't overflow).
 * Container dimensions must be > 0.
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
 * [computeEditTransform] framed against the wallpaper canvas, for a container of any shape showing
 * a centered window onto it. drawX/drawY are in container pixels. Dimensions must be > 0.
 */
// TODO tests: see vault note tests/EditTransform Tests.md (computeCanvasEditTransform)
fun computeCanvasEditTransform(
    contentWidth: Float,
    contentHeight: Float,
    containerWidth: Float,
    containerHeight: Float,
    canvasWidth: Float,
    canvasHeight: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): EditTransform {
    // The canvas, cover-fitted into the container: one axis matches, the other overflows
    val cover = maxOf(containerWidth / canvasWidth, containerHeight / canvasHeight)
    val frameWidth = canvasWidth * cover
    val frameHeight = canvasHeight * cover

    val transform = computeEditTransform(
        contentWidth = contentWidth,
        contentHeight = contentHeight,
        containerWidth = frameWidth,
        containerHeight = frameHeight,
        zoom = zoom,
        offsetX = offsetX,
        offsetY = offsetY,
    )
    return transform.copy(
        drawX = transform.drawX + (containerWidth - frameWidth) / 2f,
        drawY = transform.drawY + (containerHeight - frameHeight) / 2f,
    )
}

/**
 * The same framing as [computeCanvasEditTransform], for a container that has already fitted the
 * content itself; [LayerTransform.scale] divides out that fit. Dimensions in container pixels, > 0;
 * [contentAspect] is width / height.
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
    val transform = computeCanvasEditTransform(
        contentWidth = contentAspect,
        contentHeight = 1f,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        canvasWidth = canvasWidth,
        canvasHeight = canvasHeight,
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
