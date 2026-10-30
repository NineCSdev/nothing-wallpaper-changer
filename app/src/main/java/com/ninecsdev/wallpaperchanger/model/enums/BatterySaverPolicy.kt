package com.ninecsdev.wallpaperchanger.model.enums

/** How the app reacts when the device enters power-save mode. */
enum class BatterySaverPolicy {
    STOP,
    /** Resumes automatically when power-save ends. */
    PAUSE,
    IGNORE
}
