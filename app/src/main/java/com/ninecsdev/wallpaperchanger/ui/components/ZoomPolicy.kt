package com.ninecsdev.wallpaperchanger.ui.components

import androidx.compose.ui.geometry.Offset

/*
 * The zoom rules shared by every surface that lets the user zoom an image.
 *
 * Each screen keeps its own bounds policy. What lives here is only what has to be identical in
 * both, so the two cannot be tuned apart by accident.
 */

// TODO tests: see vault note tests/Preview Zoom Math.md

/** Furthest anything in the app zooms an image. */
internal const val MaxZoom = 5f

/** Where a double tap zooms to, and what the next one brings it back from. */
internal const val DoubleTapZoom = 3f

/** Below this (px/s) a released drag is a stop, not a throw, and coasting it would look like drift. */
internal const val FlingMinVelocity = 50f

/**
 * Pan that holds the content point under [centroid] still while the scale goes [fromZoom] to
 * [toZoom], then applies the finger's own [drag].
 *
 * All offsets are pixels, [centroid] measured from the point the scale pivots about.
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
