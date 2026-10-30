package com.ninecsdev.wallpaperchanger.model.enums

/** The *desired* delivery mechanism; the effective mode additionally requires our live wallpaper to be the system wallpaper. */
enum class WallpaperMode {
    /** A frozen image swapped on screen-off via `WallpaperManager.setStream`. */
    STATIC,

    /** Live wallpaper: a sharp photo on the lock screen that dissolves into a color-blob atmosphere on unlock. */
    ATMOSPHERE
}
