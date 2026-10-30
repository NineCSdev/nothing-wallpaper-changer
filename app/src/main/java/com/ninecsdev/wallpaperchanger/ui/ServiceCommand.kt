package com.ninecsdev.wallpaperchanger.ui

/**
 * A start/stop request from a ViewModel that the activity carries out. Held until something is listening:
 * unlike a notice, one that arrives late is still wanted.
 */
sealed interface ServiceCommand {
    data object Start : ServiceCommand
    data object Stop : ServiceCommand
}
