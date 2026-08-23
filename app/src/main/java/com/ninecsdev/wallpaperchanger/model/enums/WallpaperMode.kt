package com.ninecsdev.wallpaperchanger.model.enums

/**
 * The user's chosen delivery mechanism for wallpapers.
 *
 * This is the *desired* mode only; the effective mode additionally requires NWC's live
 * wallpaper to actually be set as the system wallpaper.
 */
enum class WallpaperMode {
    /** A frozen image swapped on screen-off via `WallpaperManager.setStream`. */
    STATIC,

    /**
     * Live-wallpaper rendering with the Nothing OS-style unlock morph: a sharp photo on the
     * lock screen that dissolves into an abstract color-blob atmosphere on the home screen.
     */
    ATMOSPHERE
}
