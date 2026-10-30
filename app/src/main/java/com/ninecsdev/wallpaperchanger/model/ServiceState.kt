package com.ninecsdev.wallpaperchanger.model

sealed class ServiceState {
    data object Running : ServiceState()

    /** Transient, for UI feedback while starting. */
    data object Loading : ServiceState()

    data object Stopping : ServiceState()

    data object Stopped : ServiceState()

    /** Paused by power save; auto-resumes when it ends. */
    data object Paused : ServiceState()

    /** Paused by Do Not Disturb; auto-resumes when it ends. */
    data object PausedDnd : ServiceState()

    /** Power save is on and the service was not running, so it cannot start. */
    data object DisabledPowerSave : ServiceState()

    data object DisabledNoCollection : ServiceState()
}
