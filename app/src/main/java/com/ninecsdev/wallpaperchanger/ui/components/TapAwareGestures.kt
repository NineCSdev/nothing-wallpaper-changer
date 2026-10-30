package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Runs [readGesture] for every touch and turns taps into single or double taps. It reads one touch to its end,
 * applies what it turned out to be, and returns where it was tapped (null if not a tap).
 * A tap is reported only once the double-tap window has closed without a second tap.
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
