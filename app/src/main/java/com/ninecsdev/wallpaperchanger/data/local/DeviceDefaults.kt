package com.ninecsdev.wallpaperchanger.data.local

import android.os.Build

/** Screen-off delay per [Build.DEVICE] codename (stable across OS updates and regions, unlike [Build.MODEL]); unknown devices get the Phone (1) value. */
object DeviceDefaults {
    private val DELAYS_BY_CODENAME = mapOf(
        "Spacewar" to 250L, // Nothing Phone (1)
        "Metroid" to 100L   // Nothing Phone (3)
    )

    fun forThisDevice(): Long = DELAYS_BY_CODENAME[Build.DEVICE] ?: 250L
}
