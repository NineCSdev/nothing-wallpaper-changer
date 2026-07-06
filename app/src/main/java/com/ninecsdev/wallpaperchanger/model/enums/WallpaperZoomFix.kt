package com.ninecsdev.wallpaperchanger.model.enums

enum class WallpaperZoomFix(val storedValue: Int) {
    OFF(0),
    BLURRED(1),
    EDGE(2);

    companion object {
        fun fromStoredValue(value: Int): WallpaperZoomFix {
            return entries.firstOrNull { it.storedValue == value } ?: OFF
        }
    }
}
