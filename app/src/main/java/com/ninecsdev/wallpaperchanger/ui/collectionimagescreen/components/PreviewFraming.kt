package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

private const val DISMISS_DRAG_SHRINK = 0.2f

/** Largest side, in px, `Constraints.fixed` holds on both axes at once (the true limit is 2^15 - 2) */
private const val MAX_FRAMED_SIDE_PX = 32_000f

/** Draw-time transform, invisible to layout, so it hands over to [previewFraming] the moment the fingers leave. */
internal fun Modifier.previewGestureTransform(state: PreviewZoomState, active: Boolean): Modifier =
    if (!active) this else this.graphicsLayer {
        val scale = state.shownScale
        scaleX = scale
        scaleY = scale
        translationX = state.pan.x
        translationY = state.pan.y + state.dismissTranslation
    }

// Read by both halves of the transform, which have to agree exactly
private val PreviewZoomState.shownScale: Float
    get() = zoom * (1f - DISMISS_DRAG_SHRINK * dismissProgress)

/**
 * The same pan/zoom, in layout, while still reporting the full rect outward so nothing around it moves.
 * A shared element flies from the bounds its content was laid out at, so a draw-time transform
 * alone would leave the close departing from the untransformed full-screen rect.
 * Only reaches the screen while no flight is in the air.
 */
internal fun Modifier.previewFraming(state: PreviewZoomState, active: Boolean): Modifier =
    if (!active) this else this.layout { measurable, constraints ->
        val scale = state.shownScale
        val idle = scale == 1f && state.pan == Offset.Zero && state.dismissTranslation == 0f
        if (idle || !constraints.hasBoundedWidth || !constraints.hasBoundedHeight) {
            val asIs = measurable.measure(constraints)
            return@layout layout(asIs.width, asIs.height) {
                asIs.place(0, 0)
                state.onFramed()
            }
        }
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        // Deep over-zoom outgrows what Constraints can hold, so layout stops there and a layer draws the rest
        val layoutScale = min(scale, MAX_FRAMED_SIDE_PX / max(width, height))
        val drawScale = scale / layoutScale
        val placeable = measurable.measure(
            Constraints.fixed((width * layoutScale).roundToInt(), (height * layoutScale).roundToInt())
        )
        layout(width, height) {
            // Both scale about the same center, so the rect is placed as if laid out at full scale
            val x = ((width - placeable.width) / 2f + state.pan.x).roundToInt()
            val y = ((height - placeable.height) / 2f + state.pan.y + state.dismissTranslation).roundToInt()
            if (drawScale == 1f) placeable.place(x, y)
            else placeable.placeWithLayer(x, y) {
                scaleX = drawScale
                scaleY = drawScale
            }
            state.onFramed()
        }
    }
