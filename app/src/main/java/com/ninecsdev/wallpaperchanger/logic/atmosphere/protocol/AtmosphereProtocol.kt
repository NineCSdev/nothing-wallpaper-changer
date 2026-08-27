package com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol

/**
 * The signaling half of the hand-off between the app and the live-wallpaper engine.
 *
 * [AtmosphereSource] is the other half: the durable file the engine reads. These are the nudges
 * that say a new one is on disk, that it was shown, and that none was found.
 *
 * **What belongs here:** anything the two sides must agree on to interoperate,
 * *would the other half break if this changed without them knowing?*
 */
object AtmosphereProtocol {

    /** Sent by the app after replacing the source container on disk. */
    const val ACTION_RELOAD = "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_RELOAD"

    /**
     * Boolean extra on [ACTION_RELOAD]:
     * `true` when the reload is a rotation delivery (that advances the magazine),
     * `false` for non-rotation reloads.
     *
     * *Absence is treated as false.*
     */
    const val EXTRA_FROM_ROTATION = "from_rotation"

    /**
     * Sent by the engine once a **rotation** delivery has actually been adopted. The app's rotation
     * service advances the magazine on this, so a delivery never shown doesn't consume a wallpaper.
     */
    const val ACTION_DISPLAYED = "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_DISPLAYED"

    /**
     * Sent by the engine when it starts and finds no source on disk. The app answers by rendering
     * one and delivering it.
     *
     * The receiver for this is declared in the manifest, because the engine can start with the
     * app's process absent, so **this string also appears verbatim in `AndroidManifest.xml`** and
     * the two must stay identical.
     */
    const val ACTION_SOURCE_REQUESTED =  "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_SOURCE_REQUESTED"
}
