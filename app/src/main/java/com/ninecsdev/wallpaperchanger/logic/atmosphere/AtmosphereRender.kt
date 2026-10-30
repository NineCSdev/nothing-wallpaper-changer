package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.graphics.Bitmap
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo

/**
 * [seeds] are quantized from the fitted photo *before* the framing that [bitmap] carries.
 * Caller owns [bitmap] and must recycle it.
 */
class AtmosphereRender(val bitmap: Bitmap, val seeds: List<VertexInfo>)
