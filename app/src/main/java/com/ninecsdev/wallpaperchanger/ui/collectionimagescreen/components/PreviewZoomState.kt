package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.lerp
import androidx.compose.animation.splineBasedDecay
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.fastFilter
import androidx.compose.ui.util.lerp
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import com.ninecsdev.wallpaperchanger.ui.components.DoubleTapZoom
import com.ninecsdev.wallpaperchanger.ui.components.FlingMinVelocity
import com.ninecsdev.wallpaperchanger.ui.components.MaxZoom
import com.ninecsdev.wallpaperchanger.ui.components.detectTapAwareGestures
import com.ninecsdev.wallpaperchanger.ui.components.focalPan
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One instance serves the whole pager; a page change resets it ([reset]). Every value is in viewport
 * pixels, and the gesture detector must sit outside the layer they transform, or the image trails the finger.
 */
@Stable
internal class PreviewZoomState(
    private val scope: CoroutineScope,
    density: Density,
    initialPage: Int
) {

    /** Keying this off the pager's current page would pop the outgoing image back to 1x mid-swipe, since current flips at the halfway point. */
    var ownerPage by mutableIntStateOf(initialPage)
        private set

    var zoom by mutableFloatStateOf(PreviewMinZoom)
        private set

    var pan by mutableStateOf(Offset.Zero)
        private set

    var dismissTranslation by mutableFloatStateOf(0f)
        private set

    /** Decides how [zoom]/[pan] are applied: a gesture gets the draw-time transform, anything else the layout one. */
    var gestureActive by mutableStateOf(false)
        private set

    /** Never cleared except by [reset]. */
    var leaveRequested by mutableStateOf(false)
        private set

    /** Set once the frozen leave values have been through a layout pass; the close waits for it. */
    var framedForLeave by mutableStateOf(false)
        private set

    fun onFramed() {
        if (leaveRequested && !framedForLeave) framedForLeave = true
    }

    private val panDecay: DecayAnimationSpec<Offset> = splineBasedDecay(density)

    private var rawZoom = PreviewMinZoom
    private var shrinkCanLeave by mutableStateOf(false)
    private var settleJob: Job? = null
    private var throwing = false // Whether the animation in settleJob is a throw instead of settle
    private var viewport by mutableStateOf(Size.Zero)
    private var content = Size.Zero

    private fun stopAnimation() {
        settleJob?.cancel()
        settleJob = null
        throwing = false
    }

    /** 0 when settled, 1 at the distance a release would dismiss from */
    val dismissProgress: Float
        get() {
            val threshold = viewport.height * PreviewDismissDistanceFraction
            return if (threshold > 0f) (dismissTranslation / threshold).coerceIn(0f, 1f) else 0f
        }

    /** Shrinking only counts when the pinch [started at rest][shrinkCanLeave], because only then can it leave. */
    val fadeProgress: Float
        get() = max(dismissProgress, if (shrinkCanLeave) zoomOutProgress(zoom) else 0f)

    /** Whether the image is close enough to its resting size to page, dismiss-drag or show chrome. */
    val isAtRest: Boolean get() = isAtRestZoom(zoom)

    fun owns(page: Int): Boolean = page == ownerPage

    /** Hands the transform to [page], cleared. Called when a swipe settles, not when it starts. */
    fun handOverTo(page: Int) {
        reset()
        ownerPage = page
    }

    /** Whether a sideways drag of [dragX] has nowhere left to pan the image so pan becomes page turn */
    fun atHorizontalLimit(dragX: Float): Boolean = atHorizontalLimit(dragX, pan, content, viewport, zoom)

    fun onViewportSize(size: Size) { viewport = size }

    /** The image's laid-out rect (fit rect rather than the viewport for a tall photo) */
    fun onContentSize(size: Size) {
        content = size
        pan = clampPan(pan, content, viewport, zoom)
    }

    /**
     * Interrupting a settle adopts what is on screen: the spring moves the accumulator to its target
     * up front, so it is re-derived through [unclampZoom] and a gesture picked up mid-overshoot is not re-banded.
     * [startedAtRest] decides whether a release below [PreviewExitZoom] leaves and whether shrinking fades the backdrop.
     */
    fun onGestureStart(startedAtRest: Boolean) {
        stopAnimation()
        rawZoom = unclampZoom(zoom)
        shrinkCanLeave = startedAtRest
        gestureActive = true
    }

    /** Only on throw: A finger landing on a throw stops it dead, before anything else about the touch is known. */
    fun onTouchDown(): Boolean {
        if (!throwing) return false
        stopAnimation()
        return true
    }

    fun onTransform(centroid: Offset, drag: Offset, zoomDelta: Float) {
        val from = zoom
        rawZoom = coerceRawZoom(rawZoom * zoomDelta)
        val to = softClampZoom(rawZoom)
        zoom = to
        pan = clampPan(focalPan(drag, centroid, pan, from, to), content, viewport, to)
    }

    fun onDismissDrag(deltaY: Float) {
        dismissTranslation = (dismissTranslation + deltaY).coerceAtLeast(0f)
    }

    /** Leaves if a pinch [started at rest][shrinkCanLeave] and released below [PreviewExitZoom], or a drag passed the dismiss threshold or [PreviewDismissVelocity]. */
    fun onGestureEnd(dismissVelocity: Float, panVelocity: Offset = Offset.Zero) {
        val leaving = (shrinkCanLeave && rawZoom < PreviewExitZoom) ||
            dismissProgress >= 1f ||
            dismissVelocity > PreviewDismissVelocity
        gestureActive = false
        when {
            leaving -> requestLeave()
            settledZoom() == zoom && dismissTranslation == 0f && panVelocity.getDistance() > FlingMinVelocity -> flingPan(panVelocity)
            else -> settle()
        }
    }

    /** Leaves zoom, pan and the dismiss pull where the fingers left them for the close to unwind; settling would put the zoom in the rest band and restore the chrome. */
    private fun freeze() {
        stopAnimation()
        gestureActive = false
        rawZoom = zoom
    }

    /**
     * Asks to leave, holding the image where it is. The close does not start here: the caller
     * gives the frozen values one layout pass first, because that is what the flight departs from.
     */
    fun requestLeave() {
        freeze()
        leaveRequested = true
    }

    private fun flingPan(velocity: Offset) {
        throwing = true
        settleJob = scope.launch {
            var previous = pan
            AnimationState(
                typeConverter = Offset.VectorConverter,
                initialValue = pan,
                initialVelocityVector = AnimationVector2D(velocity.x, velocity.y)
            ).animateDecay(panDecay) {
                val clamped = clampPan(value, content, viewport, zoom)
                // Both axes pinned: the rest of the decay has nothing left to show
                if (clamped == previous) cancelAnimation() else pan = clamped
                previous = clamped
            }
        }
    }

    /** Ends a gesture the pager took over: settle where it is, and never read it as a leave. */
    fun onGestureCancel() {
        gestureActive = false
        settle()
    }

    fun onDoubleTap(focus: Offset) {
        stopAnimation()
        val toZoom = if (isAtRest) DoubleTapZoom else PreviewMinZoom
        rawZoom = toZoom
        shrinkCanLeave = false
        animateTo(toZoom, clampPan(focalPan(Offset.Zero, focus, pan, zoom, toZoom), content, viewport, toZoom))
    }

    fun reset() {
        stopAnimation()
        rawZoom = PreviewMinZoom
        shrinkCanLeave = false
        gestureActive = false
        leaveRequested = false
        framedForLeave = false
        zoom = PreviewMinZoom
        pan = Offset.Zero
        dismissTranslation = 0f
    }

    /** The pan scales by the same ratio as the zoom, which holds the content point at the screen center still. */
    private fun settle() {
        val toZoom = settledZoom()
        rawZoom = toZoom
        animateTo(toZoom, clampPan(pan * (toZoom / zoom), content, viewport, toZoom))
    }

    private fun settledZoom(): Float = rawZoom.coerceIn(PreviewMinZoom, MaxZoom)

    private fun animateTo(toZoom: Float, toPan: Offset) {
        throwing = false
        val fromZoom = zoom
        val fromPan = pan
        val fromDismiss = dismissTranslation
        if (fromZoom == toZoom && fromPan == toPan && fromDismiss == 0f) return
        settleJob = scope.launch {
            animate(
                initialValue = 0f,
                targetValue = 1f,
            ) { fraction, _ ->
                zoom = lerp(fromZoom, toZoom, fraction)
                pan = lerp(fromPan, toPan, fraction)
                dismissTranslation = lerp(fromDismiss, 0f, fraction)
            }
        }
    }
}

@Composable
internal fun rememberPreviewZoomState(initialPage: Int): PreviewZoomState {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    return remember(scope, density) { PreviewZoomState(scope, density, initialPage) }
}

private enum class PreviewGesture { Undecided, Transform, Dismiss, Details, Blocked, Paging }

/**
 * One loop because the readings of a touch (tap, pinch/pan, dismiss drag, details drag) are mutually exclusive.
 * Must be attached above the layer that renders the zoom, never inside it.
 */
internal suspend fun PointerInputScope.detectPreviewGestures(
    state: PreviewZoomState,
    onTap: () -> Unit,
    onDoubleTap: (Offset) -> Unit,
    onGestureStart: () -> Unit,
    details: PreviewDetailsSheetState
) {
    // A tap that caught a coasting pan was for stopping the image, so it does not toggle the chrome.
    // It still counts as the first half of a double tap.
    var caughtThrow = false
    detectTapAwareGestures(
        readGesture = { down ->
            caughtThrow = state.onTouchDown()
            readPreviewGesture(state, down, onGestureStart, details)
        },
        onTap = { if (!caughtThrow) onTap() },
        onDoubleTap = onDoubleTap
    )
}

/**
 * Returns the tap position relative to the viewport center, or null if the touch wasn't a tap. Anything
 * else is applied to [state] and settled here; a tap is left to the caller, which has to wait to see if it's a double.
 */
private suspend fun AwaitPointerEventScope.readPreviewGesture(
    state: PreviewZoomState,
    down: PointerInputChange,
    onGestureStart: () -> Unit,
    details: PreviewDetailsSheetState
): Offset? {
    val startedAtRest = state.isAtRest
    val velocity = VelocityTracker()
    velocity.addPosition(down.uptimeMillis, down.position)

    val slop = viewConfiguration.touchSlop
    val center = Offset(size.width / 2f, size.height / 2f)
    var mode = PreviewGesture.Undecided
    var travel = Offset.Zero
    // Sideways drag accumulated while the pan has nowhere left to go, whether this touch may hand
    // over to the pager at all, and whether a second finger has ever been down
    var edgePush = Offset.Zero
    var canPage: Boolean? = null
    var pinched = false

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.fastFilter { it.pressed }
        if (pressed.isEmpty()) break
        // The pager took the swipe on a later pass; stop competing for this gesture.
        if (mode != PreviewGesture.Transform && event.changes.any { it.isConsumed }) {
            if (mode == PreviewGesture.Dismiss) state.onGestureCancel()
            if (mode == PreviewGesture.Details) details.onDragEnd(0f)
            mode = PreviewGesture.Paging
            break
        }

        val drag = event.calculatePan()
        travel += drag
        pinched = pinched || pressed.size > 1
        pressed.first().let { velocity.addPosition(it.uptimeMillis, it.position) }

        if (mode == PreviewGesture.Undecided) {
            val sheetShown = details.isShown
            mode = when {
                pressed.size > 1 && sheetShown -> PreviewGesture.Blocked
                pressed.size > 1 || !state.isAtRest -> PreviewGesture.Transform
                travel.getDistance() <= slop -> PreviewGesture.Undecided
                abs(travel.y) <= abs(travel.x) -> PreviewGesture.Paging
                sheetShown || travel.y < 0f -> PreviewGesture.Details
                else -> PreviewGesture.Dismiss
            }
            when (mode) {
                PreviewGesture.Undecided, PreviewGesture.Blocked -> Unit
                PreviewGesture.Paging -> break
                PreviewGesture.Details -> {
                    details.onDragStart()
                    details.onDrag(travel.y - drag.y)
                }
                else -> {
                    state.onGestureStart(startedAtRest)
                    onGestureStart()
                }
            }
        }

        when (mode) {
            PreviewGesture.Transform -> {
                // Whether this touch is allowed to become a page turn, settled by where the image
                // already was when the finger first moved sideways so panning into an edge stops there
                if (canPage == null && drag.x != 0f) canPage = state.atHorizontalLimit(drag.x)

                // Armed, still pushing that way, and one finger: hand the swipe over
                if (canPage == true && pressed.size == 1 && state.atHorizontalLimit(drag.x)) {
                    edgePush += drag
                    if (abs(edgePush.x) > slop && abs(edgePush.x) > abs(edgePush.y)) {
                        state.onGestureCancel()
                        mode = PreviewGesture.Paging
                        break
                    }
                } else {
                    edgePush = Offset.Zero
                }
                state.onTransform(
                    centroid = event.calculateCentroid(useCurrent = true) - center,
                    drag = drag,
                    zoomDelta = event.calculateZoom()
                )
            }
            PreviewGesture.Dismiss -> state.onDismissDrag(drag.y)
            PreviewGesture.Details -> details.onDrag(drag.y)
            else -> Unit
        }
        if (mode != PreviewGesture.Undecided) event.changes.forEach { it.consume() }
    }

    return when (mode) {
        // Still undecided means the touch never passed slop, which is what a tap is.
        PreviewGesture.Undecided -> down.position - center
        PreviewGesture.Transform -> {
            // Only a drag can fling out, pinch release also carries vertical momentum so we ignore it
            val thrown = if (pinched) Offset.Zero else velocity.calculateVelocity().let { Offset(it.x, it.y) }
            state.onGestureEnd(dismissVelocity = 0f, panVelocity = thrown)
            null
        }
        PreviewGesture.Dismiss -> {
            state.onGestureEnd(velocity.calculateVelocity().y)
            null
        }
        PreviewGesture.Details -> {
            details.onDragEnd(velocity.calculateVelocity().y)
            null
        }
        PreviewGesture.Blocked, PreviewGesture.Paging -> null
    }
}
