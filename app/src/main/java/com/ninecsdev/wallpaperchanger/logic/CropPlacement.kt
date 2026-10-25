package com.ninecsdev.wallpaperchanger.logic

import com.ninecsdev.wallpaperchanger.model.enums.CropRule

/** How a [CropRule] places an unedited image on a target. */
internal object CropPlacement {

    /** One scale for both axes: cover the target, or for [CropRule.FIT] fit inside it. */
    fun scale(rule: CropRule, sourceWidth: Float, sourceHeight: Float, targetWidth: Float, targetHeight: Float): Float {
        val widthRatio = targetWidth / sourceWidth
        val heightRatio = targetHeight / sourceHeight
        return when (rule) {
            CropRule.FIT -> minOf(widthRatio, heightRatio)
            CropRule.CENTER, CropRule.LEFT, CropRule.RIGHT -> maxOf(widthRatio, heightRatio)
        }
    }

    /** Where the image sits across the spare width: -1 flush left, 0 centred, 1 flush right. Always centred vertically. */
    fun horizontalBias(rule: CropRule): Float = when (rule) {
        CropRule.LEFT -> -1f
        CropRule.RIGHT -> 1f
        CropRule.CENTER, CropRule.FIT -> 0f
    }

    /** Offset of an image [size] long within [space] at [bias]. Negative when the image overflows. */
    fun offset(space: Float, size: Float, bias: Float): Float = (space - size) * (1f + bias) / 2f
}
