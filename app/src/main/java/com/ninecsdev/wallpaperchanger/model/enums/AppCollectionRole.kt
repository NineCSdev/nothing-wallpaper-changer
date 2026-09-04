package com.ninecsdev.wallpaperchanger.model.enums

/**
 * What an app-owned collection is *for*. Null on the collections the user made themselves
 *
 * "App-owned vs user-owned" is orthogonal to `CollectionType` (how images get in): both roles below
 * are plain [CollectionType.MANUAL] collections.
 */
enum class AppCollectionRole {
    /** The Favourites collection: hearted images, rename blocked, name resolved from a string resource. */
    FAVORITES,

    /** Holds the global default wallpaper. Never listed, never selectable as active. */
    DEFAULTS
}
