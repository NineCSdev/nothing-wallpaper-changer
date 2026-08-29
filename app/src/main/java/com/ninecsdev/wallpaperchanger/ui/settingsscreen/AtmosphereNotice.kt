package com.ninecsdev.wallpaperchanger.ui.settingsscreen

/**
 * Something that happened during a mode change and that the user is owed a word about, shown once
 * as a snackbar and then cleared.
 *
 * One signal rather than one per event: they share a surface, a lifecycle and a clear-on-shown
 * discipline, so the only thing that actually varies is the sentence.
 */
enum class AtmosphereNotice {
    /** Left atmosphere, but nothing of the user's was available, so the device's built-in took over. */
    EXIT_CLEARED,

    /** Could not leave: the live wallpaper could not be replaced, so the mode change was rolled back. */
    EXIT_FAILED,

    /** Entering atmosphere removed a separate lock-screen wallpaper so the engine could own both screens. */
    LOCK_WALLPAPER_REMOVED
}
