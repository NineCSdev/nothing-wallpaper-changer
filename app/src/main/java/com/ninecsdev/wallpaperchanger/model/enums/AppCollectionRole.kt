package com.ninecsdev.wallpaperchanger.model.enums

/** What an app-owned collection is for; null on user-made ones. Both roles are plain [CollectionType.MANUAL] collections. */
enum class AppCollectionRole {
    /** Hearted images; rename blocked, name resolved from a string resource. */
    FAVORITES,

    /** Holds the global default wallpaper. Never listed, never selectable as active. */
    DEFAULTS
}
