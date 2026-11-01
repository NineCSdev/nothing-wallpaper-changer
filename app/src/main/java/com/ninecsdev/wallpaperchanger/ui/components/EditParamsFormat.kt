package com.ninecsdev.wallpaperchanger.ui.components

private const val MinOffset = -1f
private const val MaxOffset = 1f

internal fun coerceOffset(value: Float): Float = value.coerceIn(MinOffset, MaxOffset)

// Sliders show the persisted -1..1 offset as 0..100 (50 = centered); conversion stays in the UI layer
internal fun offsetToPercent(offset: Float): Float = (offset - MinOffset) / (MaxOffset - MinOffset) * 100f
internal fun percentToOffset(percent: Float): Float = coerceOffset(MinOffset + percent / 100f * (MaxOffset - MinOffset))

internal const val ZoomDecimals = 3

internal const val OffsetPercentDecimals = 2

// Formatting follows the device locale
internal fun formatZoom(zoom: Float): String = "%.${ZoomDecimals}f".format(zoom)

internal fun formatOffsetPercent(offset: Float): String = "%.${OffsetPercentDecimals}f".format(offsetToPercent(offset))
