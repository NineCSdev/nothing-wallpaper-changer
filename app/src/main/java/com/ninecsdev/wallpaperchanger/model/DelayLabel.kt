package com.ninecsdev.wallpaperchanger.model

/**
 * Definition of delay values to be used when setting the animation delay.
 */
enum class DelayLabel(val milliseconds: Long) {
    SHORT(250),
    MEDIUM(500),
    LONG(1000)
}
