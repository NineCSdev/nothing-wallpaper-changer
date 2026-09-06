package com.ninecsdev.wallpaperchanger.model

import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy

/**
 * Something that happened, or that a surface wants to happen, in the rotation service's lifecycle.
 *
 * Callers state intent and obey the [LifecycleVerdict] they get back
 */
sealed class ServiceIntent {

    /** A user surface asked for the service to start. */
    data object StartRequested : ServiceIntent()

    /** The service is starting and needs the startup gate.*/
    data class ServiceStarting(
        val hasActiveCollection: Boolean,
        val policy: BatterySaverPolicy
    ) : ServiceIntent()

    /** The engine settled into the mode it was told to run in; record the state that results. */
    data object EngineReady : ServiceIntent()

    /** Teardown started. */
    data object TeardownBegan : ServiceIntent()

    /** Teardown finished: the service is gone. */
    data object TeardownFinished : ServiceIntent()

    /** The active collection was deleted, user no longer wants the service running. */
    data object ActiveCollectionDeleted : ServiceIntent()
}

/** The answer to a [ServiceIntent]: what the caller should do */
sealed class LifecycleVerdict {

    /** Taken, the state moved. Nothing to decide. */
    data object Accepted : LifecycleVerdict()

    /** The service should be up and rotating. */
    data object RunActive : LifecycleVerdict()

    /** The service should be up but not rotating, and should fall back to the default wallpaper. */
    data object RunPaused : LifecycleVerdict()

    /** The service should be up but not rotating, leaving the current wallpaper alone. */
    data object RunPausedQuiet : LifecycleVerdict()

    /** The service must not run, tear it down. */
    data object Abort : LifecycleVerdict()

    /** Already true. */
    data object NoChange : LifecycleVerdict()

    /** Illegal from the current state. */
    data class Rejected(val reason: String) : LifecycleVerdict()
}
