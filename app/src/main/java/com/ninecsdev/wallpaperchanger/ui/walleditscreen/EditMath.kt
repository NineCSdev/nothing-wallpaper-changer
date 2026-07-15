package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import com.ninecsdev.wallpaperchanger.logic.computeEditTransform
import com.ninecsdev.wallpaperchanger.model.EditParams
import kotlin.math.abs

/*
 * Edit-value policy for the wallpaper editor: comparison tolerance, zoom/offset bounds,
 * the fit-height zoom rule, and the pinch/drag gesture mapping. The transform geometry
 * itself lives in [com.ninecsdev.wallpaperchanger.logic.computeEditTransform].
 */

private const val EditValueEpsilon = 0.001f

private const val MinZoom = 0.5f
private const val MaxZoom = 5f
private const val MinOffset = -1f
private const val MaxOffset = 1f

internal fun coerceZoom(value: Float): Float = value.coerceIn(MinZoom, MaxZoom)
internal fun coerceOffset(value: Float): Float = value.coerceIn(MinOffset, MaxOffset)

internal fun isCloseEnough(left: Float, right: Float): Boolean =
    abs(left - right) <= EditValueEpsilon

/**
 * True when (zoom, offsetX, offsetY) equals [params] within [EditValueEpsilon]; null [params]
 * means the defaults (zoom 1, no offset). The single dirty-check shared by the editor screen
 * (unsaved-changes guard) and [WallpaperEditViewModel] (no-op save detection).
 */
internal fun matchesEditParams(
    params: EditParams?,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): Boolean =
    isCloseEnough(zoom, params?.zoom ?: 1f) &&
        isCloseEnough(offsetX, params?.offsetX ?: 0f) &&
        isCloseEnough(offsetY, params?.offsetY ?: 0f)

// TODO: Add tests for applyEditGesture (see "EditTransform Tests" in the notes — gesture section)

/** New (zoom, offsetX, offsetY) produced by one pinch/drag frame. Values are already coerced. */
internal data class GestureTransform(
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * Maps one pinch/drag frame onto the editor's normalized (zoom, offset) state with
 * **focal-point zoom** and **1:1 (direct-manipulation) pan**:
 *
 * - The content point under [centroidX]/[centroidY] stays put as the image scales, so pinching
 *   zooms into wherever the fingers are — not the image center.
 * - A [panX]/[panY] drag moves the image by exactly that many pixels (X and Y each mapped through
 *   their own real pan range), so the image tracks the finger instead of a tuned sensitivity.
 *
 * All pixel inputs are in the editor container's coordinate space (origin top-left, [centroidX] in
 * `0..containerWidth`). Pan is computed in container pixels via [computeEditTransform],
 * then converted back to the persisted -1..1 offset. When an axis has
 * no room to pan (image not overflowing it) the offset collapses to 0.
 *
 * Falls back to a zoom-only update while the container/image geometry isn't known yet.
 */
internal fun applyEditGesture(
    zoom: Float,
    offsetX: Float,
    offsetY: Float,
    centroidX: Float,
    centroidY: Float,
    panX: Float,
    panY: Float,
    gestureZoom: Float,
    containerWidth: Float,
    containerHeight: Float,
    imageAspectRatio: Float,
): GestureTransform {
    val newZoom = coerceZoom(zoom * gestureZoom)

    if (containerWidth <= 0f || containerHeight <= 0f || imageAspectRatio <= 0f || zoom <= 0f) {
        return GestureTransform(newZoom, offsetX, offsetY)
    }

    // Effective scale ratio actually applied (accounts for zoom coercion at the bounds).
    val zoomRatio = newZoom / zoom

    // Current pan in container pixels, and the pan range at the new zoom, both straight from
    // the shared render math so gesture and render never drift.
    val current = computeEditTransform(
        contentWidth = imageAspectRatio,
        contentHeight = 1f,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        zoom = zoom,
        offsetX = offsetX,
        offsetY = offsetY,
    )
    val scaled = computeEditTransform(
        contentWidth = imageAspectRatio,
        contentHeight = 1f,
        containerWidth = containerWidth,
        containerHeight = containerHeight,
        zoom = newZoom,
        offsetX = offsetX,
        offsetY = offsetY,
    )

    // Focal point relative to the container center (where the render pivots and pans from).
    val focalX = centroidX - containerWidth / 2f
    val focalY = centroidY - containerHeight / 2f

    // Keep the focal content point fixed under the fingers, then apply the finger drag.
    val newPanX = focalX * (1f - zoomRatio) + zoomRatio * current.panX + panX
    val newPanY = focalY * (1f - zoomRatio) + zoomRatio * current.panY + panY

    val newOffsetX = if (scaled.maxPanX > 0f) coerceOffset(newPanX / scaled.maxPanX) else 0f
    val newOffsetY = if (scaled.maxPanY > 0f) coerceOffset(newPanY / scaled.maxPanY) else 0f

    return GestureTransform(newZoom, newOffsetX, newOffsetY)
}

/**
 * Zoom that makes the image fill the view height when the image is wider than the view
 * (at zoom 1 the image fits entirely, letterboxed). Degenerate aspects fall back to 1.
 */
internal fun calculateFitHeightZoom(
    imageAspectRatio: Float,
    viewAspect: Float,
): Float {
    if (imageAspectRatio <= 0f || viewAspect <= 0f) return 1f

    return if (imageAspectRatio > viewAspect) {
        imageAspectRatio / viewAspect
    } else {
        1f
    }
}
