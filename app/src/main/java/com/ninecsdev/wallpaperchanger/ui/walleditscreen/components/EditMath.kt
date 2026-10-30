package com.ninecsdev.wallpaperchanger.ui.walleditscreen.components

import androidx.compose.ui.geometry.Offset
import com.ninecsdev.wallpaperchanger.logic.computeEditTransform
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.ui.components.MaxZoom
import com.ninecsdev.wallpaperchanger.ui.components.focalPan
import kotlin.math.abs

// Below the finest typeable step (0.01% offset = 0.0002), so a typed edit is never a no-op
private const val EditValueEpsilon = 0.0001f

internal const val MinZoom = 1f

private const val MinOffset = -1f
private const val MaxOffset = 1f

internal fun coerceZoom(value: Float): Float = value.coerceIn(MinZoom, MaxZoom)
internal fun coerceOffset(value: Float): Float = value.coerceIn(MinOffset, MaxOffset)

// Sliders show the persisted -1..1 offset as 0..100 (50 = centered); conversion stays in the UI layer
internal fun offsetToPercent(offset: Float): Float = (offset - MinOffset) / (MaxOffset - MinOffset) * 100f
internal fun percentToOffset(percent: Float): Float = coerceOffset(MinOffset + percent / 100f * (MaxOffset - MinOffset))

// TODO: Add tests for the format/parse round trip (see "EditTransform Tests" in the notes, typed value entry section)

internal const val ZoomDecimals = 3

internal const val OffsetPercentDecimals = 2

private const val MaxIntegerDigits = 3

// Formatting follows the device locale; the parsers accept either decimal separator, so a comma keyboard round-trips
internal fun formatZoom(zoom: Float): String = "%.${ZoomDecimals}f".format(zoom)

internal fun formatOffsetPercent(offset: Float): String = "%.${OffsetPercentDecimals}f".format(offsetToPercent(offset))

/** Digits only, at most one separator, [decimals] fraction digits and [MaxIntegerDigits] integer digits. */
internal fun sanitizeDecimalInput(text: String, decimals: Int): String {
    val out = StringBuilder()
    var separatorSeen = false
    var integerDigits = 0
    var fractionDigits = 0

    for (char in text) {
        when {
            char.isDigit() && separatorSeen && fractionDigits < decimals -> {
                out.append(char)
                fractionDigits++
            }
            char.isDigit() && !separatorSeen && integerDigits < MaxIntegerDigits -> {
                out.append(char)
                integerDigits++
            }
            (char == '.' || char == ',') && !separatorSeen && decimals > 0 -> {
                out.append(char)
                separatorSeen = true
            }
        }
    }

    return out.toString()
}

private fun String.toEditFloatOrNull(): Float? = replace(',', '.').toFloatOrNull()

/** Typed zoom, coerced into the editor's bounds; null while the field holds nothing parseable. */
internal fun parseZoomInput(text: String): Float? = text.toEditFloatOrNull()?.let(::coerceZoom)

/** Typed offset percentage, converted to the persisted -1..1 offset; null when unparseable. */
internal fun parseOffsetPercentInput(text: String): Float? = text.toEditFloatOrNull()?.let(::percentToOffset)

internal fun isCloseEnough(left: Float, right: Float): Boolean =
    abs(left - right) <= EditValueEpsilon

/** Null [params] means the defaults (zoom 1, no offset). Shared by the unsaved-changes guard and the ViewModel's no-op save detection. */
internal fun matchesEditParams(
    params: EditParams?,
    zoom: Float,
    offsetX: Float,
    offsetY: Float
): Boolean =
    isCloseEnough(zoom, params?.zoom ?: 1f) &&
        isCloseEnough(offsetX, params?.offsetX ?: 0f) &&
        isCloseEnough(offsetY, params?.offsetY ?: 0f)

// TODO: Add tests for applyEditGesture (see "EditTransform Tests" in the notes, gesture section)

/** New (zoom, offsetX, offsetY) produced by one pinch/drag frame. Values are already coerced. */
internal data class GestureTransform(
    val zoom: Float,
    val offsetX: Float,
    val offsetY: Float,
)

/**
 * Focal-point zoom (the content point under the centroid stays put) and 1:1 pan (X and Y each mapped
 * through their own real pan range). Pixel inputs are in the container's space, origin top-left.
 * An axis with no room to pan keeps its incoming offset; falls back to a zoom-only update while geometry is unknown.
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

    val newPan = focalPan(
        drag = Offset(panX, panY),
        centroid = Offset(focalX, focalY),
        current = Offset(current.panX, current.panY),
        fromZoom = zoom,
        toZoom = newZoom
    )

    return GestureTransform(
        newZoom,
        scaled.offsetXFor(newPan.x, offsetX),
        scaled.offsetYFor(newPan.y, offsetY),
    )
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
