package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Runs [readGesture] for every touch and turns the ones that were taps into single or double taps.
 *
 * [readGesture] reads one touch through to its end, applies whatever that touch turned out to be,
 * and returns where it was tapped (null if it wasn't a tap).
 *
 * **A tap is reported only once the double-tap window has closed without a second gesture or tap**
 */
internal suspend fun PointerInputScope.detectTapAwareGestures(
    readGesture: suspend AwaitPointerEventScope.(down: PointerInputChange) -> Offset?,
    onTap: (Offset) -> Unit,
    onDoubleTap: (Offset) -> Unit
) {
    awaitEachGesture {
        val first = readGesture(awaitFirstDown(requireUnconsumed = false)) ?: return@awaitEachGesture
        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
            awaitFirstDown(requireUnconsumed = false)
        } ?: run {
            onTap(first)
            return@awaitEachGesture
        }
        readGesture(second)?.let(onDoubleTap)
    }
}
