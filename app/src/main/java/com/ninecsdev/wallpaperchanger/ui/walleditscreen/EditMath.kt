package com.ninecsdev.wallpaperchanger.ui.walleditscreen

import kotlin.math.abs

//TODO: Decide where to put this helper file
private const val EditValueEpsilon = 0.001f

internal fun isCloseEnough(left: Float, right: Float): Boolean =
    abs(left - right) <= EditValueEpsilon
