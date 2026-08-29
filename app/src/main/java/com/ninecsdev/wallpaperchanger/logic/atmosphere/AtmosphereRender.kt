package com.ninecsdev.wallpaperchanger.logic.atmosphere

import android.graphics.Bitmap
import com.ninecsdev.wallpaperchanger.logic.atmosphere.protocol.VertexInfo

/**
 * A render destined for the atmosphere engine: the framed pixels, and its palette.
 *
 * The two travel together because they come from **different** bitmaps and only the render pipeline
 * holds both. [bitmap] carries the framing the engine should draw; [seeds] are quantized from the
 * fitted photo *before* framing.
 *
 * Caller owns [bitmap] and must recycle it.
 */
class AtmosphereRender(val bitmap: Bitmap, val seeds: List<VertexInfo>)
