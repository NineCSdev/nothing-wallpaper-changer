package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.ui.geometry.Offset

/*
 * Zoom rules shared by every surface that lets the user zoom an image. Each screen keeps its own bounds
 * policy; only what has to be identical in both lives here, so they cannot be tuned apart by accident.
 */

// TODO tests: see vault note tests/Preview Zoom Math.md

internal const val MaxZoom = 5f

internal const val DoubleTapZoom = 3f

/** Below this (px/s) a released drag is a stop, not a throw, and coasting it would look like drift. */
internal const val FlingMinVelocity = 50f

/**
 * Pan that holds the content point under [centroid] still while the scale goes [fromZoom] to [toZoom],
 * then applies the finger's own [drag]. Offsets are pixels, [centroid] measured from the scale pivot.
 */
internal fun focalPan(
    drag: Offset,
    centroid: Offset,
    current: Offset,
    fromZoom: Float,
    toZoom: Float
): Offset {
    if (fromZoom <= 0f) return current + drag
    val ratio = toZoom / fromZoom
    return centroid - (centroid - current) * ratio + drag
}
