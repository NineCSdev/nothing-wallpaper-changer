package com.ninecsdev.wallpaperchanger.ui.walleditscreen

/**
 * Shared result type for the edit transform calculation.
 * Used by both the editor canvas in [WallpaperEditScreen] and the
 * [EditableWallpaperImage][com.ninecsdev.wallpaperchanger.ui.components.EditableWallpaperImage] composable.
 */
data class TransformResult(
    val scale: Float,
    val translationX: Float,
    val translationY: Float
)

/**
 * Calculates the scale and translation needed to display an image with a given
 * zoom factor and normalized pan offsets inside a view of known size.
 *
 * Semantics:
 * - zoom = 1.0 → the image fits the view exactly (letterboxed / pillarboxed as needed).
 * - zoom > 1.0 → the image is scaled up (can fill and overflow the view).
 * - offsetX / offsetY are normalized to -1..1 representing the full available pan range.
 *
 * This function is the single source of truth for the edit transform math.
 * [BufferManager][com.ninecsdev.wallpaperchanger.logic.BufferManager] uses the equivalent
 * formula when applying edits to the wallpaper bitmap at rotation time.
 */
fun calculateTransform(
    viewWidth: Float,
    viewHeight: Float,
    imageAspectRatio: Float,
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
): TransformResult {
    val viewAspect = viewWidth / viewHeight

    val (fitW, fitH) = if (imageAspectRatio > viewAspect) {
        viewWidth to (viewWidth / imageAspectRatio)
    } else {
        (viewHeight * imageAspectRatio) to viewHeight
    }
    val imgScaledW = fitW * zoom
    val imgScaledH = fitH * zoom

    val maxPanX = ((imgScaledW - viewWidth) / 2f).coerceAtLeast(0f)
    val maxPanY = ((imgScaledH - viewHeight) / 2f).coerceAtLeast(0f)

    return TransformResult(
        scale = zoom,
        translationX = offsetX * maxPanX,
        translationY = offsetY * maxPanY
    )
}
