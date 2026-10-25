package com.ninecsdev.wallpaperchanger.ui.components

import androidx.annotation.DrawableRes
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.model.enums.CropRule

/** The icon that stands for [rule] wherever a crop rule is picked or shown. */
@DrawableRes
internal fun cropRuleIconRes(rule: CropRule): Int = when (rule) {
    CropRule.CENTER -> R.drawable.icon_crop_center
    CropRule.LEFT -> R.drawable.icon_align_left
    CropRule.RIGHT -> R.drawable.icon_align_right
    CropRule.FIT -> R.drawable.icon_fit_screen
}
