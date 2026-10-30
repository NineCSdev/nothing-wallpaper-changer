package com.ninecsdev.wallpaperchanger.logic

import com.ninecsdev.wallpaperchanger.model.enums.CropRule

internal object CropPlacement {

    /** One scale for both axes: covers the target, or fits inside it for [CropRule.FIT]. */
    fun scale(rule: CropRule, sourceWidth: Float, sourceHeight: Float, targetWidth: Float, targetHeight: Float): Float {
        val widthRatio = targetWidth / sourceWidth
        val heightRatio = targetHeight / sourceHeight
        return when (rule) {
            CropRule.FIT -> minOf(widthRatio, heightRatio)
            CropRule.CENTER, CropRule.LEFT, CropRule.RIGHT -> maxOf(widthRatio, heightRatio)
        }
    }

    /** -1 flush left, 0 centred, 1 flush right across the spare width. Vertically always centred. */
    fun horizontalBias(rule: CropRule): Float = when (rule) {
        CropRule.LEFT -> -1f
        CropRule.RIGHT -> 1f
        CropRule.CENTER, CropRule.FIT -> 0f
    }

    /** Negative when the image overflows [space]. */
    fun offset(space: Float, size: Float, bias: Float): Float = (space - size) * (1f + bias) / 2f
}
