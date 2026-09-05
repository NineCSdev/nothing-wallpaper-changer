package com.ninecsdev.wallpaperchanger.ui.walleditscreen.components

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.AnimationVector2D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.util.fastFilter
import com.ninecsdev.wallpaperchanger.ui.components.DoubleTapZoom
import com.ninecsdev.wallpaperchanger.ui.components.FlingMinVelocity
import com.ninecsdev.wallpaperchanger.ui.components.detectTapAwareGestures
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * The editor canvas's gesture reader: pinch/drag, a throw when a one-finger drag is released, and
 * a double tap.
 *
 * [onTransform] receives the centroid (in this node's pixel space, origin top-left), pan and zoom
 * delta per frame, and only after touch slop.
 *
 * [onDown] fires for every touch, before anything is known about it, so a coasting throw can be
 * caught the instant a finger lands.
 */
private suspend fun PointerInputScope.detectEditGestures(
    onDown: () -> Unit,
    onTransform: (centroid: Offset, pan: Offset, zoomDelta: Float) -> Unit,
    onFling: (velocity: Offset) -> Unit,
    onDoubleTap: (position: Offset) -> Unit
) {
    detectTapAwareGestures(
        readGesture = { down ->
            onDown()
            readEditGesture(down, onTransform, onFling)
        },
        onTap = { /* The single tap has no meaning on this screen */ },
        onDoubleTap = onDoubleTap
    )
}

/**
 * Reads one touch to its end and returns where it was tapped or null if it moved. A pinch and a drag
 * released below [FlingMinVelocity] reports no release velocity.
 */
private suspend fun AwaitPointerEventScope.readEditGesture(
    down: PointerInputChange,
    onTransform: (centroid: Offset, pan: Offset, zoomDelta: Float) -> Unit,
    onFling: (velocity: Offset) -> Unit
): Offset? {
    val velocity = VelocityTracker()
    velocity.addPosition(down.uptimeMillis, down.position)

    val slop = viewConfiguration.touchSlop
    var travel = Offset.Zero
    var moving = false
    var pinched = false

    while (true) {
        val event = awaitPointerEvent()
        val pressed = event.changes.fastFilter { it.pressed }
        if (pressed.isEmpty()) break

        pinched = pinched || pressed.size > 1
        val pan = event.calculatePan()
        travel += pan
        pressed.first().let { velocity.addPosition(it.uptimeMillis, it.position) }

        if (!moving && (pressed.size > 1 || travel.getDistance() > slop)) moving = true
        if (moving) {
            onTransform(event.calculateCentroid(useCurrent = true), pan, event.calculateZoom())
            event.changes.forEach { it.consume() }
        }
    }

    if (!moving) return down.position
    if (!pinched) {
        val thrown = velocity.calculateVelocity().let { Offset(it.x, it.y) }
        if (thrown.getDistance() >= FlingMinVelocity) onFling(thrown)
    }
    return null
}

/**
 * The editor canvas's gesture behavior: pinch/drag applied straight through [onGesture], a
 * throw that coasts to a stop, and a double tap that springs between rest and [DoubleTapZoom].
 *
 * [onGesture] receives one frame of transform at a time for the caller to map onto its own edit values.
 * Every animation here feeds through that same call rather than writing zoom or offset itself, so
 * a coast and a double tap land inside the bounds a drag does and cannot drift outside them.
 *
 * Only one animation runs at a time and any touch cancels it, so a finger landing on a coasting
 * image stops it dead.
 */
internal suspend fun PointerInputScope.runEditGestures(
    onGesture: (centroid: Offset, pan: Offset, zoomDelta: Float) -> Unit,
    currentZoom: () -> Float,
    currentOffset: () -> Offset,
    onSettleAtRest: () -> Unit
) {
    // The platform's own scroll deceleration
    val decay = splineBasedDecay<Offset>(this)
    // Read per frame rather than captured as `size` is still zero when this block is first entered.
    fun center() = Offset(size.width / 2f, size.height / 2f)

    coroutineScope {
        var running: Job? = null
        detectEditGestures(
            onDown = { running?.cancel() },
            onTransform = onGesture,
            onFling = { velocity ->
                running = launch {
                    // Coasted as pan deltas through the same mapping a drag uses
                    var travelled = Offset.Zero
                    var previous: Offset? = null
                    AnimationState(
                        typeConverter = Offset.VectorConverter,
                        initialValue = Offset.Zero,
                        initialVelocityVector = AnimationVector2D(velocity.x, velocity.y)
                    ).animateDecay(decay) {
                        // The offsets only reach here a frame late, so "did it move?" is asked of the frame before
                        val landed = currentOffset()
                        if (previous == landed) cancelAnimation()
                        previous = landed
                        onGesture(center(), value - travelled, 1f)
                        travelled = value
                    }
                }
            },
            onDoubleTap = { position ->
                running = launch {
                    val from = currentZoom()
                    val target = if (isCloseEnough(from, MinZoom)) DoubleTapZoom else MinZoom
                    // Stepped as ratios about the tapped point, so the point under the finger stays put
                    animate(
                        initialValue = from,
                        targetValue = target,
                        animationSpec = spring(stiffness = Spring.StiffnessMedium)
                    ) { value, _ ->
                        onGesture(position, Offset.Zero, value / currentZoom())
                    }
                    // At MinZoom nothing overflows, so the offsets no longer move the image and
                    // the mapping leaves them where they were
                    if (target == MinZoom) onSettleAtRest()
                }
            }
        )
    }
}
