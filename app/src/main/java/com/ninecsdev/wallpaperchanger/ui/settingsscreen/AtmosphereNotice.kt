package com.ninecsdev.wallpaperchanger.ui.settingsscreen

/** Something that happened during a mode change that the user is owed a word about; shown once as a snackbar. */
enum class AtmosphereNotice {
    /** Left atmosphere, but nothing of the user's was available, so the device's built-in took over. */
    EXIT_CLEARED,

    /** Could not leave: the live wallpaper could not be replaced, so the mode change was rolled back. */
    EXIT_FAILED,

    /** Entering atmosphere removed a separate lock-screen wallpaper so the engine could own both screens. */
    LOCK_WALLPAPER_REMOVED
}
