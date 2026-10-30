package com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol

/** The signaling half of the app/engine hand-off; [AtmosphereSource] is the durable-file half. */
object AtmosphereProtocol {

    const val ACTION_RELOAD = "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_RELOAD"

    /** Boolean extra on [ACTION_RELOAD]: true for a rotation delivery (advances the magazine). Absent means false. */
    const val EXTRA_FROM_ROTATION = "from_rotation"

    /** Sent by the engine once a rotation delivery is adopted; the magazine advances only on this. */
    const val ACTION_DISPLAYED = "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_DISPLAYED"

    /** Sent by the engine when it finds no source on disk. Also appears verbatim in `AndroidManifest.xml`; keep identical. */
    const val ACTION_SOURCE_REQUESTED =  "com.ninecsdev.wallpaperchanger.ACTION_ATMOSPHERE_SOURCE_REQUESTED"
}
