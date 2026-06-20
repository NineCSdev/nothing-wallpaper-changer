package com.ninecsdev.wallpaperchanger.model.enums

enum class LockscreenZoomFix(val storedValue: Int) {
    OFF(0),
    BLURRED(1),
    EDGE(2);

    companion object {
        fun fromStoredValue(value: Int): LockscreenZoomFix {
            return entries.firstOrNull { it.storedValue == value } ?: OFF
        }
    }
}
