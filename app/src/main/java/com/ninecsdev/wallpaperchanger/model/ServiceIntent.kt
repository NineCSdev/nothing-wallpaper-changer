package com.ninecsdev.wallpaperchanger.model

import com.ninecsdev.wallpaperchanger.model.enums.BatterySaverPolicy

/** Something that happened, or that a surface wants to happen, in the service's lifecycle; callers obey the [LifecycleVerdict] returned. */
sealed class ServiceIntent {

    data object StartRequested : ServiceIntent()

    data class ServiceStarting(
        val hasActiveCollection: Boolean,
        val policy: BatterySaverPolicy
    ) : ServiceIntent()

    data object EngineReady : ServiceIntent()

    data object TeardownBegan : ServiceIntent()

    data object TeardownFinished : ServiceIntent()

    data object ActiveCollectionDeleted : ServiceIntent()
}

sealed class LifecycleVerdict {

    data object Accepted : LifecycleVerdict()

    data object RunActive : LifecycleVerdict()

    /** Up but not rotating; the current wallpaper stays. */
    data object RunPaused : LifecycleVerdict()

    /** [RunPaused], caused by Do Not Disturb rather than by power save. Named apart so the UI can say which. */
    data object RunPausedQuiet : LifecycleVerdict()

    data object Abort : LifecycleVerdict()

    /** The intent was already true. */
    data object NoChange : LifecycleVerdict()

    data class Rejected(val reason: String) : LifecycleVerdict()
}
