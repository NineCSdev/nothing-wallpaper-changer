package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.ninecsdev.wallpaperchanger.ui.components.MaxZoom

/*
 * Pan/zoom policy for the full-screen preview: resistance past the zoom ceiling and the clamp
 * that keeps the image edge pinned to the viewport edge. The limits both screens share, and the
 * focal-point pan they both apply, are in ui/components/ZoomPolicy.kt.
 *
 * Separate from the editor's EditMath, which maps gestures onto persisted, screen-frame-relative edit params
 */

// TODO tests: see vault note tests/Preview Zoom Math.md

/** Resting zoom. Not a floor: a pinch-to-leave shrinks below it, down to [PreviewShrinkFloor]. */
internal const val PreviewMinZoom = 1f
// The band around 1x that counts as at rest
private const val RestZoomMin = 0.92f
private const val RestZoomMax = 1.03f

/** Smallest the image is ever drawn (floor of the pinch-to-leave shrink) */
private const val PreviewShrinkFloor = 0.5f

/** Pure overflow guard on the accumulator. The visible ceiling is [OverzoomCeiling] */
private const val RawZoomCeiling = 100f

/** Furthest a pinch can stretch the image past [MaxZoom] before it springs back */
internal const val OverzoomCeiling = 50f

/** Below this a released pinch is "get me out" instead of "unzoom" */
internal const val PreviewExitZoom = 0.85f

/** Downward drag, as a fraction of viewport height, that dismisses on release. */
internal const val PreviewDismissDistanceFraction = 0.15f

/** Downward fling speed (px/s) that dismisses regardless of distance traveled. */
internal const val PreviewDismissVelocity = 1000f

/** Slack (in pixels) for calling the pan "against its limit". Absorbs the clamp's own rounding. */
private const val PanEdgeTolerance = 0.5f

/**
 * Zoom as displayed for a raw accumulated [raw]. Below 1x the image tracks the pinch **exactly**.
 *
 * Above [MaxZoom] the excess is rubber-banded: the first of the overshoot comes through nearly
 * intact and the rest asymptotes onto [OverzoomCeiling].
 */
internal fun softClampZoom(raw: Float): Float = when {
    raw > MaxZoom -> {
        val headroom = OverzoomCeiling - MaxZoom
        val excess = raw - MaxZoom
        MaxZoom + headroom * excess / (excess + headroom)
    }
    else -> raw.coerceAtLeast(PreviewShrinkFloor)
}

/**
 * [softClampZoom] inverted: the raw pinch that would display [zoom].
 *
 * Picking a gesture up while the image overshot has to resume from the pinch that overshoot.
 */
internal fun unclampZoom(zoom: Float): Float = when {
    zoom > MaxZoom -> {
        val headroom = OverzoomCeiling - MaxZoom
        val shown = (zoom - MaxZoom).coerceAtMost(headroom * 0.99f)
        MaxZoom + shown * headroom / (headroom - shown)
    }
    else -> zoom
}

/**
 * The pinch accumulator is kept uncompressed so that resistance above [MaxZoom] compounds off the real pinch
 * Only the floor is a policy; the ceiling is overflow hygiene.
 */
internal fun coerceRawZoom(raw: Float): Float = raw.coerceIn(PreviewShrinkFloor, RawZoomCeiling)

internal fun isAtRestZoom(zoom: Float): Boolean = zoom in RestZoomMin..RestZoomMax

/** How far a pinch has traveled towards leaving, 0 at rest and 1 at [PreviewExitZoom] */
internal fun zoomOutProgress(zoom: Float): Float = ((PreviewMinZoom - zoom) / (PreviewMinZoom - PreviewExitZoom)).coerceIn(0f, 1f)

/** Half the overflow of the scaled content past the viewport on one axis; 0 when it fits. */
internal fun maxPan(contentSize: Float, viewportSize: Float, zoom: Float): Float = ((contentSize * zoom - viewportSize) / 2f).coerceAtLeast(0f)

/** [pan] held so the scaled content never pulls its own edge inside the viewport edge. */
internal fun clampPan(pan: Offset, content: Size, viewport: Size, zoom: Float): Offset {
    val limitX = maxPan(content.width, viewport.width, zoom)
    val limitY = maxPan(content.height, viewport.height, zoom)
    return Offset(pan.x.coerceIn(-limitX, limitX), pan.y.coerceIn(-limitY, limitY))
}

/**
 * Whether the scaled content is already as far in the direction [dragX] pushes as it can go.
 * Also true when the content is narrower than the viewport.
 */
internal fun atHorizontalLimit(
    dragX: Float,
    pan: Offset,
    content: Size,
    viewport: Size,
    zoom: Float
): Boolean {
    val limit = maxPan(content.width, viewport.width, zoom)
    return when {
        limit <= 0f -> true
        dragX > 0f -> pan.x >= limit - PanEdgeTolerance
        dragX < 0f -> pan.x <= -limit + PanEdgeTolerance
        else -> false
    }
}
