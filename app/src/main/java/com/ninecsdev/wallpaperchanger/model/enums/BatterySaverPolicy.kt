package com.ninecsdev.wallpaperchanger.model.enums

/**
 * Defines how the app reacts when the device enters power-save (battery saver) mode.
 */
enum class BatterySaverPolicy {
    /** Stop the service entirely */
    STOP,
    /** Pause rotation, resume automatically when power-save ends */
    PAUSE,
    /** Ignore power-save mode */
    IGNORE
}
