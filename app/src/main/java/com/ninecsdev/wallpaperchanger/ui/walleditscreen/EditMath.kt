package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import com.ninecsdev.wallpaperchanger.model.EditParams
import kotlin.math.abs

/*
 * Edit-value policy for the wallpaper editor: comparison tolerance, zoom/offset bounds,
 * and the fit-height zoom rule. The transform geometry itself lives in
 * [com.ninecsdev.wallpaperchanger.logic.computeEditTransform].
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
