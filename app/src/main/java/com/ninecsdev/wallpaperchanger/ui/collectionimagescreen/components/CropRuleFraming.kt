package com.ninecsdev.wallpaperchanger.ui.collectionimagescreen.components

import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.ScaleFactor
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import com.ninecsdev.wallpaperchanger.logic.CropPlacement
import com.ninecsdev.wallpaperchanger.model.enums.CropRule
import kotlin.math.roundToInt

/** Sizes an image the way [CropPlacement] does for [rule]. */
internal data class CropRuleContentScale(val rule: CropRule) : ContentScale {
    override fun computeScaleFactor(srcSize: Size, dstSize: Size): ScaleFactor {
        val scale = CropPlacement.scale(rule, srcSize.width, srcSize.height, dstSize.width, dstSize.height)
        return ScaleFactor(scale, scale)
    }
}

/**
 * Places an image the way [CropPlacement] does, on screen axes whatever the layout direction: a
 * wallpaper's left is never mirrored. The bias is read at placement, so an animated one redraws
 * without recomposing.
 */
internal class CropRuleAlignment(private val horizontalBias: () -> Float) : Alignment {
    override fun align(size: IntSize, space: IntSize, layoutDirection: LayoutDirection) = IntOffset(
        CropPlacement.offset(space.width.toFloat(), size.width.toFloat(), horizontalBias()).roundToInt(),
        CropPlacement.offset(space.height.toFloat(), size.height.toFloat(), 0f).roundToInt()
    )
}
