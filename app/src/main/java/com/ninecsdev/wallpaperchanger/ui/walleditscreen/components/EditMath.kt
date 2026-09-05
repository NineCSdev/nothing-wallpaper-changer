package com.ninecsdev.wallpaperchanger.ui.walleditscreen.components

import androidx.compose.ui.geometry.Offset
import com.ninecsdev.wallpaperchanger.logic.computeEditTransform
import com.ninecsdev.wallpaperchanger.model.EditParams
import com.ninecsdev.wallpaperchanger.ui.components.MaxZoom
import com.ninecsdev.wallpaperchanger.ui.components.focalPan
import kotlin.math.abs

/*
 * Edit-value policy for the wallpaper editor: comparison tolerance, zoom/offset bounds,
 * the fit-height zoom rule, and the pinch/drag gesture mapping. The transform geometry
 * itself lives in computeEditTransform.
 */

private const val EditValueEpsilon = 0.001f

internal const val MinZoom = 1f

private const val MinOffset = -1f
private const val MaxOffset = 1f

internal fun coerceZoom(value: Float): Float = value.coerceIn(MinZoom, MaxZoom)
internal fun coerceOffset(value: Float): Float = value.coerceIn(MinOffset, MaxOffset)

/**
 * Offset display mapping: the persisted offset is -1..1, but the editor sliders present it to the
 * user as 0..100 (0 = -1, 50 = 0 / centered, 100 = 1). Conversion is confined to the UI layer;
 * everything below the slider keeps the -1..1 range.
 */
internal fun offsetToPercent(offset: Float): Float = (offset - MinOffset) / (MaxOffset - MinOffset) * 100f
internal fun percentToOffset(percent: Float): Float = coerceOffset(MinOffset + percent / 100f * (MaxOffset - MinOffset))

// TODO: Add tests for the format/parse round trip (see "EditTransform Tests" in the notes, typed value entry section)

/*
 * Text form of the edit values, shared by the controls panel's and the field that edits it in place,
 * Formatting follows the device locale; the parsers accept either decimal separator, so a comma
 * keyboard round-trips.
 */

/** Fraction digits the zoom field accepts and displays. */
internal const val ZoomDecimals = 2

/** Fraction digits the offset-percentage fields accept and display. */
internal const val OffsetPercentDecimals = 1

/** Integer digits a field accepts. */
private const val MaxIntegerDigits = 3

internal fun formatZoom(zoom: Float): String = "%.2f".format(zoom)

internal fun formatOffsetPercent(offset: Float): String = "%.1f".format(offsetToPercent(offset))

/**
 * Trims [text] down to a typeable decimal: digits only, at most one separator, at most [decimals]
 * fraction digits and [MaxIntegerDigits] integer digits.
 */
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

/**
 * True when (zoom, offsetX, offsetY) equals [params] within [EditValueEpsilon]; null [params]
 * means the defaults (zoom 1, no offset). The single dirty-check shared by the editor screen
 * (unsaved-changes guard) and [com.ninecsdev.wallpaperchanger.ui.walleditscreen.WallpaperEditViewModel] (no-op save detection).
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

// TODO: Add tests for applyEditGesture (see "EditTransform Tests" in the notes, gesture section)

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
 * then converted back to the persisted -1..1 offset. An axis with no room to pan (image not
 * overflowing it) keeps the offset it came in with.
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
    val newPan = focalPan(
        drag = Offset(panX, panY),
        centroid = Offset(focalX, focalY),
        current = Offset(current.panX, current.panY),
        fromZoom = zoom,
        toZoom = newZoom
    )

    val newOffsetX = if (scaled.maxPanX > 0f) coerceOffset(newPan.x / scaled.maxPanX) else offsetX
    val newOffsetY = if (scaled.maxPanY > 0f) coerceOffset(newPan.y / scaled.maxPanY) else offsetY

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
