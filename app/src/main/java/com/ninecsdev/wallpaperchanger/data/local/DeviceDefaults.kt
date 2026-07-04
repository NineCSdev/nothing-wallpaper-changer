package com.ninecsdev.wallpaperchanger.data.local

import android.os.Build

/**
 * Screen-off delay defaults tuned per device, keyed by [Build.DEVICE] codename
 * (stable across OS updates/regions, unlike the human-readable [Build.MODEL]).
 * Devices not in the table fall back to the Phone (1) value.
 */
object DeviceDefaults {
    private val DELAYS_BY_CODENAME = mapOf(
        "Spacewar" to 250L, // Nothing Phone (1)
        "Metroid" to 100L   // Nothing Phone (3)
    )

    fun forThisDevice(): Long = DELAYS_BY_CODENAME[Build.DEVICE] ?: 250L
}
