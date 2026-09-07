package com.ninecsdev.wallpaperchanger.ui

/**
 * A one-off request from a screen's ViewModel that the rotation service be started or stopped.
 *
 * The act itself stays with the activity. Commands are held until something is listening:
 * unlike a notice, one that arrives late is still wanted.
 */
sealed interface ServiceCommand {
    data object Start : ServiceCommand
    data object Stop : ServiceCommand
}
