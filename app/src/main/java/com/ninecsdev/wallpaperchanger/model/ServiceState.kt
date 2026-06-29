package com.ninecsdev.wallpaperchanger.model

/**
 * Represents the current state of the app.
 *
 * Prevents "impossible states" (e.g. showing 'Running' when no folder exists).
 */
sealed class ServiceState {
    /** The service is actively running and waiting for screen off. */
    data object Running : ServiceState()

    /** Temporal state for giving UI feedback while loading. */
    data object Loading : ServiceState()

    /** Temporal state while the service is in the process of stopping. */
    data object Stopping : ServiceState()

    /** The service is stopped and ready to be started. */
    data object Stopped : ServiceState()

    /** The service is paused due to power save mode but will auto-resume when power save is off. */
    data object Paused : ServiceState()

    /** The service is disabled because the system is in power save mode and was not running. */
    data object DisabledPowerSave : ServiceState()

    /** The service cannot start because the user hasn't selected a collection yet. */
    data object DisabledNoCollection : ServiceState()
}
